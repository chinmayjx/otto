// Claude Code's sessions on this box, for `otto sessions`: list() and send(id, text), from what Claude Code itself
// leaves on disk, and through its own ways in. Otto starts, keeps and routes nothing.
//
// - A running session has ~/.claude/sessions/<pid>.json: its session id, folder, name, busy/idle, and (for a Remote
//   Control chat) bridgeSessionId. Records outlive their process, so one counts only while its pid is alive and
//   started when the record says (procStart).
// - Every session has a transcript, ~/.claude/projects/<folder>/<id>.jsonl: the title is its first prompt (or the
//   name it was given with /rename), the last activity its mtime.
// - A running session takes a message through its peer inbox: a unix socket (messagingSocketPath) and a token in
//   ~/.claude/sessions/<pid>.<sha256 of the socket's real path>.key. It's framed as from another session; it wakes
//   the session at once, and a Remote Control chat shows it on claude.ai.
// - One that isn't running is continued in the background under the same id: `claude --bg --resume <id> <text>`, in
//   its own folder. (With the session running, that would start a copy instead, so it's only for stopped ones.)
import crypto from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { StringDecoder } from 'node:string_decoder';
import { HOME, titleOf } from '../lib.mjs';

const SESSIONS = path.join(HOME, '.claude/sessions');
const PROJECTS = path.join(HOME, '.claude/projects');
const CLAUDE = process.env.CLAUDE_BIN || path.join(HOME, '.local/bin/claude');

/** The running sessions' records whose process is still the one that wrote them, by session id. */
function running() {
  const live = new Map();
  let files = [];
  try { files = fs.readdirSync(SESSIONS).filter(f => /^\d+\.json$/.test(f)); } catch {}
  for (const f of files) {
    let r;
    try { r = JSON.parse(fs.readFileSync(path.join(SESSIONS, f), 'utf8')); } catch { continue; }
    if (!r.sessionId || !alive(r.pid, r.procStart)) continue;
    const had = live.get(r.sessionId);
    if (!had || (r.updatedAt ?? 0) > (had.updatedAt ?? 0)) live.set(r.sessionId, r);
  }
  return live;
}

/** The pid is running, and (when the record has it) started at procStart: field 22 of /proc/<pid>/stat. */
function alive(pid, procStart) {
  if (!pid) return false;
  try {
    const stat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8');
    return !procStart || stat.slice(stat.lastIndexOf(') ') + 2).split(' ')[19] === String(procStart);
  } catch { return false; }
}

/** Every transcript, by session id (the newest, when copies sit in more than one folder). */
function transcripts() {
  const all = new Map();
  let dirs = [];
  try { dirs = fs.readdirSync(PROJECTS); } catch {}
  for (const d of dirs) {
    let files;
    try { files = fs.readdirSync(path.join(PROJECTS, d)); } catch { continue; }
    for (const f of files) {
      if (!f.endsWith('.jsonl')) continue;
      const file = path.join(PROJECTS, d, f);
      let mtime;
      try { mtime = fs.statSync(file).mtimeMs; } catch { continue; }
      const id = f.slice(0, -6), had = all.get(id);
      if (!had || mtime > had.mtime) all.set(id, { file, mtime });
    }
  }
  return all;
}

/** A file's lines from the start, up to max bytes (a transcript can be tens of MB; what we need is near the top). */
function* head(file, max = 2 << 20) {
  const fd = fs.openSync(file, 'r');
  try {
    const buf = Buffer.alloc(1 << 16), dec = new StringDecoder('utf8');
    let rest = '', pos = 0;
    while (pos < max) {
      const n = fs.readSync(fd, buf, 0, buf.length, pos);
      if (!n) break;
      pos += n;
      const parts = (rest + dec.write(buf.subarray(0, n))).split('\n');
      rest = parts.pop();
      yield* parts;
    }
    if (rest) yield rest;
  } finally { fs.closeSync(fd); }
}

/** The last bytes' whole lines. */
function tail(file, bytes = 1 << 16) {
  const fd = fs.openSync(file, 'r');
  try {
    const size = fs.fstatSync(fd).size, start = Math.max(0, size - bytes);
    const buf = Buffer.alloc(size - start);
    fs.readSync(fd, buf, 0, buf.length, start);
    const lines = buf.toString('utf8').split('\n');
    return start ? lines.slice(1) : lines;
  } finally { fs.closeSync(fd); }
}

/**
 * What a transcript says about its session: the title (the first line of the first prompt; after a compaction, of
 * the first one after the summary), its folder, and whether it was started headless (`claude -p` or the SDK: a
 * schedule's run, a script; its first prompt's turnOrigin is "sdk").
 */
