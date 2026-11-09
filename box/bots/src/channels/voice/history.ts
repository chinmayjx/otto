// The voice's transcript on disk, one file per bot (voice-transcript.jsonl in its state folder), so what was said on
// a call outlives the call and the service. The bot's own conversation is the agent's; this is the voice's side of
// it: the last 50 lines said on calls, each marked once it has been written to the agent. Lines that never were (the
// service stopped while they waited, as a record, for the agent's next message) go to the agent when the bot
// starts again (main.ts). One instance per file, shared by everything in the process that writes it.
import fs from 'node:fs';
import path from 'node:path';
import { writePrivate } from '../../config.js';
import { type Role, WHO } from './transcript.js';

const KEEP = 50;

export interface Entry {
  id: number;
  at: string;
  role: Role;
  /** Null while the words are still being transcribed. */
  text: string | null;
  /** Written to the agent (Otto's own lines always are). */
  delivered: boolean;
}

const open = new Map<string, History>();

export class History {
  private entries: Entry[] = [];
  private next = 1;

  private constructor(readonly file: string) {
    try {
      for (const l of fs.readFileSync(file, 'utf8').split('\n')) {
        if (!l.trim()) continue;
        try {
          const e = JSON.parse(l);
          if (typeof e.id === 'number' && typeof e.role === 'string') this.entries.push(e);
        } catch { /* a torn line: skipped */ }
      }
    } catch (e: any) {
      if (e.code !== 'ENOENT') throw e;
    }
    // A line whose words never came (the service stopped mid-transcription) has nothing to say.
    this.entries = this.entries.filter(e => e.text !== null);
    this.next = Math.max(0, ...this.entries.map(e => e.id)) + 1;
  }

  /** The history in this folder (a bot's state folder). */
  static in(dir: string): History {
    const file = path.join(dir, 'voice-transcript.jsonl');
    let h = open.get(file);
    if (!h) open.set(file, h = new History(file));
    return h;
  }

  /** A new line; its id. */
  add(role: Role, text: string | null, delivered: boolean): number {
    const id = this.next++;
    this.entries.push({ id, at: new Date().toISOString(), role, text, delivered });
    this.save();
    return id;
  }

  fill(id: number, text: string) {
    const e = this.entries.find(x => x.id === id);
    if (!e) return;
    e.text = text;
    this.save();
  }

  delivered(ids: number[]) {
    const set = new Set(ids);
    let changed = false;
    for (const e of this.entries) if (set.has(e.id) && !e.delivered) { e.delivered = true; changed = true; }
    if (changed) this.save();
  }

  /** What the agent never got, as "Who: words", and its ids. Only worth sending when the user said something in it:
   * otherwise it's marked delivered here and nothing comes back. */
  pending(): { lines: string[]; ids: number[] } {
    const left = this.entries.filter(e => !e.delivered && e.text);
    const ids = left.map(e => e.id);
    if (!left.some(e => e.role === 'user')) {
      this.delivered(ids);
      return { lines: [], ids: [] };
    }
    return { lines: left.map(e => `${WHO[e.role]}: ${e.text}`), ids };
  }

  get all(): readonly Entry[] { return this.entries; }

  /** The last KEEP lines, and any older one still waiting for the agent. */
  private save() {
    const cut = this.entries.length - KEEP;
    this.entries = this.entries.filter((e, i) => i >= cut || !e.delivered);
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true, mode: 0o700 });
      writePrivate(this.file, this.entries.map(e => JSON.stringify(e)).join('\n') + '\n');
    } catch { /* the call goes on; the next change tries again */ }
  }
}
