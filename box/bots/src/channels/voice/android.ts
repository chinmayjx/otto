// A voice call with the phone's own speech: Android's recognizer hears the user and its text-to-speech says Otto's
// words. Nothing is in between, so every sentence the phone hears goes to Otto (the bot's agent) as it is, and the
// transcript is always in step with it. Free; slower to answer small talk than a realtime voice.
import { Call, speakable } from './call.js';

export class AndroidCall extends Call {
  readonly mode = 'android';

  async begin(): Promise<Record<string, unknown>> {
    this.up = true;
    this.update();
    return {};
  }

  heard(text: string) {
    if (!text.trim() || this.stopping) return;
    this.transcript.add('user', text, true);
    this.host.toAgent(text.trim());
  }

  /** What the phone says it's doing: speaking Otto's words, or the user talking. */
  report(r: { speaking?: boolean; hearing?: boolean }) {
    if (typeof r.speaking === 'boolean') this.voiceSpeaking = r.speaking;
    if (typeof r.hearing === 'boolean') this.userSpeaking = r.hearing;
    this.update();
  }

  say(text: string) {
    const words = speakable(text);
    if (!words) return;
    this.transcript.add('otto', words);
    this.push({ type: 'speak', text: words });
  }

  protected async end(): Promise<string[]> { return []; }
}
