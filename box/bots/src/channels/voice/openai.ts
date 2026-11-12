// A voice call through OpenAI's realtime API: a voice model on a WebRTC call with the phone, which handles small talk
// itself and hands everything else to Otto (the bot's agent) with its ask_otto tool.
//
// The phone makes a WebRTC offer and the box creates the call with it (POST /v1/realtime/calls, with a key and
// the session: prompt, voice, tool, transcription), so the key never leaves the box. The key is an OpenAI API key,
// or, with auth "codex", a short-lived realtime key minted from the Codex ChatGPT sign-in for this call. The audio
// then goes straight between the phone and OpenAI; the box joins the same call on a side connection
// (wss …/realtime?call_id=), where it sees what's said and done, and answers the tool.
//
// A call isn't a blank slate: as the box joins, the voice gets what was said on earlier calls (the bot's saved voice
// transcript, history.ts) as the conversation so far, between two notes that it's earlier and already dealt with.
//
// list_chats and message_chat (sessions.ts) let the voice find the chat the user names (one of their Claude Code
// sessions on the box) and pass their words to it directly; they answer when done, and the voice says what happened.
//
// ask_otto is fire and forget: the box hands Otto everything said since the last hand-off, and answers the tool at
// once ("sent"), so the voice says it's on it and stays free. Otto's replies come back on the channel whenever they're
// ready; each is spoken word for word at the next gap (the user not talking, the voice not mid-answer), with the
// voice's tools off for it.
import WebSocket from 'ws';
import { Call, type CallHost, speakable } from './call.js';
import { codexAccessToken, mintRealtimeKey } from './codex-auth.js';
import { isSessionTool, SESSION_TOOLS, sessionTool } from './sessions.js';
import { tap, watching } from './watch.js';

const API = 'https://api.openai.com/v1/realtime';

export const PROMPT = `You are the voice of Otto, the user's personal assistant. You are only Otto's ears and mouth. Otto itself is a separate agent on the user's computer: it has their files, phone, apps, accounts, notes, calendar, and every earlier conversation with the user. You have none of that; of earlier calls you have only what was said on them, at the start of this conversation, already dealt with: use it for follow-ups ("where were we?"), never answer it again.

- Small talk and general knowledge (greetings, facts, definitions, quick maths): answer yourself, briefly.
- Anything about the user, their things or their earlier conversations, or anything that needs doing (looking something up on their computer or phone, sending, scheduling, changing anything): call ask_otto with what the user wants. Never guess, never say you can't access something, never make up an answer: Otto can.
- Every request for information or action goes to ask_otto, even one that repeats an earlier question: things may have changed. Never wrap up the conversation instead of answering.
- ask_otto returns at once; the answer comes later. After calling it, say once, in a few words, that you're on it. Then be quiet until Otto's message arrives: don't fill the silence, don't say you're still checking. If the user asks how it's going, say once that it's still working. They can talk about other things meanwhile.
- Background noise, a cough, or a fragment like "so" or "um" is not a question: don't answer it, wait for the user to finish.
- The user's chats are their Claude Code sessions on their computer, each with a name (like "cc-mobile"). When the user names a chat to tell something ("tell the cc-mobile chat …"), find it with list_chats and pass their words on with message_chat, as they said them, in the first person, with no greeting and no summary. If the match isn't obvious, say the chat's name and check with the user before sending. The chat replies to the user through Otto on its own. Everything else, about chats or not, goes to ask_otto.
- When you're asked to say a message from Otto, say it.
- To the user, you and Otto are one. Say "let me check", not "I've asked Otto".

Speak naturally and briefly, like a person on a phone call, in the user's language.`;

const TOOL = {
  type: 'function', name: 'ask_otto',
  description: "Hand a request to Otto, the user's assistant, which has the user's computer, files, phone, accounts and memory. Returns at once; "
    + 'the answer comes later as a message from Otto.',
  parameters: {
    type: 'object',
    properties: { request: { type: 'string', description: 'What the user wants, in their words, with anything Otto needs to know' } },
    required: ['request'],
  },
};

/** Dollars per million tokens: audio in, audio out, text in, text out. Only for the log. */
const PRICES: Record<string, number[]> = {
  'gpt-realtime-2.1-mini': [10, 20, 0.6, 2.4],
  'gpt-realtime-2.1': [32, 64, 4, 16],
};

