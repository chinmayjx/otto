// A voice call through OpenAI's realtime API: a voice model on a WebRTC call with the phone, which handles small talk
// itself and hands everything else to Otto (the bot's agent) with its ask_brain tool.
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
// ask_brain is fire and forget: the box hands Otto everything said since the last hand-off, and answers the tool at
// once ("sent"), so the voice says it's on it and stays free. Otto's replies come back on the channel whenever they're
// ready; each is spoken word for word at the next gap (the user not talking, the voice not mid-answer), with the
// voice's tools off for it.
//
// Talking over the voice: OpenAI stops it at the first sound its turn detection takes for speech, a car horn
// included; on a WebRTC call it clears the voice's audio then even with interrupt_response off. So the box decides
// instead (bargeIn > 0; 0 leaves it to OpenAI). While the voice talks, turn detection is off and the box commits
// what the microphone heard every PIECE_MS, each piece transcribed on its own. Words heard
// cleanly stop the voice at once: a stop word ("wait", "stop", "no no", "ruko"), or two real words or more (not
// filler, in Latin or Devanagari letters, transcribed with confidence, not a known noise phrase, not the voice's own
// words coming back). Pieces with words in a row that aren't clean add up, and bargeIn seconds of them stop it too.
// What stopped the voice is answered once it's done; pieces that never came to anything (no words, filler, a horn
// heard as "thank you" or in another script) are taken out of the conversation so they're never answered. Every
// piece and what was decided goes to the log, to tune this by.
import WebSocket from 'ws';
import { filler } from './android.js';
import { Call, type CallHost, speakable } from './call.js';
import { codexAccessToken, mintRealtimeKey } from './codex-auth.js';

const API = 'https://api.openai.com/v1/realtime';

/** While the voice talks, what the microphone heard is committed and transcribed in pieces this long. */
const PIECE_MS = 1000;
/** After the voice, how long turn detection gets to notice the user still talking before an answer is asked for. */
const SETTLE_MS = 800;
/** Said on their own (repeated or not), these stop the voice at once. */
const STOP_WORDS = new Set(['wait', 'stop', 'hold on', 'hang on', 'one sec', 'one second', 'ruko', 'ruk', 'bas', 'ek minute',
  'ek min', 'no', 'nahi', 'रुको', 'रुक', 'बस', 'एक मिनट', 'नहीं']);
/** What transcription makes of noise and silence. */
const NOISE = new Set(['thank you', 'thanks', 'thanks for watching', 'thank you for watching', 'you', 'bye', 'bye bye',
  'subscribe', 'please subscribe', 'so', 'the']);
/** Real words: not filler, not a lone "yeah". */
const WEAK = new Set(['so', 'um', 'umm', 'uh', 'uhm', 'erm', 'er', 'hmm', 'hm', 'mm', 'mhm', 'ah', 'oh', 'like', 'well',
  'yeah', 'yes', 'okay', 'ok', 'right', 'haan', 'han', 'ha', 'accha', 'acha', 'hmm']);
/** Mean log probability of a transcript's tokens at or above which it counts as heard clearly. */
const CLEAR = -0.5;

/** A line's words: lowercase, without punctuation, a polite "please" or "Otto". */
function wordsOf(text: string): string[] {
  return text.toLowerCase().replace(/[^\p{L}\p{M}\p{N}\s]/gu, ' ').split(/\s+/).filter(w => w && w !== 'please' && w !== 'otto');
}

/** Whether a line is only a stop word, said once or a few times ("Stop.", "No, no, no.", "wait wait"). */
export function stopWord(text: string): boolean {
  const words = wordsOf(text);
  if (!words.length || words.length > 6) return false;
  const one = words.join(' ');
  if (STOP_WORDS.has(one)) return one !== 'no'; // a lone "no" may be an answer; "no no" isn't
  for (const n of [1, 2]) {
    const head = words.slice(0, n).join(' ');
    if (words.length % n === 0 && STOP_WORDS.has(head) &&
      words.every((w, i) => w === words[i % n])) return true;
  }
  return false;
}

/** Whether a piece heard over the voice is clean speech: two real words or more, in Latin or Devanagari letters,
 * not a known noise phrase, not the voice's own words coming back, transcribed with confidence (when known). */
