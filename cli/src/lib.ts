// What the otto CLI and the eyes MCP server share: the config, the session, and calls to the relay.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { parse } from 'smol-toml';

const home = os.homedir();

export interface Config {
  relay: string; // the relay's local API
  bots: string; // otto-bots' local API (the voice server's port): otto send, channel, schedule
  timezone: string; // the user's, for schedules' times ([schedules] timezone)
  display: string; // the box's desktop
  sessionCommand: string | null; // an adapter's command that prints this agent's session id (and title)
  shots: string; // where screenshots are saved
}

const configFile = () => process.env.OTTO_CONFIG || path.join(home, '.config/otto/config.toml');

function readToml(file: string): Record<string, any> {
  try { return parse(fs.readFileSync(file, 'utf8')) as Record<string, any>; } catch (e: any) {
    if (e.code !== 'ENOENT') throw new Error(`${file}: ${e.message}`);
    return {};
  }
}

export function loadConfig(): Config {
  const t = readToml(configFile());
  return {
    relay: `http://127.0.0.1:${t.relay?.local_port ?? 9334}`,
    bots: `http://127.0.0.1:${t.voice?.port ?? 9336}`,
    timezone: t.schedules?.timezone ?? 'Asia/Kolkata',
    display: t.desktop?.display ?? ':1',
    sessionCommand: process.env.OTTO_SESSION_COMMAND || t.session?.command || null,
    shots: process.env.OTTO_SHOTS || path.join(os.tmpdir(), 'otto-shots'),
  };
}

/**
 * What the phone needs to pair with this box, from the config file and the token file it points to: the relay's
 * tunnel (the box's tailnet name, its ports, the token) and the web services' URLs ([phone] hub_url,
 * artifacts_url, and optionally public_artifacts_url, panel_url).
 */
export interface Pairing {
  box: string;
  port: number;
  pipePort: number;
  token: string;
  hub: string;
  artifacts: string;
  publicArtifacts?: string;
  panel?: string;
  /** otto, or otto-dev for the dev app ([box] pair_scheme). */
  scheme?: string;
}

export function pairing(box?: string): Pairing {
  const file = configFile();
  const t = readToml(file);
  const relay = t.relay ?? {}, phone = t.phone ?? {};
  const token = (f: string) => {
    try { return fs.readFileSync(f.replace(/^~(?=\/|$)/, home), 'utf8').trim(); } catch {
      throw new OttoError(`no token at ${f} (set phone.token_file in ${file})`);
    }
  };
  const need = (v: any, key: string) => {
    if (!v) throw new OttoError(`set ${key} in ${file} (see install/config.example.toml)`);
    return String(v).replace(/\/+$/, '');
  };
  let host = box || phone.box;
  if (!host && t.box?.url) { try { host = new URL(t.box.url).hostname; } catch {} }
  host ||= relay.tailnet_host || tailnetAddress();
  if (!host) throw new OttoError(`can't tell the box's tailnet name: set [box] url in ${file}, or pass --box`);
  return {
    box: host,
    port: relay.tunnel_port ?? relay.browser_port ?? 9401,
    pipePort: relay.pipe_port ?? 9402,
    token: token(phone.token_file ?? phone.browser_token_file ?? '~/.config/otto/token'),
    hub: need(phone.hub_url, '[phone] hub_url'),
    artifacts: need(phone.artifacts_url, '[phone] artifacts_url'),
    publicArtifacts: phone.public_artifacts_url || undefined,
    panel: phone.panel_url || undefined,
    scheme: t.box?.pair_scheme || 'otto',
  };
}

/** The pairing link the Otto app takes: otto://pair?box=…&port=…&pipe_port=…&token=…&hub=…&artifacts=… */
export function pairingLink(p: Pairing): string {
  const q = new URLSearchParams({
    box: p.box,
    port: String(p.port),
    pipe_port: String(p.pipePort),
    token: p.token,
    hub: p.hub,
    artifacts: p.artifacts,
  });
  if (p.publicArtifacts) q.set('public_artifacts', p.publicArtifacts);
  if (p.panel) q.set('panel', p.panel);
  return `${p.scheme ?? 'otto'}://pair?${q}`;
}

