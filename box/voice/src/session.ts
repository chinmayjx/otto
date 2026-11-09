// A voice session: one call from the phone, with a Codex app-server of its own and a fresh thread.
//
// The audio never comes here. The phone makes a WebRTC offer (its microphone, and a data channel); Codex passes it
// to OpenAI's realtime service and hands back the answer (thread/realtime/sdp), and from then on the audio goes
// straight between the phone and OpenAI. Codex keeps a side connection to the same call for its events, which is
// what this session watches.
//
// The realtime voice talks, but never hands work to Codex on its own: each time the user finishes saying something
// (thread/realtime/transcript/done, role user), the session gives the words to the thread as a turn (turn/start), or
// into the running turn (turn/steer). Codex's answer goes back to the voice by itself, which says it.
//
// What the phone sees is a list of small events, read by long poll (main.ts):
//   {type: "state", state, working}   state: connecting, listening, speaking, error, ended;
//                                     working: a Codex turn is running
//   {type: "caption", role, text}     a finished utterance (user or assistant), for the phone to show
//   {type: "activity", text}          what Codex is doing, in a few words ("Running a command")
//   {type: "error", message}
// Transcripts are sent to the phone, never logged: the log has states, timings and sizes.
import crypto from 'node:crypto';
import { AppServer, type Notification } from './appserver.js';

export type State = 'connecting' | 'listening' | 'speaking' | 'error' | 'ended';
export interface VoiceEvent { n: number; type: string; [k: string]: unknown }

const KEEP_EVENTS = 200;
const SDP_TIMEOUT_MS = 40_000;

export interface Options {
  cwd: string;
  /** realtime/start extras (voice, model …), from the config. */
  realtime?: Record<string, unknown>;
  log: (m: string) => void;
}

export class Session {
  readonly id = crypto.randomBytes(9).toString('base64url');
  readonly created = Date.now();
  state: State = 'connecting';
  working = false;
  /** When the phone last asked for events: a session nobody watches is ended (main.ts). */
  seen = Date.now();
  private events: VoiceEvent[] = [];
  private n = 0;
  private waiters = new Set<() => void>();
  private server: AppServer | null = null;
  private thread = '';
  private turn: string | null = null;
  private stopping: Promise<void> | null = null;
  /** How many of each notification came: names only, for the log at the end. */
  private seenMethods: Record<string, number> = {};

  constructor(private opts: Options) {}

  private log(m: string) { this.opts.log(`[${this.id}] ${m}`) }

  private push(e: { type: string; [k: string]: unknown }) {
    this.events.push({ n: ++this.n, ...e });
    if (this.events.length > KEEP_EVENTS) this.events.splice(0, this.events.length - KEEP_EVENTS);
    for (const w of this.waiters) w();
    this.waiters.clear();
  }

  private set(state: State, working = this.working) {
    if (state === this.state && working === this.working) return;
    if (this.state === 'ended') return;
    if (state !== this.state) this.log(`${this.state} -> ${state}`);
    this.state = state;
    this.working = working;
    this.push({ type: 'state', state, working });
  }

  /** The events after [after], at once if there are any, else when one comes or after [waitMs]. */
  async poll(after: number, waitMs: number) {
    this.seen = Date.now();
    if (!this.events.some(e => e.n > after) && this.state !== 'ended') {
      await new Promise<void>(resolve => {
        const t = setTimeout(done, waitMs);
        function done() { clearTimeout(t); resolve() }
        this.waiters.add(done);
      });
      this.seen = Date.now();
    }
    return { state: this.state, working: this.working, events: this.events.filter(e => e.n > after) };
  }

