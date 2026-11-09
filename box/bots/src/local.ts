// otto-bots' local API, beside the voice server's routes (on the same 127.0.0.1 port): what `otto send`,
// `otto channel` and `otto schedule` call, and the Otto app's Schedules section (the relay passes /schedules… on, as
// it does /voice/…).
//
//   POST   /send                          body {channel, text? | file + caption?, bot?, by?}: a message to the user on
//                                         a channel (telegram, otto_voice: spoken on a call or a notification, notify:
//                                         a phone notification); by is who sent it, for the channel's log
//   GET    /channels/<kind>/log?since=MS&limit=N   {entries}: that channel's log (channel-log.ts)
//   GET    /schedules                     {timezone, schedules: [view]} (runner.ts ScheduleView)
//   POST   /schedules                     body {name, prompt, at?, every?, cron?, once?, agent?, cwd?, model?, timeout?}
//   PATCH  /schedules/<name>              any of those (and paused); a change of timing counts from now
//   DELETE /schedules/<name>              its runs stay
//   POST   /schedules/<name>/run          runs it now: {run}
//   GET    /schedules/<name>/runs?limit=N {runs}, newest first; GET /schedules/-/runs: every schedule's
//   GET    /schedules/<name>/runs/<id>    {run, steps: [{kind: prompt|text|tool|result, text}]}: what the run did
import fs from 'node:fs';
import http from 'node:http';
import { readChannel } from './channel-log.js';
import { type Scheduler } from './schedules/runner.js';
import * as store from './schedules/store.js';
import type { Schedule } from './schedules/store.js';
import { check } from './schedules/when.js';

export interface LocalDeps {
  /** Sends on a channel; resolves to where it went, e.g. "telegram (otto)". */
  send(m: { channel: string; text?: string; file?: string; caption?: string; bot?: string; by: string }): Promise<string>;
  scheduler: Scheduler;
}

export function reply(res: http.ServerResponse, code: number, body: unknown) {
  res.writeHead(code, { 'content-type': typeof body === 'string' ? 'text/plain' : 'application/json', 'cache-control': 'no-store' });
  res.end(typeof body === 'string' ? body + '\n' : JSON.stringify(body) + '\n');
}

export function readBody(req: http.IncomingMessage, max = 1 << 20): Promise<any> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on('data', c => {
      size += c.length;
      if (size > max) { reject(new Error('too big')); req.destroy(); } else chunks.push(c);
    });
    req.on('end', () => {
      const s = Buffer.concat(chunks).toString('utf8');
      try { resolve(s ? JSON.parse(s) : {}); } catch { reject(new Error('not JSON')); }
    });
    req.on('error', reject);
  });
}

const FIELDS = ['prompt', 'at', 'every', 'cron', 'once', 'agent', 'cwd', 'model', 'timeout', 'paused'] as const;
const TIMING = ['at', 'every', 'cron', 'once'];

/** A schedule as asked for, checked; throws with what's wrong. */
function shaped(b: any, tz: string, was?: Schedule): Schedule {
  const s: any = was ? { ...was } : {};
  for (const k of FIELDS) {
    if (!(k in b)) continue;
    if (b[k] === null || b[k] === '') delete s[k]; else s[k] = b[k];
  }
  // A new timing replaces the old one whole.
  if (was && TIMING.some(k => k in b)) for (const k of TIMING) if (!(k in b)) delete s[k];
  if (typeof s.prompt !== 'string' || !s.prompt.trim()) throw new Error('a schedule needs a prompt');
  for (const k of ['at', 'every', 'cron', 'once', 'agent', 'cwd', 'model']) if (s[k] !== undefined && typeof s[k] !== 'string') throw new Error(`${k} is text`);
  if (s.timeout !== undefined && !(Number.isFinite(s.timeout) && s.timeout > 0)) throw new Error('timeout is a number of minutes');
  if (s.paused !== undefined) s.paused = !!s.paused;
  check(s, tz);
  if (!was || TIMING.some(k => k in b)) s.since = Date.now();
  return s as Schedule;
}