function readHead(file) {
  let title = null, cwd = null, headless = false, origin = false;
  try {
    for (const line of head(file)) {
      let e;
      try { e = JSON.parse(line); } catch { continue; }
      cwd ??= typeof e.cwd === 'string' ? e.cwd : null;
      if (!origin && e.type === 'user' && e.promptSource) { origin = true; headless = e.turnOrigin === 'sdk'; }
      if (!title) {
        let text = null;
        if (e.type === 'queue-operation' && e.operation === 'enqueue') text = e.content;
        else if (e.type === 'user' && typeof e.message?.content === 'string') text = e.message.content;
        // A message Otto passed on starts with who sent it (otto sessions send): the title is what follows.
        if (typeof text === 'string') text = text.replace(/^\[From [^\]\n]* via Otto\.[^\]\n]*\]\s*/, '');
        if (typeof text === 'string' && !/^\s*</.test(text) && !/^This session is being continued/.test(text)) title = titleOf(text) || null;
      }
      if (title && cwd && origin) break;
    }
  } catch {}
  return { title, cwd, headless };
}

/** The name the session was given (/rename), if any: the last one near the end of the transcript. */
function customName(file) {
  let name = null;
  try {
    for (const line of tail(file)) {
      if (!line.includes('"custom-title"') && !line.includes('"agent-name"')) continue;
      try { const e = JSON.parse(line); name = e.customTitle ?? e.agentName ?? name; } catch {}
    }
  } catch {}
  return name;
}

/** The title of session id's transcript, for adapters/claude/session.mjs. */
export function title(id) {
  const t = transcripts().get(id);
  return (t && readHead(t.file).title) || 'Claude';
}

/**
 * Claude Code's sessions: the running ones, and those whose transcript changed since `since` (ms; 0 for all).
 * Headless runs that aren't running now are left out. Each: { agent, id, name, title, cwd, lastActive (ms),
 * state: busy | idle | not running, remote (a Remote Control chat), pid (while running) }.
 */
export function list({ since = 0 } = {}) {
  const live = running(), files = transcripts(), out = [];
  for (const id of new Set([...live.keys(), ...files.keys()])) {
    const r = live.get(id), t = files.get(id);
    if (!r && t.mtime < since) continue;
    const h = t ? readHead(t.file) : { title: null, cwd: null, headless: false };
    if (!r && h.headless) continue;
    const named = r?.nameSource && r.nameSource !== 'derived' ? r.name : null;
    out.push({
      agent: 'claude', id,
      name: (t && customName(t.file)) || named || r?.name || null,
      title: h.title,
      cwd: r?.cwd ?? h.cwd,
      lastActive: Math.max(t?.mtime ?? 0, r?.updatedAt ?? 0),
      state: r ? (r.status === 'busy' ? 'busy' : 'idle') : 'not running',
      remote: !!r?.bridgeSessionId,
      ...(r ? { pid: r.pid } : {}),
    });
  }
  return out;
}

/**
 * Says text to session id: into its inbox while it runs, else by continuing it in the background. Returns
 * { how: 'live' | 'resumed', detail }.
 */
export async function send(id, text) {
  const r = running().get(id);
  if (r) {
    await inbox(r, text);
    return { how: 'live', detail: `into the running session (pid ${r.pid}, ${r.status === 'busy' ? 'busy: it reads it next' : 'idle'})` };
  }
  const t = transcripts().get(id);
  const cwd = t && readHead(t.file).cwd;
  if (!cwd) throw new Error(`no transcript with a folder for Claude session ${id}`);
  if (!fs.existsSync(cwd)) throw new Error(`session ${id}'s folder ${cwd} is gone`);
  const said = await new Promise((resolve, reject) => {
    execFile(CLAUDE, ['--bg', '--resume', id, text], { cwd, timeout: 60_000 }, (err, stdout, stderr) => {
      if (err) reject(new Error(`claude --bg --resume failed: ${String(stderr).trim() || err.message}`));
      else resolve(String(stdout).trim());
    });
  });
  return { how: 'resumed', detail: `continued in the background in ${cwd} (claude --bg --resume)${said ? `: ${said.split('\n').pop()}` : ''}` };
}

/** One user message through a running session's peer inbox: the auth line, the message line, then end. */
function inbox(r, text) {
  const sock = r.messagingSocketPath;
  if (!sock || !r.peerProtocol) throw new Error(`session ${r.sessionId} (pid ${r.pid}) takes no messages: it has no inbox`);
  let token;
  try {
    const key = crypto.createHash('sha256').update(fs.realpathSync(sock)).digest('hex');
    token = JSON.parse(fs.readFileSync(path.join(SESSIONS, `${r.pid}.${key}.key`), 'utf8')).peerToken;
  } catch {}
  if (!token) throw new Error(`no inbox key for session ${r.sessionId} (pid ${r.pid})`);
  const message = { msg_id: crypto.randomUUID(), type: 'user', message: { role: 'user', content: text }, priority: 'next' };
  return new Promise((resolve, reject) => {
    const c = net.createConnection(sock);
    const timer = setTimeout(() => { c.destroy(); reject(new Error(`session ${r.sessionId}'s inbox didn't answer`)); }, 10_000);
    c.on('connect', () => c.end(`${JSON.stringify({ type: 'auth', token })}\n${JSON.stringify(message)}\n`));
    c.on('error', e => { clearTimeout(timer); reject(new Error(`session ${r.sessionId}'s inbox: ${e.message}`)); });
    c.on('close', () => { clearTimeout(timer); resolve(); });
    c.resume();
  });
}
