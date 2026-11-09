// A voice call through OpenAI's realtime API: a voice model on a WebRTC call with the phone, which handles small talk
// itself and hands everything else to Otto (the bot's agent) with its ask_brain tool.
//
// The phone makes a WebRTC offer and the box creates the call with it (POST /v1/realtime/calls, with a key and
// the session: prompt, voice, tool, transcription), so the key never leaves the box. The key is an OpenAI API key,
// or, with auth "codex", a short-lived realtime key minted from the Codex ChatGPT sign-in for this call. The audio
// then goes straight between the phone and OpenAI; the box joins the same call on a side connection
// (wss …/realtime?call_id=), where it sees what's said and done, and answers the tool.
//
// ask_brain is fire and forget: the box hands Otto everything said since the last hand-off, and answers the tool at
// once ("sent"), so the voice says it's on it. The phone then mutes its microphone, so noise and talk around it
// don't start turns while Otto works; the user can still tap the pill to talk. Otto's replies come back on the
// channel whenever they're ready; each is spoken word for word at the next gap (the user not talking, the voice not
// mid-answer), with the voice's tools off for it, and unmutes the phone so the user can answer.
import WebSocket from 'ws';
import { Call, type CallHost, speakable } from './call.js';
import { codexAccessToken, mintRealtimeKey } from './codex-auth.js';

const API = 'https://api.openai.com/v1/realtime';

export const PROMPT = `You are the voice of Otto, the user's personal assistant. You are only Otto's ears and mouth. Otto's brain is a separate agent on the user's computer: it has their files, phone, apps, accounts, notes, calendar, and every earlier conversation with the user. You have none of that, and you remember nothing from before this call.

- Small talk and general knowledge (greetings, facts, definitions, quick maths): answer yourself, briefly.
- Anything about the user, their things or their earlier conversations, or anything that needs doing (looking something up on their computer or phone, sending, scheduling, changing anything): call ask_brain with what the user wants. Never guess, never say you can't access something, never make up an answer: the brain can.
- Every request for information or action goes to ask_brain, even one that repeats an earlier question: things may have changed. Never wrap up the conversation instead of answering.
- ask_brain returns at once; the answer comes later. After calling it, say once, in a few words, that you're on it. Then be quiet until Otto's message arrives: don't fill the silence, don't say you're still checking. If the user asks how it's going, say once that it's still working. They can talk about other things meanwhile.
- Background noise, a cough, or a fragment like "so" or "um" is not a question: don't answer it, wait for the user to finish.
- When you're asked to say a message from Otto, say it.
- To the user, you and the brain are one: Otto. Never mention a brain; say "let me check", not "I've asked the brain".

Speak naturally and briefly, like a person on a phone call, in the user's language.`;

const TOOL = {
  type: 'function', name: 'ask_brain',
  description: "Hand a request to Otto's brain, which has the user's computer, files, phone, accounts and memory. Returns at once; "
    + 'the answer comes later as a message from Otto.',
  parameters: {
    type: 'object',
    properties: { request: { type: 'string', description: 'What the user wants, in their words, with anything the brain needs to know' } },
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
        // on its loudspeaker. Both can be changed in the channel's config (turnDetection, noiseReduction).
        input: {
          transcription: { model: 'gpt-4o-mini-transcribe' },
          turn_detection: this.o.turnDetection ?? { type: 'semantic_vad', eagerness: 'auto' },
          noise_reduction: this.o.noiseReduction === undefined ? { type: 'far_field' } : this.o.noiseReduction,
        },
        output: { voice: this.o.voice || 'marin' },
      },
      tools: [TOOL], tool_choice: 'auto',
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
    ws.on('open', () => { this.up = true; this.update(); this.log('joined'); this.later(0); }); // Otto may have spoken already
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

  private send(e: unknown) {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(e));
  }

  private event(e: any) {
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

  /** The voice's tool calls in one answer: hand Otto everything said since last time, with what the voice asks, and
   * tell the voice at once that it's sent. */
  private async tools(calls: any[]) {
    if (!calls.length) return;
    const asks: string[] = [];
    for (const c of calls) {
      let request = '';
      try { request = String(JSON.parse(c.arguments || '{}').request ?? ''); } catch {}
      const ok = c.name === 'ask_brain';
      if (ok && request) asks.push(request);
      this.send({ type: 'conversation.item.create', item: { type: 'function_call_output', call_id: c.call_id,
        output: ok ? "Sent to Otto. The answer comes later as a message; tell the user you're on it." : `There is no tool ${c.name}.` } });
    }
    this.send({ type: 'response.create' });
    if (!asks.length) return;
    this.mute(true);
    await this.transcript.settled(1500);
    const b = this.transcript.take();
    this.log(`hand-off: ${b.lines.length} lines, ${asks.length} asked`);
    this.host.toAgent([
      b.lines.length ? 'On the call since your last message:' : '',
      ...b.lines,
      ...asks.map(a => `The voice passes on: ${a}`),
    ].filter(Boolean).join('\n'), false, b.delivered);
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
    this.mute(false);
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
