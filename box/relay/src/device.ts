// The phone itself: its device API, plain HTTP/1.1 served by the Otto app from "device" pipes of the tunnel
// (tunnel.ts). Never on a port of the phone's own: any app on the phone could reach that.
//
// Pipes are kept alive between requests (HTTP keep-alive), and a spare one or two are opened ahead of need, so a
// command doesn't wait for a new pipe. Commands that touch the screen run one at a time, so two sessions can't
// interleave their taps.
//
// The relay's local API keeps its shape (POST /phone/<cmd> with JSON args); each command is one route of the
// phone's API (docs/architecture.md lists them):
//   reading commands: GET /<cmd>?<args>        (arrays comma-separated)
//   the others:       POST /<cmd>, JSON args    (install: the APK as the body, ?name=)
// The phone answers 200 with
//   application/json                      the result, as JSON
//   text/plain                            the result, a string
//   any type, with X-Otto-Result: <json>  bytes (a screenshot, logs), and the result in the header
// or 4xx/5xx with the reason as text.
import http from 'node:http';
import type net from 'node:net';
import type { Phone, Tunnel } from './tunnel.js';

/** Commands that touch the screen. */
const SCREEN = new Set(['screenshot', 'ui', 'tap', 'longpress', 'swipe', 'text', 'key', 'launch', 'forcestop', 'install', 'uninstall', 'logcat']);
/** Commands that only read: GET with the args in the query. */
const READ = new Set(['info', 'current', 'screenshot', 'ui', 'wait', 'packages', 'appinfo', 'logcat']);
const SPARE = 2;
const IDLE_MS = 5 * 60_000;

export interface Reply { result: any; body: Buffer | null }

export const NO_DEVICE = 'The phone is not connected: open the Otto app on the phone and turn on its handle.';

/** HTTP over one phone's device pipes: idle ones kept alive, spares opened ahead of need. */
class PipeAgent extends http.Agent {
  private spares: net.Socket[] = [];
  private opening = 0;

  constructor(private tunnel: Tunnel, readonly phone: Phone) {
    super({ keepAlive: true, maxSockets: 8, maxFreeSockets: 4, timeout: IDLE_MS });
    this.topUp();
  }

  private topUp() {
    while (this.spares.length + this.opening < SPARE && this.tunnel.phones.has(this.phone)) {
      this.opening++;
      this.tunnel.openPipe(this.phone, 'device').then(s => {
        this.opening--;
        if (!this.tunnel.phones.has(this.phone)) return void s.destroy();
        s.once('close', () => { this.spares = this.spares.filter(x => x !== s); });
        this.spares.push(s);
      }, () => { this.opening--; });
    }
  }

  // Node's Agent asks here, with a callback, whenever it has no free socket to reuse.
  createConnection(_opts: unknown, cb?: (err: Error | null, s: any) => void): any {
    if (!cb) throw new Error('a device pipe opens asynchronously');
    const spare = this.spares.shift();
    this.topUp();
    if (spare) {
      spare.removeAllListeners('close');
      cb(null, spare.resume());
      return;
    }
    this.tunnel.openPipe(this.phone, 'device').then(s => cb(null, s.resume()), e => cb(e, null));
  }

  close() {
    for (const s of this.spares) s.destroy();
    this.spares = [];
    this.destroy();
  }
}

export class Device {
  private agents = new Map<Phone, PipeAgent>();
  private queue: Promise<unknown> = Promise.resolve();

  constructor(private tunnel: Tunnel) {
    tunnel.on('phone', (p: Phone) => { if (p.version >= 2) this.agents.set(p, new PipeAgent(tunnel, p)); });
    tunnel.on('gone', (p: Phone) => { this.agents.get(p)?.close(); this.agents.delete(p); });
  }

  /** The phone used last, among those that serve the device API. */
  private current(): PipeAgent | null {
    const p = this.tunnel.phone(x => this.agents.has(x));
    return p ? this.agents.get(p)! : null;
  }

  status() {
    const a = this.current();
    if (!a) return { connected: false };
    const { info, app, since } = a.phone;
    return { connected: true, device: info.device, android: info.android, agent: info.app ?? app, since: new Date(since).toISOString() };
  }

  /** Runs a command on the phone (screen commands one at a time). */
  run(cmd: string, args: Record<string, unknown> = {}, opts: Opts = {}): Promise<Reply> {
    if (!SCREEN.has(cmd)) return this.request(cmd, args, opts);
    const run = this.queue.then(() => this.request(cmd, args, opts));
    this.queue = run.catch(() => {});
    return run;
  }

  private request(cmd: string, args: Record<string, unknown>, { body, name, timeoutMs = 60_000 }: Opts, retry = true): Promise<Reply> {
    const agent = this.current();
    if (!agent) return Promise.reject(new Error(NO_DEVICE));
    let path = '/' + encodeURIComponent(cmd), method = 'POST', payload: Buffer = Buffer.alloc(0);
    const headers: Record<string, string> = {};
    if (cmd === 'install') {
      payload = body ?? payload;
      path += '?' + new URLSearchParams({ name: name ?? 'app.apk' });
      headers['content-type'] = 'application/vnd.android.package-archive';
    } else if (READ.has(cmd)) {
      method = 'GET';
      const q = query(args);
      if (q) path += '?' + q;
    } else {
      payload = Buffer.from(JSON.stringify(args));
      headers['content-type'] = 'application/json';
    }
    if (method === 'POST') headers['content-length'] = String(payload.length);
    return new Promise((resolve, reject) => {
      const req = http.request({ agent, host: 'phone', path, method, headers }, res => {
        const chunks: Buffer[] = [];
        res.on('data', c => chunks.push(c));
        res.on('error', reject);
        res.on('end', () => {
          const data = Buffer.concat(chunks);
          if ((res.statusCode ?? 500) >= 400) return reject(new Error(data.toString('utf8').trim() || `${cmd}: HTTP ${res.statusCode} from the phone`));
          const type = String(res.headers['content-type'] ?? '');
          const head = res.headers['x-otto-result'];
          try {
            if (head !== undefined) resolve({ result: JSON.parse(String(head)), body: data });
            else if (type.startsWith('application/json')) resolve({ result: data.length ? JSON.parse(data.toString('utf8')) : null, body: null });
            else if (type.startsWith('text/')) resolve({ result: data.toString('utf8'), body: null });
            else resolve({ result: null, body: data });
          } catch (e: any) { reject(new Error(`${cmd}: a reply the relay can't read (${e.message})`)); }
        });
      });
      req.setTimeout(timeoutMs, () => req.destroy(new Error(`${cmd}: no answer from the phone after ${Math.round(timeoutMs / 1000)} s`)));
      req.on('error', e => {
        const hungUp = /socket hang up|ECONNRESET/.test(e.message);
        // A kept-alive pipe the phone had just closed: the request never reached it, so send it again on another.
        if (hungUp && req.reusedSocket && retry) return void this.request(cmd, args, { body, name, timeoutMs }, false).then(resolve, reject);
        reject(hungUp ? new Error(`${cmd}: the phone hung up`) : e);
      });
      req.end(method === 'POST' ? payload : undefined);
    });
  }
}

interface Opts { body?: Buffer; name?: string; timeoutMs?: number }

/** Args as a query string: arrays comma-separated, null and undefined left out. */
function query(args: Record<string, unknown>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(args)) {
    if (v === undefined || v === null) continue;
    q.set(k, Array.isArray(v) ? v.join(',') : String(v));
  }
  return q.toString();
}
