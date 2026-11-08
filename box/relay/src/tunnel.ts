// The phone's reverse tunnel. The Otto app dials the box (devices dial out: nothing listens on the phone) and
// keeps one control connection open; whenever the relay wants to reach one of the phone's services, it asks for
// a pipe on the control connection, and the app opens a fresh TCP connection to the pipe port for it. Inside a
// pipe travels the service's own protocol: CDP for the browser's DevTools socket, HTTP/1.1 for the device API.
//
// Control connection (TCP, the control port), lines of UTF-8 text ending in \n:
//   app to relay:  hello <package> <token> [<version>]   first line; version 2 speaks this tunnel (no version:
//                                                        an older app: a browser link only, no device API)
//                  info <json>                           the phone: {"device", "android", "app", "versionCode"}
//                  browser up | browser down             the browser (its own process) came up or went away;
//                                                        "up" is followed by a "tab" line for each live tab
//                  ping                                  now and then; ignored
//                  dial <req> <service>                  the app wants a pipe of its own, to the relay's <service>:
//                                                        box (the box API, served by the relay: clips)
//                  ... and the browser's lines (browser.ts)
//   relay to app:  open <pipe> <service>                 open a pipe for <service>: devtools | device
//                  dialed <req> <pipe> | dialed <req> -  the pipe the app asked for (or none: an unknown service)
//                  ping                                  every 30 s, so the app notices a dead path
//                  ... and the browser's lines (browser.ts)
//
// Pipe (TCP, the pipe port): the app connects, sends "<pipe>\n", and from then on the connection is the service's
// stream. A pipe id is 24 random hex digits, asked for moments before and good once, so it needs no token. The
// same goes for a pipe the app dials for itself: it asks on the (authenticated) control connection, and the pipe
// it then opens is the relay's service's stream, spoken to by the app (the box API: HTTP/1.1, the app the client).
import net from 'node:net';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';

const PING_MS = 30_000;

export type Service = 'devtools' | 'device';

/** What the relay serves the app on a pipe the app dials. */
export const DIALS = new Set(['box']);

export interface Tab { tab: string; group: string | null }

export interface Phone {
  sock: net.Socket;
  app: string;
  /** What the app speaks: 2 for this tunnel, 1 for an older app's browser link. */
  version: number;
  info: Record<string, any>;
  /** The browser's process is up and its tabs are known. */
  browser: boolean;
  tabs: Map<string, Tab>; // target id -> tab
  active: number;
  since: number;
}

interface Waiting { resolve: (s: net.Socket) => void; reject: (e: Error) => void; timer: NodeJS.Timeout; phone: Phone }

/**
 * Events: "phone" (p) once a phone has said hello, "line" (p, words, line) for each control line after that,
 * "gone" (p) when its control connection closes, "dial" (p, service, socket) for a pipe the app dialled.
 */
export class Tunnel extends EventEmitter {
  readonly phones = new Set<Phone>();
  private pipes = new Map<string, Waiting>();

  constructor(private token: string, private log = console.log) { super(); }

  listen(host: string, controlPort: number, pipePort: number) {
    net.createServer(s => this.accept(s)).listen(controlPort, host, () => this.log(`tunnel on ${host}:${controlPort} (pipes ${pipePort})`));
    net.createServer(s => this.acceptPipe(s)).listen(pipePort, host);
    setInterval(() => { for (const p of this.phones) this.send(p, 'ping'); }, PING_MS).unref();
  }

  /** The phone used last, if any is connected (and, given a test, the last used of those that pass it). */
  phone(test: (p: Phone) => boolean = () => true): Phone | null {
    let best: Phone | null = null;
    for (const p of this.phones) if (test(p) && (!best || p.active > best.active)) best = p;
    return best;
  }

  send(p: Phone, line: string) {
    if (!p.sock.destroyed) p.sock.write(line + '\n');
  }

