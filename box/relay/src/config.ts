// The box's Otto config, ~/.config/otto/config.toml (see install/config.example.toml). Every value has a default,
// so a box with no config file runs with today's ports on its own tailnet address.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { parse } from 'smol-toml';

export interface Config {
  /** The box's tailnet address: where the phone dials in. */
  tailnetHost: string;
  /** The browser link: control connection and DevTools pipes (the app's BrowserLink). */
  browserPort: number;
  pipePort: number;
  /** The device link (the device agent's connection). */
  devicePort: number;
  /** The local API: CLI, eyes MCP server, Playwright. */
  localPort: number;
  browserToken: string;
  deviceToken: string;
  /** Where the relay keeps which group is whose. */
  stateFile: string;
}

const home = os.homedir();
const expand = (p: string) => p.replace(/^~(?=\/|$)/, home);

export function configPath(): string {
  return process.env.OTTO_CONFIG || path.join(home, '.config/otto/config.toml');
}

export function loadConfig(file = configPath()): Config {
  let t: Record<string, any> = {};
  try { t = parse(fs.readFileSync(file, 'utf8')) as Record<string, any>; } catch (e: any) {
    if (e.code !== 'ENOENT') throw new Error(`${file}: ${e.message}`);
  }
  const relay = t.relay ?? {}, phone = t.phone ?? {};
  const token = (f: string, what: string) => {
    try { return fs.readFileSync(expand(f), 'utf8').trim(); } catch {
      throw new Error(`no ${what} token at ${f} (set phone.${what}_token_file in ${file})`);
    }
  };
  return {
    tailnetHost: process.env.OTTO_TAILNET_HOST || relay.tailnet_host || tailnetAddress(),
    browserPort: relay.browser_port ?? 9401,
    pipePort: relay.pipe_port ?? 9402,
    devicePort: relay.device_port ?? 9411,
    localPort: relay.local_port ?? 9334,
    // Until the app pairs with one token, it has one per link (the two apps it was made from).
    browserToken: token(phone.browser_token_file ?? '~/.config/otto/browser-token', 'browser'),
    deviceToken: token(phone.device_token_file ?? '~/.config/otto/device-token', 'device'),
    stateFile: expand(relay.state_file ?? '~/.local/state/otto/relay.json'),
  };
}

/** This machine's Tailscale IPv4 address (100.64.0.0/10), or loopback when it isn't on a tailnet. */
function tailnetAddress(): string {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list ?? []) {
      if (a.family !== 'IPv4') continue;
      const [x, y] = a.address.split('.').map(Number);
      if (x === 100 && y >= 64 && y < 128) return a.address;
    }
  }
  return '127.0.0.1';
}