export interface OpenAIOptions {
  /** An OpenAI API key. Unused when auth is "codex". */
  apiKey?: string;
  /** "codex" mints a realtime key from the Codex ChatGPT sign-in. Anything else uses apiKey. */
  auth?: string;
  model?: string;
  voice?: string;
  instructions?: string;
  turnDetection?: Record<string, unknown>;
  noiseReduction?: Record<string, unknown> | null;
  /** OpenAI's transcription settings as they are (model, language or languages, keywords, prompt, delay). */
  transcription?: Record<string, unknown>;
}

export class OpenAICall extends Call {
  readonly mode = 'webrtc';
  private ws: WebSocket | null = null;
  private callId = '';
  /** The key this call was opened with: the API key, or a realtime key minted for the call. */
  private key = '';
  private model: string;
  private queue: string[] = []; // Otto's words, waiting for a gap
  private responding = false; // the voice is making an answer
  private relaying = false; // the answer being made is one of Otto's messages
  private asked: string | null = null; // Otto's message just sent to be said, until the voice starts on it
  private gapTimer: NodeJS.Timeout | null = null;
  private usage = { audioIn: 0, audioOut: 0, textIn: 0, textOut: 0 };

  constructor(host: CallHost, private o: OpenAIOptions) {
    super(host);
    this.model = o.model || 'gpt-realtime-2.1';
  }

  async begin(body: any): Promise<Record<string, unknown>> {
    const sdp = body?.sdp;
    if (typeof sdp !== 'string' || !sdp.startsWith('v=')) throw new Error('no SDP offer');
    const session = {
      type: 'realtime', model: this.model, instructions: this.o.instructions || PROMPT,
      audio: {
        // Semantic turn detection waits for a finished thought rather than any pause; noise reduction for a phone
        // on its loudspeaker. These and the transcription can be changed in the channel's config (turnDetection,
        // noiseReduction, transcription), passed to OpenAI as they are.
        input: {
          transcription: this.o.transcription ?? { model: 'gpt-4o-transcribe' },
          turn_detection: this.o.turnDetection ?? { type: 'semantic_vad', eagerness: 'auto' },
          noise_reduction: this.o.noiseReduction === undefined ? { type: 'far_field' } : this.o.noiseReduction,
        },
        output: { voice: this.o.voice || 'marin' },
      },
      tools: [TOOL, ...SESSION_TOOLS], tool_choice: 'auto',
    };
    const form = new FormData();
    form.set('sdp', sdp);
    form.set('session', JSON.stringify(session));
    this.key = await this.bearer(session);
    const t = Date.now();
    const r = await fetch(`${API}/calls`, { method: 'POST', headers: { authorization: `Bearer ${this.key}` }, body: form });
    const answer = await r.text();
    if (!r.ok) throw new Error(`OpenAI refused the call (${r.status}): ${redact(answer).slice(0, 300)}`);
    this.callId = (r.headers.get('location') ?? '').split('/').pop() ?? '';
    if (!this.callId) throw new Error('OpenAI gave no call id');
    this.log(`call ${this.callId.slice(0, 12)}… (${this.model}) in ${Date.now() - t} ms`);
    this.join();
    return { sdp: answer };
  }

  /** The API key, or a realtime key just minted from the Codex sign-in. */
  private async bearer(session: unknown): Promise<string> {
    if (this.o.auth === 'codex') {
      const key = await mintRealtimeKey(await codexAccessToken(), session);
      this.log('minted a realtime key from the Codex sign-in');
      return key;
    }
    if (!this.o.apiKey) throw new Error('no API key');
    return this.o.apiKey;
  }

  /** The side connection to the call. */
  private join() {
    const ws = new WebSocket(`${API}?call_id=${encodeURIComponent(this.callId)}`, { headers: { authorization: `Bearer ${this.key}` } });
    this.ws = ws;
    ws.on('open', () => {
      this.remember();
      this.up = true;
      this.update();
      this.log('joined');
      this.later(0); // Otto may have spoken already
    });
    ws.on('message', d => {
      let e: any;
      try { e = JSON.parse(String(d)); } catch { return; }
      try { this.event(e); } catch (err: any) { this.log(`event ${e.type}: ${err.message}`); }
    });
    ws.on('error', e => this.log(`side connection: ${redact(e.message)}`));
    ws.on('close', code => {
      if (this.ws !== ws) return;
      this.ws = null;
      this.log(`side connection closed (${code})`);
      if (!this.stopping) void this.stop('closed by OpenAI');
    });
  }

