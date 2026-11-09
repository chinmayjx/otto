#!/usr/bin/env node
// otto-bots: runs every bot configured in ~/.config/otto/bots/ (config.ts), each one long-running agent conversation
// (core.ts) with its channels: Telegram, and on one bot, the user's assistant, Otto's voice (channels/voice), whose
// server for the phone this also runs (on [voice] port of ~/.config/otto/config.toml, OTTO_CONFIG). On SIGHUP it reads the configs again: new bots start, removed ones
// stop, and changed ones restart (the `otto bot` commands send it after a change). Each bot logs to the journal
// and to its own log file, which `otto bot logs` reads.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import os from 'node:os';
import { parse } from 'smol-toml';
import type { Channel } from './channel.js';
import { Telegram } from './channels/telegram.js';
import { Voice } from './channels/voice/index.js';
import { notify, serveVoice } from './channels/voice/server.js';
import { type BotConfig, configFile, load, loadAll, pidFile, save, stateDir, stateRoot, update, VOICE } from './config.js';
import { Bot } from './core.js';
import type { Provider } from './provider.js';
import { claude } from './providers/claude.js';

// Otto's own config: the voice server's port, and the relay's local API (for phone notifications).
let otto: Record<string, any> = {};
try { otto = parse(fs.readFileSync(process.env.OTTO_CONFIG || path.join(os.homedir(), '.config/otto/config.toml'), 'utf8')); } catch (e: any) {
  if (e.code !== 'ENOENT') console.error(`config: ${e.message}`);
}
const VOICE_PORT: number = otto.voice?.port ?? 9336;
const RELAY_PORT: number = otto.relay?.local_port ?? 9334;

const CHANNELS: Record<string, (c: BotConfig['channels'][string], log: (m: string) => void) => Channel> = {
  telegram: (c, log) => new Telegram(String(c.token), log),
  [VOICE]: (c, log) => new Voice(c, { notify: text => notify(RELAY_PORT, text), log }),
};
const PROVIDERS: Record<string, Provider> = { claude };
const TOOLS = path.join(path.dirname(fileURLToPath(import.meta.url)), 'tools.js');
const LOG_MAX = 1024 * 1024;

/** A bot's log: a line on stdout (the journal), and in its own file, which rolls over to bot.log.1 at 1 MB. */
function logger(name: string): (m: string) => void {
  const file = path.join(stateDir(name), 'bot.log');
  return m => {
    console.log(`[${name}] ${m}`);
    try {
      fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
      if ((fs.statSync(file, { throwIfNoEntry: false })?.size ?? 0) > LOG_MAX) fs.renameSync(file, `${file}.1`);
      fs.appendFileSync(file, `${new Date().toISOString()} ${m}\n`, { mode: 0o600 });
    } catch {}
  };
}

/** What, when it changes, means a restart: everything but the session, which the bot writes as it goes. */
const shape = (c: BotConfig) => JSON.stringify({ ...c, session: undefined });

interface Running { bot: Bot | null; config: BotConfig; retry: NodeJS.Timeout | null; voice: Voice | null }
const bots = new Map<string, Running>();

/** The bot with the voice channel, if one is running with it. */
function assistant() {
  for (const [name, r] of bots) if (r.voice) return { bot: name, agent: PROVIDERS[r.config.agent ?? 'claude']?.label ?? '?', voice: r.voice };
  return null;
}

function run(name: string, config: BotConfig) {
  const log = logger(name);
  const r: Running = { bot: null, config, retry: null, voice: null };
  bots.set(name, r);
  const provider = PROVIDERS[config.agent ?? 'claude'];
  if (!provider) return log(`unknown agent "${config.agent}" (known: ${Object.keys(PROVIDERS).join(', ')})`);
  const channels: Channel[] = [];
  for (const [kind, c] of Object.entries(config.channels)) {
    const make = CHANNELS[kind];
    if (!make) { log(`unknown channel "${kind}" (known: ${Object.keys(CHANNELS).join(', ')})`); continue; }
    if (kind === VOICE) {
      const other = assistant();
      if (other) { log(`not taking the voice channel: ${other.bot} has it (only one bot can be the assistant)`); continue; }
    }
    try {
      const ch = make(c, log);
      channels.push(ch);
      if (ch instanceof Voice) r.voice = ch;
    } catch (e: any) {
      log(`${kind}: ${e.message}`);
    }
  }
  if (!channels.length) return log('no channel to run');
  const bot = new Bot({
    name, config, provider, channels,
    stateDir: stateDir(name),
    tools: { command: process.execPath, args: [TOOLS] },
    save: patch => (r.config = update(name, patch)),
    log,
  });
  r.bot = bot;
  // A bot that can't start (the network isn't up yet, Telegram is down) tries again in a while.
  bot.start().catch(e => {
    log(`can't start: ${e.message}; trying again in 30s`);
    bot.stop();
    r.retry = setTimeout(() => { if (bots.get(name) === r) run(name, r.config); }, 30_000);
  });
}

function halt(name: string) {
  const r = bots.get(name);
  if (!r) return;
  bots.delete(name);
  if (r.retry) clearTimeout(r.retry);
  r.bot?.stop();
}

function reload() {
  const configs = loadAll(m => console.error(`config ${m}`));
  for (const name of [...bots.keys()]) {
    const c = configs.get(name);
    if (!c) { console.log(`[${name}] removed`); halt(name); }
    else if (shape(c) !== shape(bots.get(name)!.config)) { console.log(`[${name}] changed: restarting`); halt(name); }
  }
  for (const [name, c] of configs) if (!bots.has(name)) run(name, c);
  if (!configs.size) console.log('no bots configured (otto bot add <name>)');
}

/** Makes a bot the assistant: the voice channel's config moves to it from the bot that has it. */
async function assign(name: string) {
  const configs = loadAll();
  const to = configs.get(name);
  if (!to) throw new Error(`no bot "${name}"`);
  if (to.channels[VOICE]) return;
  const from = [...configs].find(([, c]) => c.channels[VOICE]);
  if (!from) throw new Error('no bot has a voice yet: set one up on the box with otto bot voice <name>');
  const [fromName, fromConfig] = from;
  const voice = fromConfig.channels[VOICE];
  const rest = { ...fromConfig.channels };
  delete rest[VOICE];
  save(name, { ...load(name), channels: { ...load(name).channels, [VOICE]: voice } });
  if (Object.keys(rest).length) save(fromName, { ...load(fromName), channels: rest });
  else fs.rmSync(configFile(fromName)); // a bot that was only a voice: nothing left of it
  console.log(`the assistant is now ${name} (was ${fromName})`);
  reload();
}

fs.mkdirSync(stateRoot(), { recursive: true, mode: 0o700 });
serveVoice(VOICE_PORT, { assistant, bots: () => [...loadAll(() => {}).keys()], assign, log: m => console.log(m) });
fs.writeFileSync(pidFile(), String(process.pid));
process.on('SIGHUP', () => { console.log('reloading'); reload(); });
for (const sig of ['SIGTERM', 'SIGINT'] as const) {
  process.on(sig, () => {
    for (const name of [...bots.keys()]) halt(name);
    fs.rmSync(pidFile(), { force: true });
    process.exit(0);
  });
}
reload();