  /** Starts Codex, a thread and the realtime call with the phone's offer; resolves with the answer. */
  async start(offer: string): Promise<string> {
    const t0 = Date.now();
    this.push({ type: 'state', state: 'connecting', working: false });
    const server = new AppServer();
    this.server = server;
    server.on('exit', (why: string) => {
      if (this.stopping) return;
      this.log(why);
      this.fail(why);
    });
    server.on('notification', (m: Notification) => this.on(m));
    try {
      await server.init();
      const account = await server.request('account/read', {});
      this.log(`codex ${server.pid}, account ${account?.account?.type ?? account?.type ?? '?'}`);
      const th = await server.request('thread/start', { cwd: this.opts.cwd });
      this.thread = th.thread.id;
      this.log(`thread ${this.thread} in ${this.opts.cwd}`);
      const answer = new Promise<string>((resolve, reject) => {
        const t = setTimeout(() => reject(new Error(`no answer to the offer in ${SDP_TIMEOUT_MS / 1000} s`)), SDP_TIMEOUT_MS);
        const on = (m: Notification) => {
          if (m.method === 'thread/realtime/sdp') done(() => resolve(m.params.sdp));
          else if (m.method === 'thread/realtime/error') done(() => reject(new Error(m.params.message ?? 'realtime error')));
          else if (m.method === 'thread/realtime/closed') done(() => reject(new Error(`closed: ${m.params.reason ?? '?'}`)));
        };
        const gone = (why: string) => done(() => reject(new Error(why)));
        const done = (f: () => void) => {
          clearTimeout(t);
          server.off('notification', on);
          server.off('exit', gone);
          f();
        };
        server.on('notification', on);
        server.on('exit', gone);
      });
      // Only v3 over WebRTC works with a ChatGPT sign-in; the others want an API key.
      await server.request('thread/realtime/start', {
        ...this.opts.realtime,
        threadId: this.thread,
        outputModality: 'audio',
        version: 'v3',
        transport: { type: 'webrtc', sdp: offer },
      });
      const sdp = await answer;
      this.log(`answer in ${Date.now() - t0} ms`);
      return sdp;
    } catch (e: any) {
      this.log(`start failed: ${e.message}`);
      this.fail(e.message);
      throw e;
    }
  }

  private on({ method, params }: Notification) {
    this.seenMethods[method] = (this.seenMethods[method] ?? 0) + 1;
    switch (method) {
      case 'thread/realtime/started':
        this.log('realtime started');
        return this.set('listening');
      case 'thread/realtime/transcript/delta':
        if (params.role === 'assistant' && this.state === 'listening') this.set('speaking');
        return;
      case 'thread/realtime/transcript/done': {
        const text = String(params.text ?? '').trim();
        if (!text) return;
        this.push({ type: 'caption', role: params.role, text });
        if (params.role === 'assistant') {
          if (this.state === 'speaking') this.set('listening');
        } else if (params.role === 'user') {
          this.log(`user said ${text.length} chars`);
          this.handOff(text).catch(e => this.log(`hand-off: ${e.message}`));
        }
        return;
      }
      case 'turn/started':
        this.turn = params.turn?.id ?? this.turn;
        this.log(`turn ${this.turn} started`);
        return this.set(this.state, true);
      case 'turn/completed':
        this.log(`turn ${params.turn?.id ?? ''} ${params.turn?.status ?? 'completed'}`);
        this.turn = null;
        return this.set(this.state, false);
      case 'item/started': {
        const what = ACTIVITY[params.item?.type];
        if (what) this.push({ type: 'activity', text: what });
        return;
      }
      case 'thread/realtime/error':
        this.log(`realtime error: ${params.message}`);
        return this.fail(params.message ?? 'realtime error');
      case 'thread/realtime/closed':
        this.log(`realtime closed: ${params.reason ?? ''}`);
        void this.stop('closed');
        return;
    }
  }

  /** The user's words to Codex: into the running turn if there is one, else as a new turn. */
  private async handOff(text: string) {
    const server = this.server;
    if (!server || this.state === 'ended') return;
    const input = [{ type: 'text', text }];
    if (this.turn) {
      try {
        await server.request('turn/steer', { threadId: this.thread, expectedTurnId: this.turn, input });
        this.log(`steered turn ${this.turn}`);
        return;
      } catch (e: any) {
        this.log(`steer failed (${e.message}), starting a turn`);
      }
    }
    const r = await server.request('turn/start', { threadId: this.thread, input });
    this.turn = r?.turn?.id ?? this.turn;
    this.set(this.state, true);
  }

  private fail(message: string) {
    if (this.state === 'ended' || this.state === 'error') return;
    this.push({ type: 'error', message });
    this.set('error', false);
    void this.stop('error');
  }

  /** Ends the call and Codex with it. Safe to call more than once. */
  stop(why = 'stopped'): Promise<void> {
    if (this.stopping) return this.stopping;
    this.stopping = (async () => {
      const server = this.server;
      if (server && this.thread && this.state !== 'error') {
        await server.request('thread/realtime/stop', { threadId: this.thread }, 3000).catch(() => {});
      }
      await server?.kill();
      const was = this.state;
      this.set('ended', false);
      this.log(`ended (${why}${was === 'error' ? ', after an error' : ''}) after ${Math.round((Date.now() - this.created) / 1000)} s`);
      this.log(`notifications: ${JSON.stringify(this.seenMethods)}`);
    })();
    return this.stopping;
  }
}

const ACTIVITY: Record<string, string> = {
  commandExecution: 'Running a command',
  fileChange: 'Editing files',
  mcpToolCall: 'Using a tool',
  webSearch: 'Searching the web',
};
