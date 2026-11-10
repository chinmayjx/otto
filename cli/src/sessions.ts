// `otto sessions`: the user's agent sessions on this box, and a message into any of them as the user. Claude Code's
// Remote Control chats come from devbox-hub (hub/sessions.py), which holds the claude.ai side: their titles, whether
// a Claude serves them, and bringing a stopped one back before a message goes in. Codex threads are read from their
// rollout files here; a message to one that isn't open resumes it headless (`codex exec resume`).
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { type Config, OttoError } from './lib.js';
import { readStdin, sender } from './schedules.js';

type Out = (s: string) => void;
interface Args { _: string[]; [k: string]: any }

export interface AgentSession {
  id: string;
  agent: 'claude' | 'codex';
  title: string;
  cwd: string;
  /** When it last did anything (ISO). */
  active: string;
  /** live: an agent process holds it now; idle: a message starts or reconnects one first. */
  state: 'live' | 'idle';
  /** In the middle of a turn (Claude only). */
  busy?: boolean;
  url?: string;
}

const home = os.homedir();
const CODEX_DAYS = 14; // older Codex threads aren't listed (most are one-off exec runs)

/** devbox-hub's local API. */
async function hub(config: Config, method: string, p: string, body?: unknown): Promise<any> {
  let r: Response;
  try {
    r = await fetch(config.sessionsHub + p, { method, headers: body === undefined ? undefined : { 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(90_000) });
  } catch (e: any) {
    if (e?.name === 'TimeoutError') throw new OttoError(`devbox-hub didn't answer in time (${p})`);
    throw new OttoError(`devbox-hub isn't running (expected at ${config.sessionsHub}; [sessions] hub in the config)`);
  }
  const text = await r.text();
  if (r.status === 404) throw new OttoError('no such chat in this box\'s folders');
  if (!r.ok) {
    let error = text.trim();
    try { error = JSON.parse(text).error ?? error; } catch {}
    throw new OttoError(`devbox-hub: ${error || `HTTP ${r.status}`}`);
  }
  return JSON.parse(text);
}

async function claudeSessions(config: Config): Promise<AgentSession[]> {
  const rows: any[] = await hub(config, 'GET', '/api/sessions/recent');
  return rows.map(r => ({ id: r.session, agent: 'claude', title: r.title || path.basename(r.cwd), cwd: r.cwd, active: r.updated,
    state: r.running ? 'live' : 'idle', busy: !!r.busy, url: r.url }));
}

// ---- Codex ---------------------------------------------------------------------------------------------------------

const codexHome = () => process.env.CODEX_HOME ?? path.join(home, '.codex');

/** Rollout files changed in the last `days`, under sessions/<y>/<m>/<d>/. */
function rollouts(days: number): string[] {
  const root = path.join(codexHome(), 'sessions'), since = Date.now() - days * 86400_000, files: string[] = [];
  const walk = (dir: string) => {
    let entries: fs.Dirent[];
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.jsonl')) { try { if (fs.statSync(p).mtimeMs >= since) files.push(p); } catch {} }
    }
  };
  walk(root);
  return files;
}

