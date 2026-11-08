// The phone itself, through the device agent's connection. The app dials the device port on the box's tailnet
// address, proves itself with the device token, and then runs the commands sent down the connection.
//
// Wire format, both ways: u32 big-endian header length, a JSON header, then header.size raw bytes if present.
// Requests {id, cmd, args}; replies {id, ok, result} / {id, ok:false, error}. (To be replaced by HTTP through
// the app's tunnel; see docs/architecture.md.)
import net from 'node:net';
import crypto from 'node:crypto';

const PING_MS = 30_000;

/** Commands that touch the screen run one at a time, so two sessions can't interleave taps. */
const SCREEN = new Set(['screenshot', 'ui', 'tap', 'longpress', 'swipe', 'text', 'key', 'launch', 'forcestop', 'install', 'uninstall', 'logcat']);

export interface Reply { result: any; body: Buffer | null }

interface Pending { resolve: (r: Reply) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }

export const NO_DEVICE = 'The phone is not connected: open the Otto app on the phone and turn on its device agent.';

export class Device {
  private conn: { socket: net.Socket; device: string; android: string; version: string; since: number } | null = null;
  private nextId = 1;
  private pending = new Map<string, Pending>();
  private queue: Promise<unknown> = Promise.resolve();

  constructor(private token: string, private log = console.log) {}

  listen(host: string, port: number) {
    net.createServer(s => this.accept(s)).listen(port, host, () => this.log(`device link on ${host}:${port}`));
    setInterval(() => this.conn && this.write(this.conn.socket, { ping: true }), PING_MS).unref();
  }

  status() {
    if (!this.conn) return { connected: false };
    const { device, android, version, since } = this.conn;
    return { connected: true, device, android, agent: version, since: new Date(since).toISOString() };
  }

  /** Runs a command on the phone (screen commands one at a time). */
  run(cmd: string, args: Record<string, unknown> = {}, opts: { body?: Buffer; timeoutMs?: number } = {}): Promise<Reply> {
    if (!SCREEN.has(cmd)) return this.request(cmd, args, opts);
    const run = this.queue.then(() => this.request(cmd, args, opts));
    this.queue = run.catch(() => {});
    return run;
  }

  private request(cmd: string, args: Record<string, unknown>, { body, timeoutMs = 30_000 }: { body?: Buffer; timeoutMs?: number }): Promise<Reply> {
    const conn = this.conn;
    if (!conn) return Promise.reject(new Error(NO_DEVICE));
    const id = String(this.nextId++);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${cmd}: no answer from the phone after ${Math.round(timeoutMs / 1000)} s`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.write(conn.socket, { id, cmd, args }, body);
    });
  }

  private accept(socket: net.Socket) {
    socket.setKeepAlive(true, 15_000);
    socket.setNoDelay(true);
    let authed = false;
    let buf = Buffer.alloc(0);
    const hello = setTimeout(() => socket.destroy(), 10_000);
    socket.on('data', (d: Buffer) => {
      buf = Buffer.concat([buf, d]);
      for (;;) {
        if (buf.length < 4) return;
        const n = buf.readUInt32BE(0);
        if (n > 1 << 20) return void socket.destroy();
        if (buf.length < 4 + n) return;
        let head: any;
        try { head = JSON.parse(buf.subarray(4, 4 + n).toString()); } catch { return void socket.destroy(); }
        const size = head.size || 0;
        if (buf.length < 4 + n + size) return;
        const body = size ? Buffer.from(buf.subarray(4 + n, 4 + n + size)) : null;
        buf = buf.subarray(4 + n + size);
        if (!authed) {
          clearTimeout(hello);
          const t = Buffer.from(String(head.token ?? ''));
          if (head.hello !== 'device-agent' || t.length !== this.token.length || !crypto.timingSafeEqual(t, Buffer.from(this.token))) {
            this.log(`device link: refused ${socket.remoteAddress} (bad token)`);
            this.write(socket, { welcome: false, error: 'wrong token' });
            return void socket.end();
          }
          authed = true;
          this.adopt(socket, head);
          continue;
        }
        this.onFrame(head, body);
      }
    });
    socket.on('error', () => {});
    socket.on('close', () => {
      clearTimeout(hello);
      if (this.conn?.socket !== socket) return;
      this.conn = null;
      this.log('device link: phone gone');
      for (const [id, p] of this.pending) { clearTimeout(p.timer); p.reject(new Error('the phone disconnected')); this.pending.delete(id); }
    });
  }

  private adopt(socket: net.Socket, hello: any) {
    this.conn?.socket.destroy(); // a reconnect replaces the old connection
    this.conn = { socket, device: hello.device, android: hello.android, version: hello.version, since: Date.now() };
    this.write(socket, { welcome: true });
    this.log(`device link: ${hello.device}, Android ${hello.android}, agent ${hello.version}`);
  }

  private onFrame(head: any, body: Buffer | null) {
    if (head.pong) return;
    const p = this.pending.get(String(head.id));
    if (!p) return;
    this.pending.delete(String(head.id));
    clearTimeout(p.timer);
    if (head.ok) p.resolve({ result: head.result, body });
    else p.reject(new Error(head.error || 'failed on the phone'));
  }

  private write(socket: net.Socket, head: any, body?: Buffer) {
    if (body) head.size = body.length;
    const h = Buffer.from(JSON.stringify(head));
    const len = Buffer.alloc(4);
    len.writeUInt32BE(h.length);
    socket.write(body ? Buffer.concat([len, h, body]) : Buffer.concat([len, h]));
  }
}
