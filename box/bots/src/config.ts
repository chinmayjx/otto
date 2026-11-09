// The bots' config and state. Each bot is one file, ~/.config/otto/bots/<name>.json (0600: it holds its channels'
// tokens and keys), which the bot itself also writes to as it goes: who owns each channel, and its conversation's
// session id. What a bot keeps besides (its tools' socket, files the user sent, its log, its pairing code) lives under
// ~/.local/state/otto/bots/<name>/. OTTO_BOTS_CONFIG and OTTO_BOTS_STATE point elsewhere, e.g. for tests.
//
// A bot is one conversation with any number of channels, by kind:
//   {"agent": "claude", "session": "…", "channels": {"telegram": {"token": "…", "owner": 123},
//                                                    "otto_voice": {"provider": "openai-realtime", "apiKey": "…", "auth"?: "api-key"|"codex"}}}
// The first shape had one channel at the top ({"channel": "telegram", "token", "owner"}); it's read as the new one,
// and written in the new one at the next save.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/** One channel's settings: Telegram's token and owner, the voice's provider and key, … */
export interface ChannelConfig {
  /** The channel's token (Telegram). */
  token?: string;
  /** The channel's id for the one account the bot answers there; unset until someone pairs. */
  owner?: string | number;
  [k: string]: unknown;
}

export interface BotConfig {
  /** The bot's channels, by kind ("telegram", "otto_voice"). */
  channels: Record<string, ChannelConfig>;
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

/** The voice channel: one per box, on the bot that is the user's assistant. */
export const VOICE = 'otto_voice';

const home = os.homedir();
export const expand = (p: string) => p.replace(/^~(?=\/|$)/, home);

export const configDir = () => expand(process.env.OTTO_BOTS_CONFIG || '~/.config/otto/bots');
export const stateRoot = () => expand(process.env.OTTO_BOTS_STATE || '~/.local/state/otto/bots');
export const stateDir = (name: string) => path.join(stateRoot(), name);
export const configFile = (name: string) => path.join(configDir(), `${name}.json`);
/** Otto's state folder (the bots' folder's parent): channels' logs and schedules' runs live there too. */
export const ottoState = () => path.dirname(stateRoot());
/** The schedules' configs, next to the bots' (~/.config/otto/schedules/<name>.json). */
export const schedulesDir = () => path.join(path.dirname(configDir()), 'schedules');
/** The service's pid, for the CLI to signal it after a change. */
export const pidFile = () => path.join(stateRoot(), 'service.pid');

export const NAME = /^[a-z0-9][a-z0-9_-]{0,31}$/i;

/** A config in either shape, in the current one. */
export function normalize(c: any): BotConfig {
  if (!c || typeof c !== 'object') throw new Error('not a JSON object');
  if (typeof c.channel === 'string') {
    const { channel, token, owner, ...rest } = c;
    c = { ...rest, channels: { ...(c.channels ?? {}), [channel]: { token, ...(owner !== undefined ? { owner } : {}) } } };
  }
  // No channels is allowed: a bot whose only channel (the voice) moved to another bot keeps its conversation.
  if (!c.channels || typeof c.channels !== 'object') throw new Error('needs a channels object');
  return c;
}

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
      bots.set(name, normalize(JSON.parse(fs.readFileSync(path.join(configDir(), f), 'utf8'))));
    } catch (e: any) {
      warn(`${name}: ${e.message}`);
    }
  }
  return bots;
}

export function load(name: string): BotConfig {
  return normalize(JSON.parse(fs.readFileSync(configFile(name), 'utf8')));
}

/** Writes the whole config, readable only by this user, through a rename so a reader never sees half a file. */
export function save(name: string, c: BotConfig): void {
  fs.mkdirSync(configDir(), { recursive: true, mode: 0o700 });
  writePrivate(configFile(name), JSON.stringify(c, null, 2) + '\n');
}

/** Changes some fields in the file as it is now (the CLI may have changed others meanwhile), and returns the result.
 * `channels` in the patch is merged channel by channel, field by field. */
export function update(name: string, patch: Partial<BotConfig>): BotConfig {
  const now = load(name);
  const channels = { ...now.channels };
  for (const [k, v] of Object.entries(patch.channels ?? {})) channels[k] = { ...channels[k], ...v };
  const c = { ...now, ...patch, channels };
  save(name, c);
  return c;
}

export function writePrivate(file: string, text: string): void {
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, text, { mode: 0o600 });
  fs.renameSync(tmp, file);
}