/** The first line of a prompt, shortened (as adapters/lib.mjs titleOf). */
function titleOf(text: string): string {
  const first = String(text).split('\n').map(s => s.trim().replace(/^#+\s+/, '')).find(Boolean) ?? '';
  return first.length > 60 ? first.slice(0, 60).replace(/\s+\S*$/, '') + '…' : first;
}

/** A thread's id, cwd and title from the start of its rollout, or null for one that isn't the user's own: a
 * subagent, Otto's voice sign-in probes, a scheduled run, anything run in /tmp. */
function codexThread(file: string, scheduled: Set<string>): Omit<AgentSession, 'state'> | null {
  let head = '';
  try {
    const fd = fs.openSync(file, 'r');
    const buf = Buffer.alloc(256 * 1024);
    head = buf.subarray(0, fs.readSync(fd, buf, 0, buf.length, 0)).toString('utf8');
    fs.closeSync(fd);
  } catch { return null; }
  let meta: any = null, title = '';
  for (const line of head.split('\n')) {
    let e: any;
    try { e = JSON.parse(line); } catch { continue; }
    if (e.type === 'session_meta') meta = e.payload;
    const p = e.payload;
    if (e.type !== 'response_item' || p?.type !== 'message' || p.role !== 'user') continue;
    const text = (p.content ?? []).map((c: any) => c.text ?? '').join('\n');
    if (/^\s*(<|# AGENTS\.md)/.test(text)) continue; // environment context and instructions
    if ((title = titleOf(text))) break;
  }
  if (!meta?.id || scheduled.has(meta.id) || title.startsWith('[Scheduled run of ')) return null;
  if (meta.thread_source === 'subagent' || typeof meta.source === 'object' || String(meta.originator ?? '').startsWith('otto_')) return null;
  if (/^\/tmp(\/|$)/.test(meta.cwd ?? '')) return null; // throwaway runs (tests, probes)
  return { id: meta.id, agent: 'codex', title: title || 'Codex', cwd: meta.cwd ?? '', active: fs.statSync(file).mtime.toISOString() };
}

/** The threads Otto's schedules ran (their runs.jsonl name each run's session). */
function scheduledThreads(): Set<string> {
  const dir = path.join(process.env.XDG_STATE_HOME ?? path.join(home, '.local/state'), 'otto', 'schedules'), ids = new Set<string>();
  let names: string[] = [];
  try { names = fs.readdirSync(dir); } catch {}
  for (const n of names) {
    let text = '';
    try { text = fs.readFileSync(path.join(dir, n, 'runs.jsonl'), 'utf8'); } catch { continue; }
    for (const m of text.matchAll(/"session":"([^"]+)"/g)) ids.add(m[1]);
  }
  return ids;
}

/** Rollout files a running Codex holds open, and the command lines of running Codex processes. */
function codexProcesses(): { open: Set<string>; cmdlines: string[] } {
  const open = new Set<string>(), cmdlines: string[] = [];
  let pids: string[] = [];
  try { pids = fs.readdirSync('/proc').filter(p => /^\d+$/.test(p)); } catch {}
  for (const pid of pids) {
    let cmd = '';
    try { cmd = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').replace(/\0/g, ' '); } catch { continue; }
    if (!/codex/.test(cmd)) continue;
    cmdlines.push(cmd);
    let fds: string[] = [];
    try { fds = fs.readdirSync(`/proc/${pid}/fd`); } catch { continue; }
    for (const fd of fds) {
      try { const t = fs.readlinkSync(`/proc/${pid}/fd/${fd}`); if (t.endsWith('.jsonl')) open.add(t); } catch {}
    }
  }
  return { open, cmdlines };
}

function codexSessions(): (AgentSession & { file: string })[] {
  const scheduled = scheduledThreads(), procs = codexProcesses(), rows: (AgentSession & { file: string })[] = [];
  for (const file of rollouts(CODEX_DAYS)) {
    const t = codexThread(file, scheduled);
    if (!t) continue;
    const live = procs.open.has(file) || procs.cmdlines.some(c => c.includes(t.id));
    rows.push({ ...t, state: live ? 'live' : 'idle', file });
  }
  return rows;
}

// ---- The commands --------------------------------------------------------------------------------------------------

/** Claude's and Codex's sessions, most recently active first. A hub that's down leaves Claude's out, with a note. */
export async function listSessions(config: Config): Promise<{ sessions: AgentSession[]; problems: string[] }> {
  const problems: string[] = [];
  const claude = await claudeSessions(config).catch(e => { problems.push(`Claude sessions: ${e.message}`); return []; });
  const codex = codexSessions().map(({ file: _, ...s }) => s);
  return { sessions: [...claude, ...codex].sort((a, b) => b.active.localeCompare(a.active)), problems };
}

function ago(iso: string): string {
  const m = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60_000));
  return m < 1 ? 'just now' : m < 60 ? `${m}m ago` : m < 48 * 60 ? `${Math.round(m / 60)}h ago` : `${Math.round(m / 1440)}d ago`;
}

const tilde = (p: string) => (p.startsWith(home) ? '~' + p.slice(home.length) : p);

/** One session from what the user (or a voice) calls it: its id, the start of its id, or its title (whole, else a
 * part of it), if only one session matches. */
export function pick(sessions: AgentSession[], name: string): AgentSession {
  const q = name.trim().toLowerCase();
  const tries: ((s: AgentSession) => boolean)[] = [
    s => s.id.toLowerCase() === q,
    s => s.id.toLowerCase().startsWith(q) || s.id.toLowerCase().replace(/^session_/, '').startsWith(q),
    s => s.title.toLowerCase() === q,
    s => s.title.toLowerCase().includes(q),
  ];
  for (const t of tries) {
    const found = sessions.filter(t);
    if (found.length === 1) return found[0];
    if (found.length > 1) {
      throw new OttoError(`"${name}" matches ${found.length} sessions; give the id:\n`
        + found.slice(0, 8).map(s => `  ${s.id}  ${s.title}  (${tilde(s.cwd)}, ${ago(s.active)})`).join('\n'));
    }
  }
  throw new OttoError(`no session "${name}" (otto sessions list)`);
}

/** What goes in: who it's from and how to answer, then the text. */
const framed = (config: Config, text: string) =>
  `[From ${sender(config)} via Otto. Reply to the user with: otto send otto_voice "…"]\n${text}`;

/** Sends the text into a session as the user. Returns what happened, in a line. */
export async function sendToSession(config: Config, name: string, text: string): Promise<string> {
  if (!text.trim()) throw new OttoError('nothing to send: give the text, or pipe it in');
  let s: AgentSession;
  if (/^session_[A-Za-z0-9]+$/.test(name)) s = { id: name, agent: 'claude', title: name, cwd: '', active: '', state: 'idle' };
  else {
    const { sessions, problems } = await listSessions(config);
    try { s = pick(sessions, name); } catch (e) { throw problems.length ? new OttoError(`${(e as Error).message}\n(${problems.join('; ')})`) : e; }
  }
  if (s.agent === 'claude') {
    const r = await hub(config, 'POST', `/api/sessions/${encodeURIComponent(s.id)}/messages`, { text: framed(config, text) });
    return `sent to ${s.title === s.id ? s.id : `"${s.title}" (${s.id})`} in ${tilde(r.cwd)}`;
  }
  return resumeCodex(config, s.id, framed(config, text));
}

/** A Codex thread that isn't open takes the message as a headless `codex exec resume`, in the background. */
function resumeCodex(config: Config, id: string, text: string): string {
  const t = codexSessions().find(s => s.id === id);
  if (!t) throw new OttoError(`no Codex thread ${id} in the last ${CODEX_DAYS} days`);
  if (t.state === 'live') throw new OttoError(`"${t.title}" is a live Codex session; Otto can't put a message into an open Codex yet`);
  const dir = path.join(process.env.XDG_STATE_HOME ?? path.join(home, '.local/state'), 'otto', 'sessions');
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const log = path.join(dir, `codex-${id}-${new Date().toISOString().replace(/[:.]/g, '-')}.log`);
  const out = fs.openSync(log, 'a', 0o600);
  const input = path.join(dir, `.in-${process.pid}-${Date.now()}`);
  fs.writeFileSync(input, text, { mode: 0o600 });
  const inFd = fs.openSync(input, 'r');
  fs.unlinkSync(input); // the child keeps it open
  // Full access, as a scheduled Codex run has: its sandbox otherwise has no network, so no `otto send`.
  const env: Record<string, string | undefined> = { ...process.env };
  for (const k of Object.keys(env)) if (k === 'CODEX_THREAD_ID' || k === 'CLAUDECODE' || k.startsWith('CLAUDE_CODE_')) delete env[k];
  const p = spawn(process.env.CODEX_BIN || 'codex', ['exec', 'resume', '--json', '--skip-git-repo-check', '-c', 'sandbox_mode="danger-full-access"', id, '-'],
    { cwd: fs.existsSync(t.cwd) ? t.cwd : home, env, detached: true, stdio: [inFd, out, out] });
  p.unref();
  fs.closeSync(inFd);
  fs.closeSync(out);
  return `resumed Codex "${t.title}" (${id}) in ${tilde(t.cwd)} with the message, in the background; log: ${log}`;
}

export async function sessions(config: Config, cmd: string | undefined, a: Args, out: Out) {
  switch (cmd) {
    case undefined: case 'list': case 'ls': {
      let { sessions: list, problems } = await listSessions(config);
      if (a.agent) list = list.filter(s => s.agent === a.agent);
      if (a.search) {
        const q = String(a.search).toLowerCase();
        list = list.filter(s => s.title.toLowerCase().includes(q) || s.cwd.toLowerCase().includes(q));
      }
      if (a.limit) list = list.slice(0, Number(a.limit));
      for (const p of problems) process.stderr.write(`otto: ${p}\n`);
      if (a.json) return out(JSON.stringify(list, null, 2));
      const rows = list.map(s => [s.id, s.agent, s.busy ? 'busy' : s.state, ago(s.active), s.title, tilde(s.cwd)]);
      const width = (i: number) => Math.max(...rows.map(r => r[i].length));
      const text = rows.map(r => r.map((c, i) => (i < 4 ? c.padEnd(width(i)) : c)).join('  ').replace(/\s+$/, '')).join('\n');
      return out(text || 'no sessions');
    }
    case 'send': {
      const [name, ...words] = a._;
      if (!name) throw new OttoError('missing a session: otto sessions send <id|title> "text" (otto sessions list)');
      return out(await sendToSession(config, name, words.length ? words.join(' ') : readStdin().trim()));
    }
  }
  throw new OttoError(`unknown command: otto sessions ${cmd} (see otto help)`);
}
