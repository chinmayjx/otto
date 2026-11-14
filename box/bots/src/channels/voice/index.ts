// Otto's voice as a bot's channel (kind "otto_voice"): calls from the Otto app on the phone, and phone notifications
// when Otto says something with no call on. One bot on the box has it: that bot is the user's assistant.
//
// The channel's config picks what does the listening and speaking:
//   {"provider": "openai-realtime", "apiKey": "sk-…", "model"?: "gpt-realtime-2.1", "voice"?: "marin",
//    "turnDetection"?: {…}, "noiseReduction"?: {…}, "transcription"?: {…}}   OpenAI's own settings, passed as they are
//   {"provider": "openai-realtime", "auth": "codex", …}  the Codex ChatGPT sign-in; a realtime key is minted per call
//   {"provider": "android"}       the phone's own speech recognizer and text-to-speech
//
// The phone reaches it through the relay's box API, which passes /voice/… on to the voice server (server.ts).
import { type Channel, type Id, type Incoming, Stale } from '../../channel.js';
import type { ChannelConfig } from '../../config.js';
import { AndroidCall } from './android.js';
import type { Call } from './call.js';
import type { History } from './history.js';
import { OpenAICall } from './openai.js';
import { show, worth } from './transcript.js';

export const PROVIDERS = ['openai-realtime', 'android'];
export const AUTHS = ['api-key', 'codex'];

const FORMATTING = [
  'A voice call with you from the Otto app on their phone; when no call is on, what you send shows as a phone',
  'notification instead. What you send here is read aloud, so write for the ear: short plain sentences, no Markdown,',
  'lists, tables, links, code, file paths or long numbers; say numbers, dates and times the way a person would. On a',
  'call with a realtime voice, the voice handles small talk itself and passes the rest to you, with what was said on',
  'the call since your last message from it. While you work, what\'s said on the call meanwhile comes in as "On the',
  'call just now": context, not a request, so reply to it only if it changes what you\'re doing or saying. A reply here',
  'can come back not sent because the call has moved on since you wrote it: you get the newer lines with it; take them',
  'into account and send your reply again, or nothing if the call already covered it. When something has to be seen',
  '(a table, a link, a file), send it on another channel or publish an artifact, and say so here in a sentence.',
].join(' ');

/** How long a call's new lines gather before they go into the agent's turn, as one block. */
const STEER_MS = 1000;
/** How many replies in a row can be held back for newer lines on the call. Past that one is said anyway, so a user
 * who keeps talking can't keep the agent rewriting forever. */
const MAX_HELD = 2;

/** Whether to acknowledge first, by what's on the call. Unlike other channels, a realtime voice already tells the user
 * it's on it; the phone's own speech has nothing in between. */
const ACK: Record<string, string> = {
  'openai-realtime': 'The user already knows you\'re on it (the voice acknowledged it, and the call shows you working), '
    + 'so don\'t acknowledge or send a heads-up here: send only the answer, with a short progress note only if the work '
    + 'runs past about 20 seconds.',
  android: 'The user\'s words come straight through, with no voice in between: if the answer will take more than a few '
    + 'seconds, first send a short acknowledgement here.',
};

export interface VoiceDeps {
  /** A phone notification; throws when the phone can't be reached. */
  notify(text: string): Promise<void>;
  log(m: string): void;
  /** Where calls' transcripts are kept (the bot's state folder). */
  history?: History;
}

export class Voice implements Channel {
  readonly kind = 'otto_voice';
  readonly label = 'Otto voice';
  readonly formatting: string;
  readonly owned = false;
  readonly handle = null;
  call: Call | null = null;
  private onMessage: ((m: Incoming) => void) | null = null;
  private steerTimer: NodeJS.Timeout | null = null;
  private held = 0; // replies held back in a row

  constructor(readonly config: ChannelConfig, private deps: VoiceDeps) {
    const p = config.provider ?? 'openai-realtime';
    if (!PROVIDERS.includes(String(p))) throw new Error(`unknown voice provider "${p}" (known: ${PROVIDERS.join(', ')})`);
    this.formatting = `${FORMATTING} ${ACK[String(p)]}`;
    if (p === 'openai-realtime') {
      const auth = String(config.auth ?? 'api-key');
      if (!AUTHS.includes(auth)) throw new Error(`unknown voice auth "${auth}" (known: ${AUTHS.join(', ')})`);
      if (auth === 'api-key' && typeof config.apiKey !== 'string') throw new Error('openai-realtime needs an apiKey, or "auth": "codex"');
    }
  }

  get provider(): string { return String(this.config.provider ?? 'openai-realtime'); }

  async start(onMessage: (m: Incoming) => void) { this.onMessage = onMessage; }

