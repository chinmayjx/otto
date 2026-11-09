// `otto send`, `otto channel` and `otto schedule`: thin clients of otto-bots' local API (box/bots local.ts), which
// holds the channels' tokens, runs the schedules and keeps their files.
import fs from 'node:fs';
import path from 'node:path';
import { bots, type Config, OttoError, session } from './lib.js';

type Out = (s: string) => void;
interface Args { _: string[]; [k: string]: any }

/** Who is sending, for the channel's log: a scheduled run, else this session, else just the CLI. */
function sender(config: Config): string {
  if (process.env.OTTO_SENDER) return process.env.OTTO_SENDER;
  const s = session(config);
  return s.id === 'default' ? 'otto send' : `session ${s.title || s.id}`;
}

function readStdin(): string {
  if (process.stdin.isTTY) return '';
  try { return fs.readFileSync(0, 'utf8'); } catch { return ''; }
}

export async function send(config: Config, a: Args, out: Out) {
  const [channel, ...words] = a._;
  if (!channel) throw new OttoError('missing a channel: otto send telegram|otto_voice|notify "text" (or --file F [--caption C])');
  const text = words.length ? words.join(' ') : a.file ? undefined : readStdin().trim();
  if (!a.file && !text) throw new OttoError('nothing to send: give the text, or --file');
  const file = a.file ? path.resolve(String(a.file)) : undefined;
  if (file && !fs.existsSync(file)) throw new OttoError(`no file ${file}`);
  out(await (await bots(config, 'POST', '/send', { channel, text, file, caption: a.caption, bot: a.bot, by: sender(config) })).text());
}

/** "30m", "2h", "1d" (or plain minutes) as milliseconds. */
function span(s: string): number {
  const m = /^(\d+(?:\.\d+)?)\s*([mhd]?)$/.exec(String(s).trim());
  if (!m) throw new OttoError(`not a length of time: "${s}" (e.g. 30m, 2h, 1d)`);
  return Number(m[1]) * { '': 60_000, m: 60_000, h: 3600_000, d: 86400_000 }[m[2] as '' | 'm' | 'h' | 'd'];
}

const time = (config: Config, iso: string | null | undefined) => iso
  ? new Intl.DateTimeFormat('en-GB', { timeZone: config.timezone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso))
  : '-';

export async function channel(config: Config, cmd: string | undefined, a: Args, out: Out) {
  if (cmd !== 'log') throw new OttoError('otto channel log <channel> [--since 2h] [--lines N]');
  const kind = a._[0];
  if (!kind) throw new OttoError('missing a channel: otto channel log telegram|otto_voice|notify');
  const q = new URLSearchParams({ limit: String(Number(a.lines) || 30) });
  if (a.since) q.set('since', String(Date.now() - span(a.since)));
  const { entries } = await (await bots(config, 'GET', `/channels/${encodeURIComponent(kind)}/log?${q}`)).json();
  if (a.json) return out(JSON.stringify(entries, null, 2));
  out(entries.map((e: any) => `${time(config, e.at)}  ${e.dir === 'in' ? '←' : '→'} ${e.by}: ${[e.file && `[file ${e.file}]`, e.text].filter(Boolean).join(' ')}`).join('\n')
    || `nothing on ${kind}${a.since ? ` in the last ${a.since}` : ''}`);
}

/** A schedule's fields from the flags: --prompt or --prompt-file, the timing, and the rest. */
function fields(a: Args): Record<string, unknown> {
  const f: Record<string, unknown> = {};
  if (a['prompt-file'] !== undefined) {
    try { f.prompt = fs.readFileSync(String(a['prompt-file']), 'utf8'); } catch (e: any) { throw new OttoError(`can't read ${a['prompt-file']}: ${e.message}`); }
  } else if (a.prompt !== undefined) f.prompt = String(a.prompt);
  for (const k of ['at', 'every', 'cron', 'once', 'agent', 'cwd', 'model']) if (a[k] !== undefined) f[k] = String(a[k]);
  if (f.cwd) f.cwd = path.resolve(String(f.cwd));
  if (a.timeout !== undefined) f.timeout = a.timeout === '' ? null : span(a.timeout) / 60_000;
  return f;
}