  /** A new pipe to one of the phone's services. */
  openPipe(p: Phone, service: Service, timeoutMs = 10_000): Promise<net.Socket> {
    const id = crypto.randomBytes(12).toString('hex');
    this.send(p, `open ${id} ${service}`);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.pipes.delete(id)) reject(new Error(`the phone did not open a ${service} pipe`));
      }, timeoutMs);
      this.pipes.set(id, { resolve, reject, timer, phone: p });
    });
  }

  private accept(sock: net.Socket) {
    sock.setKeepAlive(true, 10_000);
    sock.setNoDelay(true);
    sock.on('error', () => {});
    const p: Phone = { sock, app: '?', version: 1, info: {}, browser: true, tabs: new Map(), active: 0, since: Date.now() };
    let ok = false, buf = '';
    const hello = setTimeout(() => { if (!ok) sock.destroy(); }, 10_000);
    sock.on('data', d => {
      buf += d;
      if (!ok && buf.length > 4096) return void sock.destroy();
      let nl;
      while ((nl = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!ok) {
          const [w, app, token, version] = line.split(' ');
          if (w !== 'hello' || !same(token, this.token)) {
            this.log(`tunnel: refused ${sock.remoteAddress}`);
            return void sock.destroy();
          }
          clearTimeout(hello);
          ok = true;
          p.app = app;
          p.version = Number(version) || 1;
          p.browser = p.version < 2; // a newer app says when its browser is up
          p.active = Date.now();
          this.phones.add(p);
          this.log(`tunnel: ${app} connected${p.version < 2 ? ' (browser link only)' : ''}`);
          this.emit('phone', p);
          continue;
        }
        this.control(p, line);
      }
    });
    sock.on('close', () => {
      clearTimeout(hello);
      if (!this.phones.delete(p)) return;
      this.log(`tunnel: ${p.app} gone`);
      for (const [id, w] of this.pipes) {
        if (w.phone !== p) continue;
        this.pipes.delete(id);
        clearTimeout(w.timer);
        w.reject(new Error('the phone disconnected'));
      }
      this.emit('gone', p);
    });
  }

  private control(p: Phone, line: string) {
    const w = line.split(' ');
    switch (w[0]) {
      case 'ping': return;
      case 'active': p.active = Date.now(); break;
      case 'info':
        try { p.info = JSON.parse(line.slice(5)); } catch {}
        this.log(`tunnel: ${p.info.device ?? '?'}, Android ${p.info.android ?? '?'}, ${p.app} ${p.info.app ?? ''}`);
        break;
      case 'browser':
        p.browser = w[1] === 'up';
        p.tabs.clear(); // "up" is followed by the live tabs
        break;
      case 'dial': {
        if (!w[1] || !DIALS.has(w[2])) return void this.send(p, `dialed ${w[1] ?? '-'} -`);
        const id = crypto.randomBytes(12).toString('hex'), service = w[2];
        const timer = setTimeout(() => this.pipes.delete(id), 10_000);
        this.pipes.set(id, { resolve: s => this.emit('dial', p, service, s), reject: () => {}, timer, phone: p });
        this.send(p, `dialed ${w[1]} ${id}`);
        return;
      }
    }
    this.emit('line', p, w, line);
  }

  private acceptPipe(s: net.Socket) {
    s.on('error', () => {});
    s.setNoDelay(true);
    let head = Buffer.alloc(0);
    const timer = setTimeout(() => s.destroy(), 10_000);
    const onData = (d: Buffer) => {
      head = Buffer.concat([head, d]);
      const nl = head.indexOf(10);
      if (nl < 0) {
        if (head.length > 100) s.destroy();
        return;
      }
      clearTimeout(timer);
      s.off('data', onData);
      s.pause();
      const id = head.subarray(0, nl).toString();
      const w = this.pipes.get(id);
      this.pipes.delete(id);
      if (!w) return void s.destroy();
      clearTimeout(w.timer);
      if (head.length > nl + 1) s.unshift(head.subarray(nl + 1));
      w.resolve(s);
    };
    s.on('data', onData);
  }
}

function same(a: string | undefined, b: string): boolean {
  if (!a || a.length !== b.length) return false;
  return crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
}
