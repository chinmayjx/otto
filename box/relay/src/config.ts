// The box's Otto config, ~/.config/otto/config.toml (see install/config.example.toml). Every value has a default,
// so a box with no config file runs with today's ports on its own tailnet address.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { parse } from 'smol-toml';

export interface Config {
  /** The box's tailnet address: where the phone dials in. */
  tailnetHost: string;
  /** The phone's tunnel: its control connection, and its pipes (tunnel.ts). */
  tunnelPort: number;
  pipePort: number;
  /** The local API: CLI, eyes MCP server, Playwright. */
  localPort: number;
  /** What the app presents on the tunnel (given to it at pairing). */
  token: string;
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
  const token = (f: string) => {
    try { return fs.readFileSync(expand(f), 'utf8').trim(); } catch {
      throw new Error(`no token at ${f} (set phone.token_file in ${file})`);
    }
  };
  return {
    tailnetHost: process.env.OTTO_TAILNET_HOST || relay.tailnet_host || tailnetAddress(),
    tunnelPort: relay.tunnel_port ?? relay.browser_port ?? 9401,
    pipePort: relay.pipe_port ?? 9402,
    localPort: relay.local_port ?? 9334,
    // One token for the app. Before the tunnel it had one per link; the browser link's carries on.
    token: token(phone.token_file ?? phone.browser_token_file ?? '~/.config/otto/token'),
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
