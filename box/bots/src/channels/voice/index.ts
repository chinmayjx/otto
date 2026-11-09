// Otto's voice as a bot's channel (kind "otto_voice"): calls from the Otto app on the phone, and phone notifications
// when Otto says something with no call on. One bot on the box has it: that bot is the user's assistant.
//
// The channel's config picks what does the listening and speaking:
//   {"provider": "openai-realtime", "apiKey": "sk-…", "model"?: "gpt-realtime-2.1-mini", "voice"?: "marin"}
//   {"provider": "android"}       the phone's own speech recognizer and text-to-speech
//
// The phone reaches it through the relay's box API, which passes /voice/… on to the voice server (server.ts).
import type { Channel, Id, Incoming } from '../../channel.js';
import type { ChannelConfig } from '../../config.js';
import { AndroidCall } from './android.js';
import type { Call } from './call.js';
import { OpenAICall } from './openai.js';

export const PROVIDERS = ['openai-realtime', 'android'];

const FORMATTING = [
  'A voice call with you from the Otto app on their phone; when no call is on, what you send shows as a phone',
  'notification instead. What you send here is read aloud, so write for the ear: short plain sentences, no Markdown,',
  'lists, tables, links, code, file paths or long numbers; say numbers, dates and times the way a person would. On a',
  'call with a realtime voice, the voice handles small talk itself and passes the rest to you, with what was said on',
  'the call since your last message from it. When something has to be seen (a table, a link, a file), send it on',
  'another channel or publish an artifact, and say so here in a sentence.',
].join(' ');

export interface VoiceDeps {
  /** A phone notification; throws when the phone can't be reached. */
  notify(text: string): Promise<void>;
  log(m: string): void;
}

export class Voice implements Channel {
  readonly kind = 'otto_voice';
  readonly label = 'Otto voice';
  readonly formatting = FORMATTING;
  readonly owned = false;
  readonly handle = null;
  call: Call | null = null;
  private onMessage: ((m: Incoming) => void) | null = null;

  constructor(readonly config: ChannelConfig, private deps: VoiceDeps) {
    const p = config.provider ?? 'openai-realtime';
    if (!PROVIDERS.includes(String(p))) throw new Error(`unknown voice provider "${p}" (known: ${PROVIDERS.join(', ')})`);
    if (p === 'openai-realtime' && typeof config.apiKey !== 'string') throw new Error('openai-realtime needs an apiKey');
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
      toAgent: (text: string, record = false) => this.onMessage?.({ chat: 'user', from: 'user', text, attachments: [], record }),
      log: this.deps.log,
    };
    const call: Call = this.provider === 'android'
      ? new AndroidCall(host)
      : new OpenAICall(host, {
        apiKey: String(this.config.apiKey), model: this.config.model as string | undefined,
        voice: this.config.voice as string | undefined, instructions: this.config.instructions as string | undefined,
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

  /** Ends the call, if there is one; what Otto said that was never spoken goes to a notification. */
  async hangUp(reason: string) {
    const call = this.call;
    if (!call) return;
    this.call = null;
    const left = await call.stop(reason);
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
