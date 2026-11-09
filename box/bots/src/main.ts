#!/usr/bin/env node
// otto-bots: runs every bot configured in ~/.config/otto/bots/ (config.ts), each one a chat channel to one
// long-running agent conversation (core.ts). On SIGHUP it reads the configs again: new bots start, removed ones
// stop, and changed ones restart (the `otto bot` commands send it after a change). Each bot logs to the journal
// and to its own log file, which `otto bot logs` reads.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Channel } from './channel.js';
import { Telegram } from './channels/telegram.js';
import { type BotConfig, loadAll, pidFile, stateDir, stateRoot, update } from './config.js';
import { Bot } from './core.js';
import type { Provider } from './provider.js';
import { claude } from './providers/claude.js';

const CHANNELS: Record<string, (token: string, log: (m: string) => void) => Channel> = {
  telegram: (token, log) => new Telegram(token, log),
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

interface Running { bot: Bot | null; config: BotConfig; retry: NodeJS.Timeout | null }
const bots = new Map<string, Running>();

function run(name: string, config: BotConfig) {
  const log = logger(name);
  const r: Running = { bot: null, config, retry: null };
  bots.set(name, r);
  const channel = CHANNELS[config.channel];
  const provider = PROVIDERS[config.agent ?? 'claude'];
  if (!channel) return log(`unknown channel "${config.channel}" (known: ${Object.keys(CHANNELS).join(', ')})`);
  if (!provider) return log(`unknown agent "${config.agent}" (known: ${Object.keys(PROVIDERS).join(', ')})`);
  const bot = new Bot({
    name, config, provider,
    channel: channel(config.token, log),
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

fs.mkdirSync(stateRoot(), { recursive: true, mode: 0o700 });
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
