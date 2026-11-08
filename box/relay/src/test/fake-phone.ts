// A stand-in for the Otto app, for tests: it speaks the app's side of the tunnel. Its browser is a headless
// Chromium, its tab groups are kept here, and its device API is a tiny HTTP server answering a few routes.
import net from 'node:net';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, type ChildProcess } from 'node:child_process';
import { WebSocket } from 'ws';
import { chromium } from 'playwright-core';

export class FakePhone {
  chrome!: ChildProcess;
  private port = 0;
  private uuid = '';
  private cdp!: WebSocket;
  private cdpId = 1;
  private cdpWaiting = new Map<number, (r: any) => void>();
  private control!: net.Socket;
  tabs = new Map<string, { tab: string; group: string | null }>();
  shown: string[] = [];
  closedGroups: string[] = [];
  private nextTab = 1;
  private nextGroup = 1;
  /** Device pipes opened, and device requests served. */
  devicePipes = 0;
  deviceRequests: string[] = [];
  private api = http.createServer((req, res) => this.device(req, res));
  private relayHost = '';
  private pipePort = 0;
  private dials = new Map<string, (pipe: string) => void>();
  private nextDial = 1;

  async start(relayHost: string, controlPort: number, pipePort: number, token: string) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'otto-fake-chrome-'));
    this.chrome = spawn(chromium.executablePath(), ['--headless=new', '--remote-debugging-port=0', `--user-data-dir=${dir}`, '--no-first-run', 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] });
    const ws = await new Promise<string>((resolve, reject) => {
      let err = '';
      this.chrome.stderr!.on('data', d => {
        err += d;
        const m = err.match(/DevTools listening on (ws:\/\/\S+)/);
        if (m) resolve(m[1]);
      });
      this.chrome.on('exit', c => reject(new Error(`chrome exited ${c}: ${err}`)));
    });
    const u = new URL(ws);
    this.port = +u.port;
    this.uuid = u.pathname.split('/').pop()!;
    this.cdp = new WebSocket(ws);
    await new Promise(r => this.cdp.once('open', r));
    this.cdp.on('message', d => {
      const m = JSON.parse(String(d));
      if (m.id && this.cdpWaiting.has(m.id)) { this.cdpWaiting.get(m.id)!(m); this.cdpWaiting.delete(m.id); }
    });

    this.relayHost = relayHost;
    this.pipePort = pipePort;
    this.control = net.connect(controlPort, relayHost);
    await new Promise(r => this.control.once('connect', r));
    this.control.write(`hello me.test.otto ${token} 2\n`);
    this.control.write(`info ${JSON.stringify({ device: 'Test Phone', android: '16', app: 'test', versionCode: 1 })}\n`);
    await this.browserUp();
    let buf = '';
    this.control.on('data', d => {
      buf += d;
      let nl;
      while ((nl = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, nl);
        buf = buf.slice(nl + 1);
        this.onLine(line, relayHost, pipePort).catch(e => console.error('fake phone:', e));
      }
    });
  }

  /** The browser's process came (back) up: its tabs, as it has them (at first, Chromium's, in no group). */
  async browserUp() {
    this.control.write('browser up\n');
    if (!this.tabs.size) {
      const { result } = await this.send('Target.getTargets');
      for (const t of result.targetInfos) if (t.type === 'page') this.announce(t.targetId, null);
    } else {
      for (const [target, t] of this.tabs) this.control.write(`tab ${target} ${t.tab} ${t.group ?? '-'}\n`);
    }
  }

  browserDown() {
    this.control.write('browser down\n');
  }

  private send(method: string, params: any = {}): Promise<any> {
    const id = this.cdpId++;
    this.cdp.send(JSON.stringify({ id, method, params }));
    return new Promise(r => this.cdpWaiting.set(id, r));
  }

  private announce(target: string, group: string | null) {
    const tab = `t${this.nextTab++}`;
    this.tabs.set(target, { tab, group });
    this.control.write(`tab ${target} ${tab} ${group ?? '-'}\n`);
  }

  private async onLine(line: string, relayHost: string, pipePort: number) {
    const w = line.split(' ');
    switch (w[0]) {
      case 'newtab': {
        const group = w[2] === '-' || ![...this.tabs.values()].some(t => t.group === w[2]) ? `tg${this.nextGroup++}` : w[2];
        const { result } = await this.send('Target.createTarget', { url: 'about:blank' });
        this.announce(result.targetId, group);
        this.control.write(`made ${w[1]} ${result.targetId} ${group}\n`);
        break;
      }
      case 'wake':
        this.control.write(`woke ${w[1]} ${[...this.tabs.values()].filter(t => t.group === w[2]).length}\n`);
        break;
      case 'close':
        await this.send('Target.closeTarget', { targetId: w[1] });
        this.tabs.delete(w[1]);
        this.control.write(`gone ${w[1]}\n`);
        break;
      case 'closegroup':
        this.closedGroups.push(w[1]);
        break;
      case 'show':
        this.shown.push(w[1]);
        break;
      case 'dialed':
        this.dials.get(w[1])?.(w[2]);
        this.dials.delete(w[1]);
        break;
      case 'open': {
        if (w[2] === 'device') {
          this.devicePipes++;
          const pipe = net.connect(pipePort, relayHost);
          pipe.write(`${w[1]}\n`);
          pipe.on('error', () => {});
          this.api.emit('connection', pipe);
          break;
        }
        // A DevTools pipe: the relay's end, joined to Chromium's (whose browser endpoint has an id in its path,
        // which the app's WebView socket doesn't).
        const pipe = net.connect(pipePort, relayHost);
        pipe.write(`${w[1]}\n`);
        const devtools = net.connect(this.port, '127.0.0.1');
        let first = true;
        pipe.on('data', d => {
          if (first) {
            first = false;
            d = Buffer.from(String(d).replace('GET /devtools/browser ', `GET /devtools/browser/${this.uuid} `));
          }
          devtools.write(d);
        });
        devtools.pipe(pipe);
        pipe.on('close', () => devtools.destroy());
        devtools.on('close', () => pipe.destroy());
        pipe.on('error', () => {});
        devtools.on('error', () => {});
        break;
      }
    }
  }

  /** A pipe of the app's own to one of the relay's services (the box API), as the app dials it. */
  dial(service = 'box'): Promise<net.Socket> {
    const req = `d${this.nextDial++}`;
    this.control.write(`dial ${req} ${service}\n`);
    return new Promise((resolve, reject) => {
      this.dials.set(req, pipe => {
        if (pipe === '-') return reject(new Error(`no ${service} pipe`));
        const s = net.connect(this.pipePort, this.relayHost, () => {
          s.write(`${pipe}\n`);
          resolve(s);
        });
      });
    });
  }

  /** An HTTP request to the box API, on a pipe of its own. */
  async box(method: string, p: string, body?: Buffer, headers: Record<string, string> = {}): Promise<{ status: number; type: string; body: Buffer }> {
    const s = await this.dial();
    return new Promise((resolve, reject) => {
      const req = http.request({ createConnection: () => s, path: p, method, headers: { ...headers, ...(body ? { 'content-length': String(body.length) } : {}) } }, res => {
        const chunks: Buffer[] = [];
        res.on('data', c => chunks.push(c));
        res.on('end', () => {
          s.destroy();
          resolve({ status: res.statusCode ?? 0, type: String(res.headers['content-type'] ?? ''), body: Buffer.concat(chunks) });
        });
      });
      req.on('error', reject);
      req.end(body);
    });
  }

  /** The device API: current, screenshot, ui, logcat, install; anything else is unknown. */
  private device(req: http.IncomingMessage, res: http.ServerResponse) {
    const u = new URL(req.url ?? '/', 'http://phone');
    const chunks: Buffer[] = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      const body = Buffer.concat(chunks);
      this.deviceRequests.push(`${req.method} ${u.pathname}${u.search}`);
      const json = (v: unknown) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(v)); };
      const route = `${req.method} ${u.pathname}`;
      if (route === 'GET /current') return json({ package: 'me.test.app', activity: '.Main' });
      if (route === 'GET /info') return json({ device: 'Test Phone', android: '16', screen: '1080x2340' });
      if (route === 'GET /screenshot') {
        res.writeHead(200, { 'content-type': 'image/png', 'x-otto-result': JSON.stringify({ width: 2, height: 1, format: 'png', scale: u.searchParams.get('scale') }) });
        return res.end('PNGBYTES');
      }
      if (route === 'GET /ui') { res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' }); return res.end(`Button "OK" all=${u.searchParams.get('all')}`); }
      if (route === 'GET /logcat') {
        res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8', 'x-otto-result': JSON.stringify({ fullAccess: true }) });
        return res.end(`log lines tags=${u.searchParams.get('tags')}\n`);
      }
      if (route === 'POST /tap') {
        const a = JSON.parse(body.toString() || '{}');
        if (a.x === undefined) { res.writeHead(400, { 'content-type': 'text/plain' }); return res.end('give x and y, or text, id or desc'); }
        res.writeHead(200, { 'content-type': 'text/plain' });
        return res.end(`tapped ${a.x},${a.y}`);
      }
      if (route === 'POST /install') { res.writeHead(200, { 'content-type': 'text/plain' }); return res.end(`Success: ${u.searchParams.get('name')} (${body.length} bytes)`); }
      res.writeHead(404, { 'content-type': 'text/plain' });
      res.end(`unknown command ${u.pathname.slice(1)}`);
    });
  }

  stop() {
    this.control?.destroy();
    this.cdp?.close();
    this.chrome?.kill();
  }
}
