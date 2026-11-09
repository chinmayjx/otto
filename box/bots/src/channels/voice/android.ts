// A voice call with the phone's own speech: Android's recognizer hears the user and its text-to-speech says Otto's
// words. Nothing is in between, so every sentence the phone hears goes to Otto (the bot's agent) as it is, and the
// transcript is always in step with it. Free; slower to answer small talk than a realtime voice.
//
// Only what's worth a turn goes, though: a bit that is only filler ("so", "um", "yeah so") waits and goes in front of
// the next real sentence, so a cough or a trailing word doesn't make Otto answer again.
import { Call, speakable } from './call.js';

const FILLER = new Set(['so', 'um', 'umm', 'uh', 'uhm', 'erm', 'er', 'hmm', 'hm', 'mm', 'mhm', 'ah', 'oh', 'like', 'well', 'and',
  'but', 'then', 'the', 'a', 'i', 'just', 'actually', 'anyway', 'basically']);
const SOFT = new Set(['yeah', 'yes', 'okay', 'ok', 'right', 'no']); // answers on their own, fillers next to one

/** Whether a sentence the phone heard is only filler: nothing for Otto to answer. */
export function filler(text: string): boolean {
  const words = text.toLowerCase().replace(/[^\p{L}\p{N}\s']/gu, ' ').split(/\s+/).filter(Boolean);
  if (!words.length) return true;
  return words.every(w => FILLER.has(w) || SOFT.has(w)) && words.some(w => FILLER.has(w));
}

export class AndroidCall extends Call {
  readonly mode = 'android';

  async begin(): Promise<Record<string, unknown>> {
    this.up = true;
    this.update();
    return {};
  }

  private held: string[] = [];

  heard(text: string) {
    text = text.trim();
    if (!text || this.stopping) return;
    if (filler(text)) {
      this.held.push(text);
      this.log(`held a filler (${text.split(/\s+/).length} words)`);
      return;
    }
    const all = [...this.held.splice(0), text].join(' ');
    this.transcript.add('user', all, true);
    this.host.toAgent(all);
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
