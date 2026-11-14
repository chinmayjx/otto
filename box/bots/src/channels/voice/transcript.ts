// A voice call's transcript, the same whatever does the speaking: what the user said, what the voice said on its own,
// and what Otto (the bot's agent) said through it. The agent already knows its own words; the rest reaches it in
// bundles, each line once: when the call hands something to the agent, it takes everything said since the last
// bundle. A line can be held open for words still being transcribed (the user's speech, a moment after they stop).
// With a History, every line is also kept on disk (history.ts), and marked there once its bundle reached the agent.
import type { History } from './history.js';

export type Role = 'user' | 'voice' | 'otto';

interface Line { role: Role; text: string | null; sent: boolean; id: number }

/** A finished line the agent hasn't seen yet. */
export type Unseen = Readonly<Line> & { text: string };

export const WHO: Record<Role, string> = { user: 'User', voice: 'Voice', otto: 'You (Otto)' };

/** Words that are only noise or filler on their own (the phone's speech holds a sentence of nothing but these, android.ts). */
export const FILLER = new Set(['so', 'um', 'umm', 'uh', 'uhm', 'erm', 'er', 'hmm', 'hm', 'mm', 'mhm', 'ah', 'oh', 'like', 'well', 'and',
  'but', 'then', 'the', 'a', 'i', 'just', 'actually', 'anyway', 'basically']);
/** Answers on their own, fillers next to one. */
export const SOFT = new Set(['yeah', 'yes', 'okay', 'ok', 'right', 'no']);

/** How much unseen talk on a call is enough to be worth the agent's attention: steering its turn with it, or holding
 * back a reply written without it (index.ts). Each user or voice line counts the characters of its words once fillers
 * ("um", "so", "okay", "yeah") are dropped, and a line left with fewer than two words counts nothing: a cough, a
 * "hmm", a lone "yes", the voice's "On it." The lines together have to reach this many characters, about one short
 * real sentence ("what's on at six", "it's about three o'clock"). The voice's lines count like the user's: when it
 * answered something itself, the agent must know, or it says the same again. */
export const WORTH_CHARS = 20;

/** The weight of unseen lines, by the rule above. */
export function weight(lines: readonly { role: Role; text: string | null }[]): number {
  let n = 0;
  for (const l of lines) {
    if (l.role === 'otto' || !l.text) continue;
    const words = l.text.toLowerCase().replace(/[^\p{L}\p{N}\s']/gu, ' ').split(/\s+/).filter(w => w && !FILLER.has(w) && !SOFT.has(w));
    if (words.length >= 2) n += words.join(' ').length;
  }
  return n;
}

/** Whether unseen lines amount to something the agent should see before it speaks again. */
export const worth = (lines: readonly { role: Role; text: string | null }[]) => weight(lines) >= WORTH_CHARS;

/** Lines as the agent reads them. */
export const show = (lines: readonly { role: Role; text: string | null }[]) => lines.filter(l => l.text).map(l => `${WHO[l.role]}: ${l.text}`);

/** Lines taken for the agent; delivered() once they've been written to it. */
export interface Bundle { lines: string[]; delivered(): void }

export class Transcript {
  private lines: Line[] = [];
  private open = new Map<string, Line>();
  private waiters: (() => void)[] = [];
  /** Runs when a line is finished (added, or its words filled in). */
  onLine: (() => void) | null = null;

  constructor(private history?: History) {}

  /** A finished line. Otto's own are known to the agent already. */
  add(role: Role, text: string, sent = role === 'otto'): void {
    if (!text.trim()) return;
    const id = this.history?.add(role, text.trim(), sent) ?? 0;
    this.lines.push({ role, text: text.trim(), sent, id });
    if (!sent) this.onLine?.();
  }

  /** Keeps the place of a line whose words come later (fill), so the order is the order things were said. */
  hold(id: string, role: Role): void {
    if (this.open.has(id)) return;
    const l: Line = { role, text: null, sent: false, id: this.history?.add(role, null, false) ?? 0 };
    this.lines.push(l);
    this.open.set(id, l);
  }

  fill(id: string, text: string): void {
    const l = this.open.get(id);
    if (!l) return this.add('user', text);
    l.text = text.trim();
    if (this.history) {
      this.history.fill(l.id, l.text);
      if (!l.text) this.history.delivered([l.id]); // nothing heard: nothing for the agent
    }
    this.open.delete(id);
    if (!this.open.size) for (const w of this.waiters.splice(0)) w();
    if (l.text) this.onLine?.();
  }

  /** Waits (up to ms) for the lines still being transcribed. */
  settled(ms: number): Promise<void> {
    if (!this.open.size) return Promise.resolve();
    return new Promise(resolve => {
      const t = setTimeout(resolve, ms);
      this.waiters.push(() => { clearTimeout(t); resolve(); });
    });
  }

  /** Whether the user has said anything the agent hasn't seen. */
  get unheard(): boolean {
    return this.lines.some(l => !l.sent && l.role === 'user' && l.text);
  }

  /** The finished lines the agent hasn't seen, oldest first, left unseen. */
  unseen(): Unseen[] {
    return this.lines.filter((l): l is Line & { text: string } => !l.sent && l.text !== null);
  }

  /** The lines the agent hasn't seen (or only those of them given), as "Who: words", now marked seen. Lines still
   * open stay for the next bundle. */
  take(only?: readonly Unseen[]): Bundle {
    const lines: string[] = [], ids: number[] = [];
    const pick = only && new Set<Readonly<Line>>(only);
    for (const l of this.lines) {
      if (l.sent || l.text === null || (pick && !pick.has(l))) continue;
      l.sent = true;
      ids.push(l.id);
      if (l.text) lines.push(`${WHO[l.role]}: ${l.text}`);
    }
    return { lines, delivered: () => this.history?.delivered(ids) };
  }
}
