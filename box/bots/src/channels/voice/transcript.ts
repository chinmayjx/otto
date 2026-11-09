// A voice call's transcript, the same whatever does the speaking: what the user said, what the voice said on its own,
// and what Otto (the bot's agent) said through it. The agent already knows its own words; the rest reaches it in
// bundles, each line once: when the call hands something to the agent, it takes everything said since the last
// bundle. A line can be held open for words still being transcribed (the user's speech, a moment after they stop).

export type Role = 'user' | 'voice' | 'otto';

interface Line { role: Role; text: string | null; sent: boolean }

const WHO: Record<Role, string> = { user: 'User', voice: 'Voice', otto: 'You (Otto)' };

export class Transcript {
  private lines: Line[] = [];
  private open = new Map<string, Line>();
  private waiters: (() => void)[] = [];

  /** A finished line. Otto's own are known to the agent already. */
  add(role: Role, text: string, sent = role === 'otto'): void {
    if (text.trim()) this.lines.push({ role, text: text.trim(), sent });
  }

  /** Keeps the place of a line whose words come later (fill), so the order is the order things were said. */
  hold(id: string, role: Role): void {
    if (this.open.has(id)) return;
    const l: Line = { role, text: null, sent: false };
    this.lines.push(l);
    this.open.set(id, l);
  }

  fill(id: string, text: string): void {
    const l = this.open.get(id);
    if (!l) return this.add('user', text);
    l.text = text.trim();
    this.open.delete(id);
    if (!this.open.size) for (const w of this.waiters.splice(0)) w();
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

  /** The lines the agent hasn't seen, as "Who: words", now marked seen. Lines still open stay for the next bundle. */
  take(): string[] {
    const out: string[] = [];
    for (const l of this.lines) {
      if (l.sent || l.text === null) continue;
      l.sent = true;
      if (l.text) out.push(`${WHO[l.role]}: ${l.text}`);
    }
    return out;
  }
}
