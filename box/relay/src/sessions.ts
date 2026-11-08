// Which tab group is whose. An agent session names itself (OTTO_SESSION, set by its adapter); the relay keeps
// the group the phone made for it, and its title, in a small JSON file so a session finds its tabs again after
// the relay, the app or the phone restarts. Sessions unused for 30 days are forgotten (the app drops their
// tabs on its own schedule).
import fs from 'node:fs';
import path from 'node:path';

export interface Session {
  group: string;
  title: string;
  used: number; // ms since the epoch
}

const FORGET_MS = 30 * 24 * 3600 * 1000;

export class Sessions {
  private all: Record<string, Session> = {};

  constructor(private file: string) {
    try { this.all = JSON.parse(fs.readFileSync(file, 'utf8')).sessions ?? {}; } catch {}
    const old = Date.now() - FORGET_MS;
    let dropped = false;
    for (const [id, s] of Object.entries(this.all)) if (s.used < old) { delete this.all[id]; dropped = true; }
    if (dropped) this.save();
  }

  get(id: string): Session | undefined {
    return this.all[id];
  }

  group(id: string): string | undefined {
    return this.all[id]?.group;
  }

  /** Whether any session has this group (a group the phone just made may not be recorded yet). */
  claimed(group: string): boolean {
    return Object.values(this.all).some(s => s.group === group);
  }

  set(id: string, group: string, title: string) {
    this.all[id] = { group, title, used: Date.now() };
    this.save();
  }

  touch(id: string) {
    const s = this.all[id];
    if (!s) return;
    s.used = Date.now();
    this.save();
  }

  delete(id: string) {
    if (!(id in this.all)) return;
    delete this.all[id];
    this.save();
  }

  list(): Record<string, Session> {
    return { ...this.all };
  }

  private save() {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    fs.writeFileSync(this.file + '.tmp', JSON.stringify({ sessions: this.all }, null, 1));
    fs.renameSync(this.file + '.tmp', this.file);
  }
}
