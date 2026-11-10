// Otto's voice as a bot's channel (kind "otto_voice"): calls from the Otto app on the phone, and phone notifications
// when Otto says something with no call on. One bot on the box has it: that bot is the user's assistant.
//
// The channel's config picks what does the listening and speaking:
//   {"provider": "openai-realtime", "apiKey": "sk-…", "model"?: "gpt-realtime-2.1", "voice"?: "marin", "bargeIn"?: 5,
//    "language"?: "en"}
//   {"provider": "openai-realtime", "auth": "codex", …}  the Codex ChatGPT sign-in; a realtime key is minted per call
//   {"provider": "android"}       the phone's own speech recognizer and text-to-speech
//
// The phone reaches it through the relay's box API, which passes /voice/… on to the voice server (server.ts).
import type { Channel, Id, Incoming } from '../../channel.js';
import type { ChannelConfig } from '../../config.js';
import { AndroidCall } from './android.js';
import type { Call } from './call.js';
import type { History } from './history.js';
import { OpenAICall } from './openai.js';

export const PROVIDERS = ['openai-realtime', 'android'];
export const AUTHS = ['api-key', 'codex'];

const FORMATTING = [
  'A voice call with you from the Otto app on their phone; when no call is on, what you send shows as a phone',
  'notification instead. What you send here is read aloud, so write for the ear: short plain sentences, no Markdown,',
  'lists, tables, links, code, file paths or long numbers; say numbers, dates and times the way a person would. On a',
  'call with a realtime voice, the voice handles small talk itself and passes the rest to you, with what was said on',
  'the call since your last message from it. When something has to be seen (a table, a link, a file), send it on',
  'another channel or publish an artifact, and say so here in a sentence.',
].join(' ');

/** Whether to acknowledge first, by what's on the call. Unlike other channels, a realtime voice already tells the user
 * it's on it; the phone's own speech has nothing in between. */
const ACK: Record<string, string> = {
  'openai-realtime': 'The voice has already told the user you\'re on it, so don\'t acknowledge or send a heads-up here: '
    + 'send only the answer, with a short progress note only if the work runs past about 20 seconds.',
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
    const host = {
      toAgent: (text: string, record = false, delivered?: () => void) =>
        this.onMessage?.({ chat: 'user', from: 'user', text, attachments: [], record, delivered }),
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
        language: typeof this.config.language === 'string' ? this.config.language : undefined,
        bargeIn: this.config.bargeIn === undefined ? undefined : this.config.bargeIn === 'off' ? 'off' : Number(this.config.bargeIn),
      });
    this.call = call;
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
    const left = await call.stop(reason, message);
    for (const text of left) await this.deps.notify(text).catch(e => this.deps.log(`voice: a notification failed: ${e.message}`));
  }

  private live(): Call | null {
    const c = this.call;
    return c && c.state !== 'ended' && c.state !== 'error' ? c : null;
  }

  async send(_chat: Id, text: string) {
    const call = this.live();
    if (call) return call.say(text);
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