  stop() {
    void this.hangUp('bot stopped');
    this.onMessage = null;
  }

  pairLink() { return null; }
  async setCommands() {}

  /** A new call from the phone, ending the one before; resolves to what the phone needs to go on. */
  async open(body: any): Promise<Record<string, unknown>> {
    await this.hangUp('replaced');
    this.held = 0;
    const host = {
      toAgent: (text: string, record = false, delivered?: () => void) => {
        if (!record) this.held = 0; // a new request: its reply gets its own chances
        this.onMessage?.({ chat: 'user', from: 'user', text, attachments: [], record, delivered });
      },
      log: this.deps.log,
      history: this.deps.history,
    };
    const call: Call = this.provider === 'android'
      ? new AndroidCall(host)
      : new OpenAICall(host, {
        auth: this.config.auth === 'codex' ? 'codex' : 'api-key',
        apiKey: this.config.auth === 'codex' ? undefined : String(this.config.apiKey),
        model: this.config.model as string | undefined,
        voice: this.config.voice as string | undefined, instructions: this.config.instructions as string | undefined,
        turnDetection: this.config.turnDetection as Record<string, unknown> | undefined,
        noiseReduction: this.config.noiseReduction as Record<string, unknown> | null | undefined,
        transcription: this.config.transcription as Record<string, unknown> | undefined,
      });
    this.call = call;
    call.transcript.onLine = () => this.steerSoon(call);
    try {
      const r = await call.begin(body);
      return { id: call.id, mode: call.mode, ...r };
    } catch (e) {
      if (this.call === call) this.call = null;
      await call.stop('failed to start');
      throw e;
    }
  }

  /** Ends the call, if there is one; what Otto said that was never spoken goes to a notification. `message` tells the
   * phone why, when the box is the one hanging up. */
  async hangUp(reason: string, message?: string) {
    const call = this.call;
    if (!call) return;
    this.call = null;
    if (this.steerTimer) clearTimeout(this.steerTimer);
    this.steerTimer = null;
    const left = await call.stop(reason, message);
    for (const text of left) await this.deps.notify(text).catch(e => this.deps.log(`voice: a notification failed: ${e.message}`));
  }

  private live(): Call | null {
    const c = this.call;
    return c && c.state !== 'ended' && c.state !== 'error' ? c : null;
  }

  /** The call's new lines, a moment after they're said (gathering what comes within STEER_MS), go into the agent's
   * turn while one runs, so its reply is written with the call in view. With the agent idle the core drops them, and
   * they go with the call's next hand-off as before. Below the noise threshold (transcript.ts) they wait too. */
  private steerSoon(call: Call) {
    if (this.steerTimer) return;
    this.steerTimer = setTimeout(() => {
      this.steerTimer = null;
      if (call !== this.live()) return;
      const lines = call.transcript.unseen();
      if (!worth(lines)) return;
      this.onMessage?.({ chat: 'user', from: 'user', text: ['On the call just now:', ...show(lines)].join('\n'), attachments: [],
        steer: true, delivered: () => call.transcript.take(lines).delivered() });
    }, STEER_MS);
  }

  /** The safety net for the moment between a line being said and it reaching the agent: a reply that arrives with
   * lines past the threshold still unseen isn't said; the agent gets them instead, marked seen, and writes again. */
  private async catchUp(call: Call) {
    await call.transcript.settled(1500);
    const lines = call.transcript.unseen();
    if (!worth(lines)) return;
    if (this.held >= MAX_HELD) return this.deps.log(`voice: said a reply with ${lines.length} newer lines unseen (held ${MAX_HELD} in a row already)`);
    this.held++;
    call.transcript.take(lines).delivered();
    this.deps.log(`voice: held back a reply for ${lines.length} newer lines on the call (${this.held} in a row)`);
    throw new Stale(['Not said: the call has moved on since you wrote this. Newer on the call:', ...show(lines),
      'Take it into account and send your reply again, or send nothing if the call already covered it.'].join('\n'));
  }

  async send(_chat: Id, text: string, o?: { reply?: boolean }) {
    let call = this.live();
    if (call && o?.reply) {
      await this.catchUp(call);
      call = this.live(); // it may have ended meanwhile: then it's a notification
    }
    if (call) {
      this.held = 0;
      return call.say(text);
    }
    try {
      await this.deps.notify(text);
    } catch (e: any) {
      throw new Error(`not delivered: no call is on and the phone can't be reached (${e.message}). Use another channel.`);
    }
  }

  async sendFile() {
    throw new Error('a voice can\'t show files: send it on another channel (or publish an artifact) and say so here');
  }

  async typing() { this.live()?.working(); }
}
