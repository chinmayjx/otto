// `otto bot`: the bots that box/bots runs (the otto-bots service). A bot is a config file the CLI writes, readable
// only by this user since it holds the channel's token; the service reads it, and tells the CLI how a bot is doing
// through the bot's status file (its name on the channel, and its pairing code while it has no owner). After a
// change the CLI sends the service SIGHUP, so bots start and stop without a restart.
import dns from 'node:dns';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { OttoError } from './lib.js';

const home = os.homedir();
const expand = (p: string) => p.replace(/^~(?=\/|$)/, home);
const configDir = () => expand(process.env.OTTO_BOTS_CONFIG || '~/.config/otto/bots');
const stateRoot = () => expand(process.env.OTTO_BOTS_STATE || '~/.local/state/otto/bots');
const configFile = (name: string) => path.join(configDir(), `${name}.json`);
const stateDir = (name: string) => path.join(stateRoot(), name);
const NAME = /^[a-z0-9][a-z0-9_-]{0,31}$/i;
const CHANNELS = ['telegram'];

type Out = (s: string) => void;

interface Status { handle: string | null; pid: number; owned: boolean; pair: { code: string; link: string | null } | null }

function readJson(file: string): any {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; }
}

function config(name: string): any {
  if (!NAME.test(name)) throw new OttoError(`a bot's name is letters, digits, - and _ (got "${name}")`);
  const c = readJson(configFile(name));
  if (!c) throw new OttoError(`no bot "${name}" (otto bot list)`);
  return c;
}

function save(name: string, c: unknown) {
  fs.mkdirSync(configDir(), { recursive: true, mode: 0o700 });
  const file = configFile(name), tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(c, null, 2) + '\n', { mode: 0o600 });
  fs.renameSync(tmp, file);
}

/** The service's pid, when it's running. */
function service(): number | null {
  const pid = Number(readJson(path.join(stateRoot(), 'service.pid')));
  if (!pid) return null;
  try {
    const cmd = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8');
    return /bots\/dist\/main\.js|otto-bots/.test(cmd) ? pid : null;
  } catch { return null; }
}

/** Tells the service to read the configs again. False when it isn't running. */
function reload(): boolean {
  const pid = service();
  if (!pid) return false;
  process.kill(pid, 'SIGHUP');
  return true;
}

/** The bot's status from the service, when it's running. */
function status(name: string): Status | null {
  const s: Status | null = readJson(path.join(stateDir(name), 'status.json'));
  return s && service() === s.pid ? s : null;
}

/** Waits for the service to have the bot running and, when `pairing`, to have a pairing code for it. */
async function waitFor(name: string, pairing: boolean, ms = 15_000): Promise<Status | null> {
  for (const end = Date.now() + ms; Date.now() < end; await new Promise(r => setTimeout(r, 300))) {
    const s = status(name);
    if (s && (!pairing || s.pair)) return s;
  }
  return null;
}

const NOT_RUNNING = 'The otto-bots service isn\'t running: install and start it (install/otto-bots.service), then';

function printPairing(out: Out, name: string, s: Status) {
  out(`${name} is ${s.handle ?? 'running'}. To pair it with your account, open this link and press Start:\n  ${s.pair!.link ?? '(no link on this channel)'}\nor send the bot: /start ${s.pair!.code}`);
}

/** The token, from stdin when it's piped in, otherwise typed at a prompt without echo. Never from argv. */
async function readToken(prompt: string): Promise<string> {
  const stdin = process.stdin;
  if (!stdin.isTTY) {
    let s = '';
    for await (const d of stdin) s += d;
    return s.trim();
  }
  process.stderr.write(prompt);
  stdin.setRawMode(true);
  stdin.setEncoding('utf8');
  stdin.resume();
  return new Promise((resolve, reject) => {
    let s = '';
    const done = (fn: () => void) => { stdin.setRawMode(false); stdin.pause(); stdin.off('data', on); process.stderr.write('\n'); fn(); };
    const on = (d: string) => {
      for (const ch of d) {
        if (ch === '\r' || ch === '\n') return done(() => resolve(s.trim()));
        if (ch === '\u0003') return done(() => reject(new OttoError('cancelled')));
        if (ch === '\u007f' || ch === '\b') s = s.slice(0, -1);
        else s += ch;
      }
    };
    stdin.on('data', on);
  });
}

