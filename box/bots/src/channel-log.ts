// Each channel's own log: every message sent or received on it, whoever did it (a bot, a scheduled run, a session's
// `otto send`), in ~/.local/state/otto/channels/<kind>.jsonl (0600; rolls over to .1 at 1 MB). It belongs to the
// channel, not to any bot: nothing goes into a bot's conversation. When the user replies to something a bot didn't
// send itself, the bot looks here (`otto channel log <kind>`).
import fs from 'node:fs';
import path from 'node:path';
import { ottoState } from './config.js';

export interface LogEntry {
  /** ISO time. */
  at: string;
  /** "out": sent to the user; "in": from the user. */
  dir: 'in' | 'out';
  /** Who: "user", "bot otto", "schedule morning (run r7)", "session cc-mobile", … */
  by: string;
  text?: string;
  file?: string;
}

const MAX = 1024 * 1024;
const KIND = /^[a-z0-9_-]{1,32}$/i;

export const logDir = () => path.join(ottoState(), 'channels');
const fileOf = (kind: string) => {
  if (!KIND.test(kind)) throw new Error(`not a channel: "${kind}"`);
  return path.join(logDir(), `${kind}.jsonl`);
};

/** Adds an entry to a channel's log. Never throws: a log that can't be written mustn't stop a message. */
export function logChannel(kind: string, e: Omit<LogEntry, 'at'>): void {
  try {
    const file = fileOf(kind);
    fs.mkdirSync(logDir(), { recursive: true, mode: 0o700 });
    if ((fs.statSync(file, { throwIfNoEntry: false })?.size ?? 0) > MAX) fs.renameSync(file, `${file}.1`);
    fs.appendFileSync(file, JSON.stringify({ at: new Date().toISOString(), ...e }) + '\n', { mode: 0o600 });
  } catch {}
}

/** A channel's entries, oldest first: those since `since` (ms), at most the last `limit`. */
export function readChannel(kind: string, o: { since?: number; limit?: number } = {}): LogEntry[] {
  const file = fileOf(kind);
  const out: LogEntry[] = [];
  for (const f of [`${file}.1`, file]) {
    let text = '';
    try { text = fs.readFileSync(f, 'utf8'); } catch { continue; }
    for (const line of text.split('\n')) {
      if (!line) continue;
      try {
        const e = JSON.parse(line) as LogEntry;
        if (!o.since || Date.parse(e.at) >= o.since) out.push(e);
      } catch {}
    }
  }
  return o.limit ? out.slice(-o.limit) : out;
}

/** The channels that have a log. */
export function loggedChannels(): string[] {
  try { return fs.readdirSync(logDir()).filter(f => f.endsWith('.jsonl')).map(f => f.slice(0, -6)).sort(); } catch { return []; }
}
