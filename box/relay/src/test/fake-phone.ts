// A stand-in for the Otto app, for tests: its browser is a headless Chromium, its tab groups are kept here, and
// its device agent answers a few commands. Speaks the app's side of both links.
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, type ChildProcess } from 'node:child_process';
import { WebSocket } from 'ws';
import { chromium } from 'playwright-core';

export class FakeBrowserApp {
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

    this.control = net.connect(controlPort, relayHost);
    await new Promise(r => this.control.once('connect', r));
    this.control.write(`hello me.test.otto ${token}\n`);
    // The tabs it has already, in no group.
    const { result } = await this.send('Target.getTargets');
    for (const t of result.targetInfos) if (t.type === 'page') this.announce(t.targetId, null);
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
      case 'open': {
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

  stop() {
    this.control?.destroy();
    this.cdp?.close();
    this.chrome?.kill();
  }
}

/** The device agent's side of its link: answers "current" and "screenshot", fails anything else. */
export async function fakeDevice(host: string, port: number, token: string): Promise<net.Socket> {
  const s = net.connect(port, host);
  await new Promise(r => s.once('connect', r));
  const write = (head: any, body?: Buffer) => {
    if (body) head.size = body.length;
    const h = Buffer.from(JSON.stringify(head));
    const len = Buffer.alloc(4);
    len.writeUInt32BE(h.length);
    s.write(Buffer.concat(body ? [len, h, body] : [len, h]));
  };
  write({ hello: 'device-agent', token, device: 'Test Phone', android: '16', version: 'test' });
  let buf = Buffer.alloc(0);
  s.on('data', d => {
    buf = Buffer.concat([buf, d]);
    while (buf.length >= 4) {
      const n = buf.readUInt32BE(0);
      if (buf.length < 4 + n) return;
      const head = JSON.parse(buf.subarray(4, 4 + n).toString());
      const size = head.size ?? 0;
      if (buf.length < 4 + n + size) return;
      buf = buf.subarray(4 + n + size);
      if (head.welcome !== undefined || head.ping) continue;
      if (head.cmd === 'current') write({ id: head.id, ok: true, result: { package: 'me.test.app', activity: '.Main' } });
      else if (head.cmd === 'screenshot') write({ id: head.id, ok: true, result: { width: 2, height: 1, format: 'png' } }, Buffer.from('PNGBYTES'));
      else write({ id: head.id, ok: false, error: `unknown command ${head.cmd}` });
    }
  });
  return s;
}
