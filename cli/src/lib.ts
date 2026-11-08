// What the otto CLI and the eyes MCP server share: the config, the session, and calls to the relay.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { parse } from 'smol-toml';

const home = os.homedir();

export interface Config {
  relay: string; // the relay's local API
  display: string; // the box's desktop
  sessionCommand: string | null; // an adapter's command that prints this agent's session id (and title)
  shots: string; // where screenshots are saved
}

export function loadConfig(): Config {
  const file = process.env.OTTO_CONFIG || path.join(home, '.config/otto/config.toml');
  let t: Record<string, any> = {};
  try { t = parse(fs.readFileSync(file, 'utf8')) as Record<string, any>; } catch (e: any) {
    if (e.code !== 'ENOENT') throw new Error(`${file}: ${e.message}`);
  }
  return {
    relay: `http://127.0.0.1:${t.relay?.local_port ?? 9334}`,
    display: t.desktop?.display ?? ':1',
    sessionCommand: process.env.OTTO_SESSION_COMMAND || t.session?.command || null,
    shots: process.env.OTTO_SHOTS || path.join(os.tmpdir(), 'otto-shots'),
  };
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