/** This machine's Tailscale IPv4 address (100.64.0.0/10), if it has one. */
function tailnetAddress(): string {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list ?? []) {
      if (a.family !== 'IPv4') continue;
      const [x, y] = a.address.split('.').map(Number);
      if (x === 100 && y >= 64 && y < 128) return a.address;
    }
  }
  return '';
}

export interface Session { id: string; title: string }

/**
 * Which agent session this is: OTTO_SESSION (and OTTO_SESSION_TITLE), set by the agent's adapter; or what the
 * adapter's session command prints (id on the first line, title on the second); or a shared default.
 */
export function session(config: Config): Session {
  if (process.env.OTTO_SESSION) return { id: process.env.OTTO_SESSION, title: process.env.OTTO_SESSION_TITLE ?? '' };
  if (config.sessionCommand) {
    try {
      const [id, title = ''] = execFileSync('/bin/sh', ['-c', config.sessionCommand], { encoding: 'utf8', timeout: 5000 }).split('\n');
      if (id.trim()) return { id: id.trim(), title: title.trim() };
    } catch {}
  }
  return { id: 'default', title: 'Agent' };
}

export class OttoError extends Error {}

/** A call to the relay: text (or JSON) back, or bytes for images. */
export async function relay(config: Config, method: string, p: string, body?: unknown): Promise<Response> {
  let r: Response;
  try {
    r = await fetch(config.relay + p, {
      method,
      body: body === undefined ? undefined : Buffer.isBuffer(body) ? new Uint8Array(body) : JSON.stringify(body),
    });
  } catch {
    throw new OttoError(`the Otto relay isn't running (expected at ${config.relay}); start it with: systemctl start otto-relay`);
  }
  if (!r.ok) throw new OttoError((await r.text()).trim() || `${p}: HTTP ${r.status}`);
  return r;
}

/** A call to otto-bots' local API (box/bots local.ts). */
export async function bots(config: Config, method: string, p: string, body?: unknown): Promise<Response> {
  let r: Response;
  try {
    r = await fetch(config.bots + p, { method, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new OttoError(`otto-bots isn't running (expected at ${config.bots}); start it with: systemctl start otto-bots`);
  }
  if (!r.ok) throw new OttoError((await r.text()).trim() || `${p}: HTTP ${r.status}`);
  return r;
}

export async function browser(config: Config, s: Session, cmd: string, args: Record<string, unknown> = {}): Promise<Response> {
  const q = s.title ? `?title=${encodeURIComponent(s.title)}` : '';
  return relay(config, 'POST', `/browser/${encodeURIComponent(s.id)}/${cmd}${q}`, args);
}

export async function phone(config: Config, cmd: string, args: Record<string, unknown> = {}): Promise<{ result: any; body: Buffer | null }> {
  const r = await relay(config, 'POST', `/phone/${cmd}`, args);
  if (r.headers.get('content-type')?.startsWith('application/json')) return { result: (await r.json()).result, body: null };
  return { result: JSON.parse(r.headers.get('x-otto-result') ?? 'null'), body: Buffer.from(await r.arrayBuffer()) };
}

/** Saves a screenshot and returns its path. */
export function saveShot(config: Config, kind: string, data: Buffer, ext: string): string {
  fs.mkdirSync(config.shots, { recursive: true });
  const file = path.join(config.shots, `${kind}-${new Date().toISOString().replace(/[:.]/g, '-')}.${ext}`);
  fs.writeFileSync(file, data);
  return file;
}

/** The box's desktop as a PNG, at most `max` pixels on its long side (0: full size). */
export function desktopShot(config: Config, max = 0): Buffer {
  const args = ['-window', 'root'];
  if (max) args.push('-resize', `${max}x${max}>`);
  try {
    return execFileSync('import', [...args, 'png:-'], { env: { ...process.env, DISPLAY: config.display }, maxBuffer: 64 << 20 });
  } catch (e: any) {
    throw new OttoError(`can't capture the desktop on ${config.display}: ${e.stderr?.toString().trim() || e.message}`);
  }
}

/** The phone's screenshot scale that makes its long side about `max` pixels. */
export async function phoneScale(config: Config, max: number): Promise<number> {
  const { result } = await phone(config, 'info');
  const long = Math.max(...String(result?.screen ?? '').split('x').map(Number).filter(n => n > 0), 0); // "1080x2340"
  return long > max ? Math.round((max / long) * 100) / 100 : 1;
}