export function clean(text: string, logprob: number | null, voice = ''): { ok: boolean; why: string } {
  const words = wordsOf(text);
  if (/[^\p{Script=Latin}\p{Script=Devanagari}\p{N}\p{P}\p{S}\p{Z}\p{M}\s]/u.test(text)) return { ok: false, why: 'other script' };
  if (NOISE.has(words.join(' '))) return { ok: false, why: 'noise phrase' };
  const real = words.filter(w => !WEAK.has(w));
  if (real.length < 2) return { ok: false, why: `${real.length} real words` };
  if (logprob !== null && logprob < CLEAR) return { ok: false, why: `unsure (${logprob.toFixed(2)})` };
  const said = ' ' + wordsOf(voice).join(' ') + ' ';
  for (let i = 0; i + 2 < words.length || (i === 0 && words.length === 2); i++) {
    if (said.includes(' ' + words.slice(i, i + 3).join(' ') + ' ')) return { ok: false, why: 'echo' };
  }
  return { ok: true, why: 'clean' };
}

export const PROMPT = `You are the voice of Otto, the user's personal assistant. You are only Otto's ears and mouth. Otto's brain is a separate agent on the user's computer: it has their files, phone, apps, accounts, notes, calendar, and every earlier conversation with the user. You have none of that; of earlier calls you have only what was said on them, at the start of this conversation, already dealt with: use it for follow-ups ("where were we?"), never answer it again.

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
  /** Seconds of the user's speech that stop the voice mid-sentence; 0 lets OpenAI stop it at any sound. */
  bargeIn?: number;
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
  /** Talking over the voice (bargeIn > 0): the box answers turns and decides when the voice stops. */
  private readonly barge: number;
  private readonly turns: Record<string, unknown>; // the turn detection the call starts with
  private deaf = false; // turn detection off while the voice talks; the box listens in pieces
  private pieceTimer: NodeJS.Timeout | null = null;
  private pieces = new Map<string, number>(); // pieces committed while the voice talks, by item: when
  private run: { since: number; last: number } | null = null; // pieces with words in a row
  private unsure: string[] = []; // pieces with words but not clean, kept only if the user is found to be talking
  private said = ''; // what the voice is saying now, to tell its echo from the user
  private tail = false; // the last piece, committed as turn detection comes back on
  private midTurn = false; // the user talked the voice down and is likely still talking: their turn's end answers
  private settleUntil = 0; // turn detection just came back on: a moment for it to hear the user still talking
  private answered = new Set<string>(); // user items an answer has been asked for
  private owed = false; // an answer is due once the voice is free
  private answering = false; // the answer just asked for, until it starts
  private item = ''; // the voice's answer being said
  private itemSince = 0; // when its audio started

  constructor(host: CallHost, private o: OpenAIOptions) {
    super(host);
    this.model = o.model || 'gpt-realtime-2.1';
    this.barge = Math.max(0, Number(o.bargeIn ?? 5)) || 0;
    // Semantic turn detection waits for a finished thought rather than any pause; it can be changed in the
    // channel's config (turnDetection). With bargeIn, OpenAI neither answers nor stops the voice by itself: the box
    // does both.
    this.turns = { ...(o.turnDetection ?? { type: 'semantic_vad', eagerness: 'auto' }),
      ...(this.barge ? { interrupt_response: false, create_response: false } : {}) };
  }

  /** The voice talks, or is about to. */
  private get talking() { return this.voiceSpeaking || this.responding; }

  async begin(body: any): Promise<Record<string, unknown>> {
    const sdp = body?.sdp;
    if (typeof sdp !== 'string' || !sdp.startsWith('v=')) throw new Error('no SDP offer');
    const session = {
      type: 'realtime', model: this.model, instructions: this.o.instructions || PROMPT,
      audio: {
        // Noise reduction for a phone on its loudspeaker; it can be changed in the channel's config (noiseReduction).
        input: {
          transcription: { model: 'gpt-4o-transcribe' },
          turn_detection: this.turns,
          noise_reduction: this.o.noiseReduction === undefined ? { type: 'far_field' } : this.o.noiseReduction,
        },
        output: { voice: this.o.voice || 'marin' },
      },
      tools: [TOOL], tool_choice: 'auto',
      include: ['item.input_audio_transcription.logprobs'],
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

  private event(e: any) {
    switch (e.type) {
      case 'input_audio_buffer.speech_started':
        this.userSpeaking = true;
        break;
      case 'input_audio_buffer.speech_stopped':
        this.userSpeaking = false;
        this.later(900); // the voice's own answer usually starts in this time; Otto's words wait for it
        break;
      case 'input_audio_buffer.committed':
        if (!this.barge) break;
        // A piece heard while the voice talks: decided once its words are in. Otherwise a turn, answered now.
        if (this.deaf || this.tail) { this.tail = false; this.pieces.set(e.item_id, Date.now()); }
        else {
          this.midTurn = false;
          if (!this.answered.has(e.item_id)) this.answer();
        }
        break;
      case 'conversation.item.added': case 'conversation.item.created':
        if (e.item?.type === 'message' && e.item.role === 'user' && e.item.content?.some((c: any) => c.type === 'input_audio')) {
          this.transcript.hold(e.item.id, 'user');
        }
        break;
      case 'conversation.item.input_audio_transcription.completed': {
        const lp: number[] = (e.logprobs ?? []).map((t: any) => Number(t.logprob)).filter((n: number) => Number.isFinite(n));
        this.heardOver(e.item_id, String(e.transcript ?? ''), lp.length ? lp.reduce((a, b) => a + b, 0) / lp.length : null);
        break;
      }
        break;
      case 'conversation.item.input_audio_transcription.failed':
        this.heardOver(e.item_id, '', null);
        break;
      case 'response.created':
        this.responding = true;
        this.answering = false;
        this.asked = null;
        if (this.barge) this.deafen(true);
        break;
      case 'response.output_item.added':
        if (e.item?.type === 'message') { this.item = e.item.id; this.itemSince = 0; this.said = ''; }
        break;
      case 'response.output_audio_transcript.delta':
        this.said += String(e.delta ?? '');
        break;
      case 'response.output_audio_transcript.done':
        this.transcript.add(this.relaying ? 'otto' : 'voice', String(e.transcript ?? ''));
        break;
      case 'output_audio_buffer.started':
        this.voiceSpeaking = true;
        if (!this.itemSince) this.itemSince = Date.now();
        break;
      case 'output_audio_buffer.stopped': case 'output_audio_buffer.cleared':
        this.voiceSpeaking = false;
        this.free();
        break;
      case 'response.done':
        this.responding = false;
        this.relaying = false;
        this.count(e.response?.usage);
        void this.tools((e.response?.output ?? []).filter((i: any) => i.type === 'function_call'));
        this.free();
        break;
      case 'error':
        if (e.error?.code === 'input_audio_buffer_commit_empty') { this.tail = false; break; } // a piece with no audio
        this.log(`OpenAI: ${e.error?.code ?? ''} ${redact(String(e.error?.message ?? '')).slice(0, 200)}`);
        if (this.answering && e.error?.code === 'conversation_already_has_active_response') {
          // An answer was asked for just as another began: it's asked for again once that one is done.
          this.answering = false;
          this.owed = true;
          this.responding = true;
        }
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
    this.responding = true;
    this.send({ type: 'response.create' });
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

  /** Muting mid-sentence sends no more audio, so the voice would never hear the pause that ends the sentence: what
   * was said so far is taken as the user's turn now, and answered (or handed to Otto) like any other. */
  mute(on: boolean) {
    super.mute(on);
    if (!on || !this.userSpeaking) return;
    this.userSpeaking = false;
    this.send({ type: 'input_audio_buffer.commit' });
    if (!this.barge && !this.responding) this.send({ type: 'response.create' });
    this.log('muted mid-sentence: taken as said');
    this.update();
  }

  /** Stops the voice now: what it had yet to say is cut from its answer, so it knows where it was stopped. */
  interrupt(why = 'interrupted') {
    if (!this.talking) return;
    this.send({ type: 'response.cancel' });
    this.send({ type: 'output_audio_buffer.clear' });
    if (this.item && this.voiceSpeaking && this.itemSince) {
      this.send({ type: 'conversation.item.truncate', item_id: this.item, content_index: 0, audio_end_ms: Math.max(0, Date.now() - this.itemSince) });
    }
    this.log(why);
  }

  /** A piece's words are in, or a turn's. A piece heard while the voice talks: a stop word, or clean speech, stops
   * it at once; other pieces with words in a row stop it once they add up to bargeIn seconds; what stopped it is
   * answered once it's done. Pieces that never come to that are dropped. */
  private heardOver(id: string, text: string, logprob: number | null) {
    const at = this.pieces.get(id);
    this.pieces.delete(id);
    if (!this.barge || at === undefined || this.answered.has(id)) return this.transcript.fill(id, text);
    const words = text.trim() && !filler(text) ? wordsOf(text).length : 0;
    const note = (what: string) => this.log(`over: "${text.trim().slice(0, 60)}" ${words} words` +
      `${logprob === null ? '' : `, lp ${logprob.toFixed(2)}`}${this.talking ? '' : ', voice done'} -> ${what}`);
    if (!words) {
      note('noise: dropped');
      this.drop(id);
      return this.free();
    }
    if (stopWord(text)) {
      note('stop word: stopped');
      this.transcript.fill(id, text);
      this.answered.add(id); // stopping is the answer
      for (const u of this.unsure.splice(0)) this.drop(u);
      this.run = null;
      if (this.talking) this.interrupt('stop word: stopped');
      return;
    }
    const c = clean(text, logprob, this.said);
    if (c.why === 'other script' || c.why === 'noise phrase') {
      note(`${c.why}: dropped`);
      this.drop(id);
      return this.free();
    }
    this.transcript.fill(id, text);
    const follows = this.run && at - this.run.last <= PIECE_MS * 1.6;
    if (follows) this.run!.last = at; else this.run = { since: at - PIECE_MS, last: at };
    const long = this.run!.last - this.run!.since >= this.barge * 1000;
    if (!c.ok && !long) {
      note(`${c.why}: held`);
      this.unsure.push(id);
      return this.free();
    }
    // The user is talking: everything they said over the voice is theirs, and answered once the voice stops.
    this.unsure = [];
    this.owed = true;
    if (this.talking && !this.midTurn) {
      note(c.ok ? 'clean: stopped' : `talked over for ${this.barge} s: stopped`);
      this.run = null;
      this.midTurn = true;
      this.interrupt(c.ok ? 'clean speech: stopped' : `talked over for ${this.barge} s: stopped`);
    } else {
      note(this.talking ? 'kept, stopping already' : 'kept, answered next');
      this.free();
    }
  }

  /** A piece that's only noise: out of the transcript and the conversation. */
  private drop(id: string) {
    this.transcript.fill(id, '');
    this.send({ type: 'conversation.item.delete', item_id: id });
  }

  /** Asks the voice to answer what the user has said, now or once it's free. */
  private answer() {
    if (this.talking || this.answering) { this.owed = true; return; }
    this.owed = false;
    this.answering = true;
    this.responding = true;
    for (const id of this.pieces.keys()) this.answered.add(id);
    this.send({ type: 'response.create' });
  }

  /** The voice may be free: turn detection back on, an answer that's owed (after a moment, in case the user is
   * still talking), then Otto's words. */
  private free() {
    if (this.talking || this.answering) return;
    if (this.deaf) {
      this.deafen(false);
      setTimeout(() => this.free(), SETTLE_MS + 50);
      return;
    }
    if (Date.now() < this.settleUntil) return; // free() comes again after it
    if (this.midTurn) {
      if (this.userSpeaking) return; // the end of their turn answers it all
      this.midTurn = false;
    }
    if (!this.pieces.size) for (const u of this.unsure.splice(0)) this.drop(u); // nothing came of them
    if (this.owed && !this.userSpeaking && !this.pieces.size) return this.answer();
    this.later(300);
  }

  /** While the voice talks, OpenAI's turn detection is off (it would stop the voice at any sound) and the box
   * commits what the microphone heard in pieces; after, it's back on, with what's left committed as a last piece. */
  private deafen(on: boolean) {
    if (on === this.deaf) return;
    this.deaf = on;
    if (this.pieceTimer) clearInterval(this.pieceTimer);
    this.pieceTimer = null;
    if (on) {
      this.run = null;
      this.send({ type: 'session.update', session: { type: 'realtime', audio: { input: { turn_detection: null } } } });
      this.pieceTimer = setInterval(() => { if (!this.mutedSince) this.send({ type: 'input_audio_buffer.commit' }); }, PIECE_MS);
      this.pieceTimer.unref(); // the call's end clears it; a test or a closing process needn't wait for it
    } else {
      this.settleUntil = Date.now() + SETTLE_MS;
      if (!this.mutedSince) { this.tail = true; this.send({ type: 'input_audio_buffer.commit' }); }
      this.send({ type: 'session.update', session: { type: 'realtime', audio: { input: { turn_detection: this.turns } } } });
    }
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
    if (this.userSpeaking || this.responding || this.voiceSpeaking || this.answering || this.owed) return; // the events that end these look again
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
    if (this.pieceTimer) clearInterval(this.pieceTimer);
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