/** Checks a Telegram token with getMe: the bot's username. */
async function telegramMe(token: string): Promise<string> {
  // As in the service: IPv4 only (see box/bots/src/channels/telegram.ts).
  dns.setDefaultResultOrder('ipv4first');
  net.setDefaultAutoSelectFamily(false);
  if (!/^\d+:[\w-]+$/.test(token)) throw new OttoError('that doesn\'t look like a Telegram bot token (from @BotFather: 123456:ABC…)');
  let j: any;
  try { j = await (await fetch(`https://api.telegram.org/bot${token}/getMe`)).json(); } catch (e: any) {
    throw new OttoError(`couldn't reach Telegram: ${e.cause?.message ?? e.message}`);
  }
  if (!j.ok) throw new OttoError(`Telegram refused the token: ${j.description}`);
  return j.result.username;
}

export async function bot(cmd: string | undefined, a: { _: string[]; [k: string]: any }, out: Out): Promise<void> {
  const name = a._[0];
  switch (cmd) {
    case 'add': {
      if (!name) throw new OttoError('missing a name: otto bot add <name> --channel telegram');
      if (!NAME.test(name)) throw new OttoError('a bot\'s name is letters, digits, - and _');
      if (fs.existsSync(configFile(name))) throw new OttoError(`there is already a bot "${name}" (otto bot rm ${name} first)`);
      const channel = a.channel ?? 'telegram';
      if (!CHANNELS.includes(channel)) throw new OttoError(`unknown channel "${channel}" (known: ${CHANNELS.join(', ')})`);
      const token = await readToken('Bot token from @BotFather (not shown): ');
      if (!token) throw new OttoError('no token');
      const username = await telegramMe(token);
      const c: Record<string, unknown> = { channel, token };
      if (a.cwd) c.cwd = path.resolve(expand(a.cwd));
      if (a.model) c.model = a.model;
      if (a['permission-mode']) c.permissionMode = a['permission-mode'];
      save(name, c);
      out(`Added ${name}: @${username}, working in ${c.cwd ?? '~/code'}.`);
      if (!reload()) return out(`${NOT_RUNNING} otto bot pair ${name}.`);
      const s = await waitFor(name, true);
      return s ? printPairing(out, name, s) : out(`The service hasn't started it yet: see otto bot logs ${name}, then otto bot pair ${name}.`);
    }

    case 'list': case 'ls': {
      let files: string[] = [];
      try { files = fs.readdirSync(configDir()).filter(f => f.endsWith('.json')).sort(); } catch {}
      if (!files.length) return out('no bots (otto bot add <name> --channel telegram)');
      const rows = files.map(f => {
        const n = f.replace(/\.json$/, '');
        const c = readJson(path.join(configDir(), f)) ?? {};
        const s = status(n);
        return [n, c.channel ?? '?', s?.handle ?? '', s ? 'running' : 'stopped', c.owner ? 'paired' : 'unpaired',
          c.session ? `session ${String(c.session).slice(0, 8)}` : 'new session', (c.cwd ?? '~/code').replace(home, '~')];
      });
      const widths = rows[0].map((_, i) => Math.max(...rows.map(r => r[i].length)));
      return out(rows.map(r => r.map((x, i) => x.padEnd(widths[i])).join('  ').trimEnd()).join('\n'));
    }

    case 'rm': case 'remove': {
      if (!name) throw new OttoError('missing a name: otto bot rm <name>');
      config(name);
      fs.rmSync(configFile(name));
      reload();
      return out(`Removed ${name}. Its log and the files it was sent stay in ${stateDir(name).replace(home, '~')}.`);
    }

    case 'pair': {
      if (!name) throw new OttoError('missing a name: otto bot pair <name> [--reset]');
      const c = config(name);
      if (c.owner && !a.reset) return out(`${name} is paired (owner ${c.owner}). otto bot pair ${name} --reset pairs another account; the current one loses access.`);
      if (c.owner) { delete c.owner; save(name, c); }
      if (!reload()) return out(`${NOT_RUNNING} run otto bot pair ${name} again.`);
      const s = await waitFor(name, true);
      return s ? printPairing(out, name, s) : out(`No pairing code yet: see otto bot logs ${name}.`);
    }

    case 'logs': case 'log': {
      if (!name) throw new OttoError('missing a name: otto bot logs <name> [--lines N] [--follow]');
      config(name);
      const file = path.join(stateDir(name), 'bot.log');
      if (!fs.existsSync(file)) return out(`no log yet (${file})`);
      const r = spawnSync('tail', ['-n', String(a.lines ?? 50), ...(a.follow ? ['-F'] : []), file], { stdio: 'inherit' });
      process.exitCode = r.status ?? 0;
      return;
    }
  }
  throw new OttoError(`unknown command: otto bot${cmd ? ` ${cmd}` : ''} (see otto help)`);
}
