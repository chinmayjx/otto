// `otto sessions list|send`: the user's agent sessions on this box, and a message into one of them. Each agent's
// adapter (adapters/<agent>/sessions.mjs) knows its own sessions and has list() and send(id, text); Claude Code's is
// the only one so far. Otto's own bots' sessions are left out: they're Otto, not the user's chats.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { configDir } from './bots.js';
import { type Config, OttoError } from './lib.js';
import { readStdin, sender } from './schedules.js';

type Out = (s: string) => void;
interface Args { _: string[]; [k: string]: any }

export interface AgentSession {
  agent: string;
  id: string;
  name: string | null;
  title: string | null;
  cwd: string | null;
  lastActive: number;
  state: 'busy' | 'idle' | 'not running';
  remote: boolean;
  pid?: number;
}

interface Adapter {
  list(o: { since: number }): AgentSession[];
  send(id: string, text: string): Promise<{ how: 'live' | 'resumed'; detail: string }>;
}

const ADAPTERS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../adapters');
const AGENTS = ['claude'];
const adapter = (agent: string): Promise<Adapter> => import(pathToFileURL(path.join(ADAPTERS, agent, 'sessions.mjs')).href);

/** What the default list covers: running sessions, and those active in the last few days. */
const RECENT = 3 * 86400_000;

/** The session ids Otto's bots run as. */
function botSessions(): Set<string> {
  const ids = new Set<string>();
  let files: string[] = [];
  try { files = fs.readdirSync(configDir()).filter(f => f.endsWith('.json')); } catch {}
  for (const f of files) {
    try { const s = JSON.parse(fs.readFileSync(path.join(configDir(), f), 'utf8')).session; if (s) ids.add(String(s)); } catch {}
  }
  return ids;
}

/** Every agent's sessions (running, or active since `since`), newest activity first. */
export async function listSessions(since: number): Promise<AgentSession[]> {
  const bots = botSessions();
  const all = (await Promise.all(AGENTS.map(async a => (await adapter(a)).list({ since })))).flat();
  return all.filter(s => !bots.has(s.id)).sort((a, b) => b.lastActive - a.lastActive);
}

const label = (s: AgentSession) => [s.name, s.title].filter(Boolean).join(': ') || '(untitled)';
const folder = (cwd: string | null) => cwd ? cwd.replace(os.homedir(), '~') : '-';

function ago(ms: number): string {
  const m = Math.round((Date.now() - ms) / 60_000);
  return m < 1 ? 'just now' : m < 60 ? `${m}m ago` : m < 1440 ? `${Math.round(m / 60)}h ago` : `${Math.round(m / 1440)}d ago`;
}

function table(list: AgentSession[]): string {
  const rows = list.map(s => [s.id.slice(0, 8), label(s).slice(0, 60), folder(s.cwd), ago(s.lastActive), s.state, s.remote ? 'remote' : '']);
  const width = rows.reduce((w, r) => w.map((n, i) => Math.max(n, r[i].length)), [0, 0, 0, 0, 0, 0]);
  return rows.map(r => r.map((c, i) => i < r.length - 1 ? c.padEnd(width[i]) : c).join('  ').trimEnd()).join('\n');
}

/** The session `query` means: an id or its prefix, else an exact name, else a title or name containing it. */
export function resolve(list: AgentSession[], query: string): AgentSession {
  const q = query.trim().toLowerCase();
  const tries = [
    (s: AgentSession) => s.id.toLowerCase() === q,
    (s: AgentSession) => q.length >= 4 && s.id.toLowerCase().startsWith(q),
    (s: AgentSession) => s.name?.toLowerCase() === q,
    (s: AgentSession) => s.title?.toLowerCase() === q,
    (s: AgentSession) => !!(s.name?.toLowerCase().includes(q) || s.title?.toLowerCase().includes(q)),
  ];
  for (const t of tries) {
    const found = list.filter(t);
    if (found.length === 1) return found[0];
    if (found.length > 1) throw new OttoError(`"${query}" could be any of these; use the id:\n${table(found.slice(0, 10))}`);
  }
  throw new OttoError(`no session matches "${query}" (otto sessions list --all)`);
}

/** Sends text to the session `query` means, headed with who sent it and how to answer; where it went, and how. */
export async function sendToSession(config: Config, query: string, text: string) {
  const s = resolve(await listSessions(0), query);
  const body = `[From ${sender(config)} via Otto. Reply to the user with: otto send otto_voice "…"]\n\n${text}`;
  const r = await (await adapter(s.agent)).send(s.id, body);
  return { session: s, ...r };
}

export async function sessions(config: Config, cmd: string | undefined, a: Args, out: Out) {
  switch (cmd) {
    case undefined: case 'list': case 'ls': {
      let list = await listSessions(a.all ? 0 : Date.now() - RECENT);
      if (a.limit) list = list.slice(0, Number(a.limit));
      if (a.json) return out(JSON.stringify(list.map(s => ({ ...s, lastActive: new Date(s.lastActive).toISOString() })), null, 2));
      return out(list.length ? table(list) : `no sessions${a.all ? '' : ' in the last 3 days (--all for older ones)'}`);
    }
    case 'send': {
      const [query, ...words] = a._;
      if (!query) throw new OttoError('missing a session: otto sessions send <id|name> "text" (text from stdin when not given)');
      const text = words.length ? words.join(' ') : readStdin().trim();
      if (!text) throw new OttoError('nothing to send: give the text, or pipe it in');
      let r;
      try { r = await sendToSession(config, query, text); } catch (e: any) {
        throw e instanceof OttoError ? e : new OttoError(e.message);
      }
      const where = `${label(r.session)} (${r.session.id.slice(0, 8)}, ${folder(r.session.cwd)})`;
      if (a.json) return out(JSON.stringify({ id: r.session.id, name: r.session.name, title: r.session.title, how: r.how, detail: r.detail }));
      return out(`sent to ${where}, ${r.how === 'live' ? 'live' : 'resumed'}: ${r.detail}`);
    }
  }
  throw new OttoError('otto sessions list [--all] [--json] | otto sessions send <id|name> [text]');
}