/** What a run did, from its log: the agent's words and the commands it ran. */
export function steps(name: string, id: string): { kind: string; text: string }[] {
  let text = '';
  try { text = fs.readFileSync(store.logOf(name, id), 'utf8'); } catch { return []; }
  const out: { kind: string; text: string }[] = [];
  for (const line of text.split('\n')) {
    let e: any;
    try { e = JSON.parse(line); } catch { continue; }
    if (e.type === 'assistant') { // Claude Code
      for (const c of e.message?.content ?? []) {
        if (c.type === 'text' && c.text?.trim()) out.push({ kind: 'text', text: c.text.trim() });
        if (c.type === 'tool_use') out.push({ kind: 'tool', text: tool(c.name, c.input) });
      }
    } else if (e.type === 'result') {
      const r = String(e.result ?? '').trim();
      if (r && r !== out.at(-1)?.text) out.push({ kind: 'result', text: r }); // often the last words again
    }
    else if (e.type === 'item.completed') { // Codex
      if (e.item?.type === 'agent_message') out.push({ kind: 'text', text: String(e.item.text ?? '').trim() });
      if (e.item?.type === 'command_execution') out.push({ kind: 'tool', text: `$ ${e.item.command}` });
    }
  }
  return out;
}

const tool = (name: string, input: any) => {
  if (name === 'Bash' && input?.command) return `$ ${String(input.command).slice(0, 500)}`;
  const arg = input?.file_path ?? input?.path ?? input?.url ?? input?.pattern ?? input?.query ?? '';
  return `${name}${arg ? ` ${String(arg).slice(0, 200)}` : ''}`;
};

/** Handles a request if it's one of these routes; false otherwise. */
export async function local(req: http.IncomingMessage, res: http.ServerResponse, u: URL, d: LocalDeps): Promise<boolean> {
  const p = u.pathname;
  const tz = d.scheduler.timezone;
  if (p === '/send' && req.method === 'POST') {
    const b = await readBody(req, 64 << 10);
    if (typeof b.channel !== 'string' || !b.channel) throw new Error('name a channel');
    if (!b.file && !(typeof b.text === 'string' && b.text.trim())) throw new Error('nothing to send');
    return reply(res, 200, `sent on ${await d.send({ channel: b.channel, text: b.text, file: b.file, caption: b.caption, bot: b.bot, by: String(b.by || 'someone on the box') })}`), true;
  }
  const log = p.match(/^\/channels\/([\w-]+)\/log$/);
  if (log && req.method === 'GET') {
    const since = Number(u.searchParams.get('since')) || undefined, limit = Number(u.searchParams.get('limit')) || undefined;
    return reply(res, 200, { entries: readChannel(log[1], { since, limit }) }), true;
  }
  if (!p.startsWith('/schedules')) return false;
  const s = d.scheduler;
  if (p === '/schedules' && req.method === 'GET') return reply(res, 200, { timezone: tz, schedules: s.views() }), true;
  if (p === '/schedules' && req.method === 'POST') {
    const b = await readBody(req);
    const name = String(b.name ?? '');
    store.checkName(name);
    if (store.load(name)) return reply(res, 409, `schedules: "${name}" exists (change it with otto schedule edit ${name})`), true;
    store.save(name, shaped(b, tz));
    return reply(res, 200, s.view(name)), true;
  }
  const m = p.match(/^\/schedules\/([\w-]+)(?:\/(run|runs)(?:\/([\w-]+))?)?$/);
  if (!m) return false;
  const [, name, sub, id] = m;
  if (sub === 'runs' && req.method === 'GET') {
    if (id) {
      const run = store.runs(name).find(r => r.id === id);
      if (!run) return reply(res, 404, `schedules: no run ${id} of "${name}"`), true;
      return reply(res, 200, { run, steps: steps(name, id) }), true;
    }
    const limit = Number(u.searchParams.get('limit')) || 20;
    const all = name === '-' ? store.withRuns().flatMap(n => store.runs(n)) : store.runs(name);
    return reply(res, 200, { runs: all.sort((a, b) => b.started.localeCompare(a.started)).slice(0, limit) }), true;
  }
  const was = name === '-' ? null : store.load(name);
  if (!was) return reply(res, 404, `schedules: no schedule "${name}" (otto schedule list)`), true;
  if (sub === 'run' && req.method === 'POST') return reply(res, 200, { run: s.runNow(name) }), true;
  if (!sub && req.method === 'PATCH') {
    store.save(name, shaped(await readBody(req), tz, was));
    return reply(res, 200, s.view(name)), true;
  }
  if (!sub && req.method === 'DELETE') {
    store.remove(name);
    return reply(res, 200, 'deleted'), true;
  }
  if (!sub && req.method === 'GET') return reply(res, 200, s.view(name)), true;
  return false;
}