  /** What was said on earlier calls, as the conversation so far. */
  private remember() {
    const h = this.host.history;
    const earlier = (h?.all ?? []).filter(e => e.text && new Date(e.at).getTime() < this.created);
    if (!earlier.length) return;
    const mins = Math.round((this.created - new Date(earlier.at(-1)!.at).getTime()) / 60_000);
    const ago = mins < 2 ? 'a moment ago' : mins < 90 ? `${mins} minutes ago` : `${Math.round(mins / 60)} hours ago`;
    const note = (text: string) => this.send({ type: 'conversation.item.create', item: { type: 'message', role: 'system', content: [{ type: 'input_text', text }] } });
    note(`What was said on earlier calls, oldest first (the last call ended ${ago}). It has all been dealt with: don't answer it again.`);
    for (const e of earlier) {
      const text = e.role === 'otto' ? `(Otto) ${e.text}` : e.text!;
      this.send({ type: 'conversation.item.create', item: e.role === 'user'
        ? { type: 'message', role: 'user', content: [{ type: 'input_text', text }] }
        : { type: 'message', role: 'assistant', content: [{ type: 'output_text', text }] } });
    }
    note('This call starts now.');
    this.log(`remembered ${earlier.length} lines from earlier calls`);
  }

  private send(e: unknown) {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(e));
  }

  /** What the call hears and says, word by word, and the voice model's conversation, for the watch page (watch.ts). */
  private heardSoFar = new Map<string, string>();
  private voiceSoFar = '';
  private watch(e: any) {
    if (!watching) return;
    switch (e.type) {
      case 'conversation.item.input_audio_transcription.delta': {
        const t = (this.heardSoFar.get(e.item_id) ?? '') + String(e.delta ?? '');
        this.heardSoFar.set(e.item_id, t);
        return tap(this.id, 'user-partial', t);
      }
      case 'conversation.item.input_audio_transcription.completed': {
        this.heardSoFar.delete(e.item_id);
        const lp: number[] = (e.logprobs ?? []).map((x: any) => Number(x.logprob)).filter((n: number) => Number.isFinite(n));
        const mean = lp.length ? lp.reduce((a, b) => a + b, 0) / lp.length : undefined;
        tap(this.id, 'item-text', String(e.transcript ?? '') || '(nothing)', mean, e.item_id);
        return tap(this.id, 'user', String(e.transcript ?? '') || '(nothing)', mean);
      }
      case 'conversation.item.added': {
        const i = e.item ?? {};
        const text = i.type === 'function_call' ? `${i.name ?? 'tool'}(${i.arguments ?? ''})`
          : i.type === 'function_call_output' ? `→ ${i.output ?? ''}`
          : (i.content ?? []).map((c: any) => c.text ?? c.transcript ?? '').join(' ').trim();
        return tap(this.id, 'item', text, undefined, i.id, i.type === 'message' ? i.role : i.type);
      }
      case 'conversation.item.deleted': return tap(this.id, 'item-gone', '', undefined, e.item_id);
      case 'response.output_audio_transcript.delta':
        this.voiceSoFar += String(e.delta ?? '');
        tap(this.id, 'item-text', this.voiceSoFar, undefined, e.item_id);
        return tap(this.id, 'voice-partial', this.voiceSoFar);
      case 'response.output_audio_transcript.done':
        this.voiceSoFar = '';
        tap(this.id, 'item-text', String(e.transcript ?? ''), undefined, e.item_id);
        return tap(this.id, 'voice', String(e.transcript ?? ''));
      case 'input_audio_buffer.speech_started': return tap(this.id, 'vad', 'OpenAI: speech start');
      case 'input_audio_buffer.speech_stopped': return tap(this.id, 'vad', 'OpenAI: speech stop');
      case 'input_audio_buffer.committed': return tap(this.id, 'vad', 'committed');
      case 'output_audio_buffer.cleared': return tap(this.id, 'vad', 'voice audio cleared');
    }
  }

  private event(e: any) {
    this.watch(e);
    switch (e.type) {
      case 'input_audio_buffer.speech_started':
        this.userSpeaking = true;
        break;
      case 'input_audio_buffer.speech_stopped':
        this.userSpeaking = false;
        this.later(900); // the voice's own answer usually starts in this time; Otto's words wait for it
        break;
      case 'conversation.item.added': case 'conversation.item.created':
        if (e.item?.type === 'message' && e.item.role === 'user' && e.item.content?.some((c: any) => c.type === 'input_audio')) {
          this.transcript.hold(e.item.id, 'user');
        }
        break;
      case 'conversation.item.input_audio_transcription.completed':
        this.transcript.fill(e.item_id, String(e.transcript ?? ''));
        break;
      case 'conversation.item.input_audio_transcription.failed':
        this.transcript.fill(e.item_id, '');
        break;
      case 'response.created':
        this.responding = true;
        this.asked = null;
        break;
      case 'response.output_audio_transcript.done':
        this.transcript.add(this.relaying ? 'otto' : 'voice', String(e.transcript ?? ''));
        break;
      case 'output_audio_buffer.started':
        this.voiceSpeaking = true;
        break;
      case 'output_audio_buffer.stopped': case 'output_audio_buffer.cleared':
        this.voiceSpeaking = false;
        this.later(300);
        break;
      case 'response.done':
        this.responding = false;
        this.relaying = false;
        this.count(e.response?.usage);
        void this.tools((e.response?.output ?? []).filter((i: any) => i.type === 'function_call'));
        this.later(300);
        break;
      case 'error':
        this.log(`OpenAI: ${e.error?.code ?? ''} ${redact(String(e.error?.message ?? '')).slice(0, 200)}`);
        if (this.asked !== null) {
          // Refused before it started. Most often the voice had just begun an answer of its own: Otto's message
          // goes back to the front, for the gap after that answer (its response.done). Otherwise there's no
          // answer under way, and it's tried once more in a moment.
          const busy = e.error?.code === 'conversation_already_has_active_response';
          this.queue.unshift(this.asked);
          this.asked = null;
          this.relaying = false;
          this.responding = busy;
          if (!busy) this.later(1000);
        }
        break;
    }
    this.update();
  }

  /** The voice's tool calls in one answer. ask_otto: hand Otto everything said since last time, with what the voice
   * asks, and tell the voice at once that it's sent. The session tools answer when they're done. */
  private async tools(calls: any[]) {
    if (!calls.length) return;
    const asks: string[] = [];
    let answered = false;
    for (const c of calls) {
      let args: any = {};
      try { args = JSON.parse(c.arguments || '{}'); } catch {}
      if (isSessionTool(c.name)) { void this.sessionTool(c.call_id, c.name, args); continue; }
      const request = String(args.request ?? '');
      const ok = c.name === 'ask_otto';
      if (ok && request) asks.push(request);
      this.send({ type: 'conversation.item.create', item: { type: 'function_call_output', call_id: c.call_id,
        output: ok ? "Sent to Otto. The answer comes later as a message; tell the user you're on it." : `There is no tool ${c.name}.` } });
      answered = true;
    }
    if (answered) this.send({ type: 'response.create' });
    if (!asks.length) return;
    await this.transcript.settled(1500);
    const b = this.transcript.take();
    this.log(`hand-off: ${b.lines.length} lines, ${asks.length} asked`);
    this.host.toAgent([
      b.lines.length ? 'On the call since your last message:' : '',
      ...b.lines,
      ...asks.map(a => `The voice passes on: ${a}`),
      "(The voice has told the user you're on it: send only the answer, and a progress note only past about 20 s.)",
    ].filter(Boolean).join('\n'), false, b.delivered);
  }

  /** list_chats or message_chat, run; its result goes to the voice when it's free to answer. */
  private async sessionTool(callId: string, name: string, args: any) {
    const t = Date.now();
    const output = await sessionTool(name, args);
    this.log(`${name} in ${Date.now() - t} ms${output.startsWith('Failed') ? `: ${output.slice(0, 200)}` : ''}`);
    while (!this.stopping && (this.responding || this.userSpeaking)) await new Promise(r => setTimeout(r, 300));
    if (this.stopping) return;
    this.send({ type: 'conversation.item.create', item: { type: 'function_call_output', call_id: callId, output } });
    this.send({ type: 'response.create' });
  }

  /** Muting mid-sentence sends no more audio, so the voice would never hear the pause that ends the sentence: what
   * was said so far is taken as the user's turn now, and answered (or handed to Otto) like any other. */
  mute(on: boolean) {
    super.mute(on);
    if (!on || !this.userSpeaking) return;
    this.userSpeaking = false;
    this.send({ type: 'input_audio_buffer.commit' });
    if (!this.responding) this.send({ type: 'response.create' });
    this.log('muted mid-sentence: taken as said');
    this.update();
  }

  /** The user tapped the pill while the voice talks: stop it now. */
  interrupt() {
    if (!this.responding && !this.voiceSpeaking) return;
    this.send({ type: 'response.cancel' });
    this.send({ type: 'output_audio_buffer.clear' });
    this.log('interrupted');
  }

  say(text: string) {
    const words = speakable(text);
    if (!words) return;
    this.queue.push(words);
    this.later(0);
  }

  /** Looks for a gap to speak Otto's next message in, after a moment. */
  private later(ms: number) {
    if (this.gapTimer) clearTimeout(this.gapTimer);
    this.gapTimer = setTimeout(() => { this.gapTimer = null; this.speakNext(); }, ms);
  }

  private speakNext() {
    if (!this.queue.length || !this.up || this.stopping) return;
    if (this.userSpeaking || this.responding || this.voiceSpeaking) return; // the events that end these look again
    const text = this.queue.shift()!;
    this.responding = true;
    this.relaying = true;
    this.asked = text;
    this.send({ type: 'conversation.item.create', item: { type: 'message', role: 'system',
      content: [{ type: 'input_text', text: `Message from Otto for the user: ${text}` }] } });
    this.send({ type: 'response.create', response: { tool_choice: 'none',
      instructions: "Say Otto's message to the user now, word for word, and nothing else." } });
  }

  private count(u: any) {
    if (!u) return;
    this.usage.audioIn += u.input_token_details?.audio_tokens ?? 0;
    this.usage.textIn += u.input_token_details?.text_tokens ?? 0;
    this.usage.audioOut += u.output_token_details?.audio_tokens ?? 0;
    this.usage.textOut += u.output_token_details?.text_tokens ?? 0;
  }

  protected async end(): Promise<string[]> {
    if (this.gapTimer) clearTimeout(this.gapTimer);
    const ws = this.ws;
    this.ws = null;
    if (this.callId) await this.hangUp();
    ws?.close();
    const u = this.usage, p = PRICES[this.model];
    const cost = p ? (u.audioIn * p[0] + u.audioOut * p[1] + u.textIn * p[2] + u.textOut * p[3]) / 1e6 : null;
    const mins = (Date.now() - this.created) / 60_000;
    this.log(`tokens: audio ${u.audioIn} in, ${u.audioOut} out; text ${u.textIn} in, ${u.textOut} out`
      + (cost !== null ? `; about $${cost.toFixed(3)} (${(cost / Math.max(mins, 0.1)).toFixed(3)}/min)` : ''));
    return this.queue.splice(0);
  }

  /** Ends the call at OpenAI. A minted key lasts about ten minutes, so a longer call hangs up with the Codex
   * access token when the key has expired. */
  private async hangUp() {
    const url = `${API}/calls/${encodeURIComponent(this.callId)}/hangup`;
    const send = (key: string) => fetch(url, { method: 'POST', headers: { authorization: `Bearer ${key}` } });
    try {
      const r = await send(this.key);
      if (r.ok || this.o.auth !== 'codex' || r.status !== 401) return;
      await send(await codexAccessToken());
    } catch { /* the websocket close below ends our side either way */ }
  }
}

function redact(s: string): string {
  return s.replace(/(sk-[\w-]{6,}|ek_[\w-]{6,}|eyJ[\w-]{20,})/g, '[key]');
}
