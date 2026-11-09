// The bots' config and state. Each bot is one file, ~/.config/otto/bots/<name>.json (0600: it holds the channel's
// token), which the bot itself also writes to as it goes: who its owner is, and its conversation's session id.
// What a bot keeps besides (its tools' socket, files the user sent, its log, its pairing code) lives under
// ~/.local/state/otto/bots/<name>/. OTTO_BOTS_CONFIG and OTTO_BOTS_STATE point elsewhere, e.g. for tests.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export interface BotConfig {
  /** Where the bot is reached: only "telegram" so far. */
  channel: string;
  /** The channel's bot token. */
  token: string;
  /** The channel's id for the one account the bot answers; unset until someone pairs. */
  owner?: string | number;
  /** The agent's conversation, resumed when the bot restarts; unset for a new one. */
  session?: string | null;
  /** The folder the agent works in (default ~/code). */
  cwd?: string;
  /** The agent's permission mode (default auto). */
  permissionMode?: string;
  model?: string;
  /** The agent the bot is a conversation with (default claude, the only one so far). */
  agent?: string;
}

const home = os.homedir();
const expand = (p: string) => p.replace(/^~(?=\/|$)/, home);

export const configDir = () => expand(process.env.OTTO_BOTS_CONFIG || '~/.config/otto/bots');
export const stateRoot = () => expand(process.env.OTTO_BOTS_STATE || '~/.local/state/otto/bots');
export const stateDir = (name: string) => path.join(stateRoot(), name);
export const configFile = (name: string) => path.join(configDir(), `${name}.json`);
/** The service's pid, for the CLI to signal it after a change. */
export const pidFile = () => path.join(stateRoot(), 'service.pid');

export const NAME = /^[a-z0-9][a-z0-9_-]{0,31}$/i;

/** Every bot's config, by name. A file that doesn't parse is reported and skipped, so one bad bot can't stop the rest. */
export function loadAll(warn: (m: string) => void = console.error): Map<string, BotConfig> {
  const bots = new Map<string, BotConfig>();
  let files: string[] = [];
  try { files = fs.readdirSync(configDir()); } catch (e: any) {
    if (e.code !== 'ENOENT') throw e;
  }
  for (const f of files.sort()) {
    const name = f.replace(/\.json$/, '');
    if (!f.endsWith('.json') || !NAME.test(name)) continue;
    try {
      const c = JSON.parse(fs.readFileSync(path.join(configDir(), f), 'utf8'));
      if (typeof c.channel !== 'string' || typeof c.token !== 'string') throw new Error('needs a channel and a token');
      bots.set(name, c);
    } catch (e: any) {
      warn(`${name}: ${e.message}`);
    }
  }
  return bots;
}

export function load(name: string): BotConfig {
  return JSON.parse(fs.readFileSync(configFile(name), 'utf8'));
}

/** Writes the whole config, readable only by this user, through a rename so a reader never sees half a file. */
export function save(name: string, c: BotConfig): void {
  fs.mkdirSync(configDir(), { recursive: true, mode: 0o700 });
  writePrivate(configFile(name), JSON.stringify(c, null, 2) + '\n');
}

/** Changes some fields in the file as it is now (the CLI may have changed others meanwhile), and returns the result. */
export function update(name: string, patch: Partial<BotConfig>): BotConfig {
  const c = { ...load(name), ...patch };
  save(name, c);
  return c;
}

export function writePrivate(file: string, text: string): void {
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, text, { mode: 0o600 });
  fs.renameSync(tmp, file);
}