function line(config: Config, s: any): string {
  const last = s.running ? 'running now' : s.last ? `last ${s.last.status} ${time(config, s.last.started)}` : 'never run';
  return `${s.name}  ${s.description}${s.paused ? '  (paused)' : `  next ${time(config, s.next)}`}  ${last}`;
}

function detail(config: Config, s: any): string {
  return [
    `${s.name}: ${s.description} (${config.timezone})${s.paused ? ', paused' : ''}`,
    `next:    ${s.paused ? 'paused' : time(config, s.next)}`,
    `agent:   ${s.agent ?? 'claude'}${s.model ? ` (${s.model})` : ''}, in ${s.cwd || '~/code'}, up to ${s.timeout ?? 30} min`,
    `last:    ${s.running ? 'running now' : s.last ? `${s.last.status} ${time(config, s.last.started)} (run ${s.last.id})` : 'never run'}`,
    '', s.prompt.trimEnd(),
  ].join('\n');
}

function runLine(config: Config, r: any): string {
  return [`${r.id}  ${r.schedule}  ${time(config, r.started)}  ${r.status}${r.trigger === 'now' ? ' (run now)' : ''}`,
    r.transcript ? `  transcript: ${r.transcript}` : r.session ? `  session: ${r.session}` : '',
    r.result ? `  ${String(r.result).split('\n')[0].slice(0, 160)}` : ''].filter(Boolean).join('\n');
}

export async function schedule(config: Config, cmd: string | undefined, a: Args, out: Out) {
  const name = a._[0];
  const need = () => { if (!name) throw new OttoError(`missing a name: otto schedule ${cmd} <name>`); return encodeURIComponent(name); };
  const api = async (method: string, p: string, body?: unknown) => (await bots(config, method, p, body)).json();
  switch (cmd) {
    case undefined: case 'list': case 'ls': {
      const { schedules } = await api('GET', '/schedules');
      return out(schedules.map((s: any) => line(config, s)).join('\n') || 'no schedules (otto schedule add <name> --at HH:MM --prompt "…")');
    }
    case 'add': {
      const f = fields(a);
      if (!f.prompt) throw new OttoError('a schedule needs --prompt "…" or --prompt-file F');
      return out(detail(config, await api('POST', '/schedules', { name: (need(), name), ...f })));
    }
    case 'edit': {
      const f = fields(a);
      if (!Object.keys(f).length) throw new OttoError('nothing to change: give --prompt, --at/--every/--cron/--once, --agent, --cwd, --model or --timeout');
      return out(detail(config, await api('PATCH', `/schedules/${need()}`, f)));
    }
    case 'show': return out(detail(config, await api('GET', `/schedules/${need()}`)));
    case 'pause': case 'resume':
      await api('PATCH', `/schedules/${need()}`, { paused: cmd === 'pause' });
      return out(cmd === 'pause' ? `${name} paused` : `${name} resumed`);
    case 'rm': case 'delete':
      await bots(config, 'DELETE', `/schedules/${need()}`);
      return out(`${name} deleted (its runs are kept: otto schedule runs ${name})`);
    case 'run': {
      const { run } = await api('POST', `/schedules/${need()}/run`);
      return out(run.status === 'running' ? `run ${run.id} started: otto schedule runs ${name} ${run.id}` : runLine(config, run));
    }
    case 'runs': {
      if (name && a._[1]) {
        const { run, steps } = await api('GET', `/schedules/${need()}/runs/${encodeURIComponent(a._[1])}`);
        return out([runLine(config, run), '', ...steps.map((s: any) => (s.kind === 'tool' ? `  ${s.text}` : s.kind === 'result' ? `= ${s.text}` : s.text))].join('\n'));
      }
      const { runs } = await api('GET', `/schedules/${name ? need() : '-'}/runs?limit=${Number(a.limit) || 10}`);
      return out(runs.map((r: any) => runLine(config, r)).join('\n') || 'no runs yet');
    }
  }
  throw new OttoError(`unknown command: otto schedule ${cmd} (see otto help)`);
}
