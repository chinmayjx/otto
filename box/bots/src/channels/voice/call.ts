// One voice call from the phone, whatever does the listening and speaking (openai.ts, android.ts): its transcript,
// its state for the phone's pill, and the events the phone reads by long poll.
//
// Events, each with a number n:
//   {type: "state", state}    connecting, listening, thinking (Otto is working on something), speaking, error, ended
//   {type: "speak", text}     android: words for the phone to say
//   {type: "error", message}
// Transcripts never go to the log: states, timings and sizes only.
import crypto from 'node:crypto';
import type { History } from './history.js';
import { Transcript } from './transcript.js';

export type State = 'connecting' | 'listening' | 'thinking' | 'speaking' | 'error' | 'ended';
export interface CallEvent { n: number; type: string; [k: string]: unknown }

const KEEP_EVENTS = 200;
/** How long a typing ping from the bot means it's still working (it pings every 4.5 s while a turn runs). */
const BUSY_MS = 6500;

export interface CallHost {
  /** A bundle for the agent, as a message on the voice channel; `record` for one that needs no reply. `delivered`
   * runs once it has been written to the agent. */
  toAgent(text: string, record?: boolean, delivered?: () => void): void;
  log(m: string): void;
  /** Where the transcript is kept on disk, if anywhere. */
  history?: History;
}

export abstract class Call {
  readonly id = crypto.randomBytes(9).toString('base64url');
  readonly created = Date.now();
  /** When the phone last asked for events: a call nobody watches is ended (server.ts). */
  seen = Date.now();
  state: State = 'connecting';
  readonly transcript: Transcript;
  /** What the implementation reports: the user talking, the voice talking. */
  protected userSpeaking = false;
  protected voiceSpeaking = false;
  protected up = false;
  private busyUntil = 0;
  private busyTimer: NodeJS.Timeout | null = null;
  private events: CallEvent[] = [];
  private n = 0;
  private waiters = new Set<() => void>();
  protected stopping: Promise<void> | null = null;

  constructor(protected host: CallHost) {
    this.transcript = new Transcript(host.history);
  }

  /** Which implementation, for the phone: "webrtc" (the phone makes a WebRTC call) or "android" (its own speech). */
  abstract readonly mode: string;
  /** Starts the call with what the phone sent; resolves to what the phone needs back. */
  abstract begin(body: any): Promise<Record<string, unknown>>;
  /** Something Otto says to the user: spoken as soon as there's a gap. */
  abstract say(text: string): void;
  /** Lets go of the call; `undelivered` is whatever Otto said that was never spoken. */
  protected abstract end(): Promise<string[]>;
  /** The user wants the voice to stop talking (a tap on the pill). */
  interrupt(): void {}
  /** The phone heard the user say something (android). */
  heard(_text: string): void { throw new Error('this call hears for itself'); }

  protected log(m: string) { this.host.log(`voice [${this.id}] ${m}`); }

  protected push(e: { type: string; [k: string]: unknown }) {
    this.events.push({ n: ++this.n, ...e });
    if (this.events.length > KEEP_EVENTS) this.events.splice(0, this.events.length - KEEP_EVENTS);
    for (const w of this.waiters) w();
    this.waiters.clear();
  }

  /** Otto's turn is running (the bot's typing ping). */
  working() {
    this.busyUntil = Date.now() + BUSY_MS;
    if (this.busyTimer) clearTimeout(this.busyTimer);
    this.busyTimer = setTimeout(() => this.update(), BUSY_MS + 50);
    this.update();
  }

  /** Works out the state from what's going on, and tells the phone when it changes. */
  protected update() {
    if (this.state === 'ended' || this.state === 'error') return;
    const next: State = !this.up ? 'connecting'
      : this.voiceSpeaking ? 'speaking'
      : this.userSpeaking ? 'listening'
      : Date.now() < this.busyUntil ? 'thinking'
      : 'listening';
    this.set(next);
  }

  protected set(state: State) {
    if (state === this.state || this.state === 'ended') return;
    this.log(`${this.state} -> ${state}`);
    this.state = state;
    this.push({ type: 'state', state });
  }

  protected fail(message: string) {
    if (this.state === 'ended') return;
    this.log(`error: ${message}`);
    this.push({ type: 'error', message });
    this.set('error');
  }

  /** The events after [after], at once if there are any, else when one comes or after [waitMs]. */
  async poll(after: number, waitMs: number) {
    this.seen = Date.now();
    if (!this.events.some(e => e.n > after) && this.state !== 'ended') {
      await new Promise<void>(resolve => {
        const done = () => { clearTimeout(t); resolve(); };
        const t = setTimeout(done, waitMs);
        this.waiters.add(done);
      });
    }
    return { state: this.state, events: this.events.filter(e => e.n > after) };
  }

  /** Ends the call. Whatever the user said that Otto never saw goes to it for the record (no turn of its own), and
   * whatever Otto said that was never spoken comes back, for a notification. */
  stop(reason: string): Promise<string[]> {
    if (this.stopping) return this.stopping.then(() => []);
    let undelivered: string[] = [];
    this.stopping = (async () => {
      this.log(`ending (${reason}) after ${Math.round((Date.now() - this.created) / 1000)} s`);
      if (this.busyTimer) clearTimeout(this.busyTimer);
      undelivered = await this.end().catch(e => { this.log(`end: ${e.message}`); return []; });
      // The voice's own last words alone aren't worth a record: they're settled as they are.
      const unheard = this.transcript.unheard, left = this.transcript.take();
      if (unheard) {
        this.host.toAgent(['Said on a voice call that has now ended (for the record; it needs no reply):', ...left.lines].join('\n'), true, left.delivered);
      } else left.delivered();
      this.set('ended');
    })();
    return this.stopping.then(() => undelivered);
  }
}

/** Markdown the agent may still write, made speakable: no marks, links as their words. */
export function speakable(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/https?:\/\/\S+/g, 'the link')
    .replace(/[*~`#>]+/g, '')
    .replace(/^\s*[-•]\s+/gm, '')
    .replace(/[ \t]+/g, ' ')
    .trim();
}
