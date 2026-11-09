// The relay's local API, on 127.0.0.1 only: what the otto CLI, the eyes MCP server and Playwright use.
//
//   GET  /status                          the phone's links, live tabs, sessions
//   POST /show?url=<url>                  open a page in the second screen
//   POST /sessions/<id>?title=<title>     name a session's tab group (before its first tab)
//   POST /close?session=<id>              close a session's tabs and forget its group
//   GET  /s/<id>/json/version|list        a session's DevTools endpoint (Playwright: connectOverCDP(".../s/<id>"))
//   WS   /s/<id>/browser
//   POST /browser/<id>/<cmd>?title=<t>    a browser command for a session (driver.ts); JSON args in the body;
//                                         replies text, or a PNG
//   POST /phone/<cmd>                     a device command; JSON args in the body (install: the APK, ?name=)
//                                         replies JSON {result}, or the bytes with the result in X-Otto-Result
//                                         (device.ts forwards it to the phone's device HTTP API)
//   GET  /clips[?limit=N], /clips/<id>, /clips/<id>/<n>.png; DELETE /clips/<id>
//                                         clips from the phone (clips.ts)
//
// The box API, served to the app on the pipes it dials itself (tunnel.ts "dial ... box"), never on a port: the
// clip routes, with POST /clips for uploads (clips.ts), and /voice/…, passed on as it is to the voice adapter
// (box/voice) on 127.0.0.1, which answers 503 here when it isn't running.
import http from 'node:http';
import { WebSocketServer } from 'ws';
import { Browser, NO_PHONE } from './browser.js';
import { Device, NO_DEVICE } from './device.js';
import { Driver } from './driver.js';
import type { Sessions } from './sessions.js';
import type { Clips } from './clips.js';
import type { Phone, Tunnel } from './tunnel.js';
import type net from 'node:net';

const SESSION = '[\\w.:@-]{1,200}';

export function serve(port: number, browser: Browser, device: Device, sessions: Sessions, clips: Clips, log = console.log) {
  const titles = new Map<string, string>(); // titles named before a session's first tab
  const driver = new Driver(id => `http://127.0.0.1:${port}/s/${encodeURIComponent(id)}`);
  const wss = new WebSocketServer({ noServer: true, maxPayload: 1 << 30 });

  const server = http.createServer(async (req, res) => {
    const u = new URL(req.url ?? '/', 'http://x');
    const send = (code: number, body: unknown) => {
      res.writeHead(code, { 'content-type': typeof body === 'string' ? 'text/plain' : 'application/json' });
      res.end(typeof body === 'string' ? body + '\n' : JSON.stringify(body, null, 1) + '\n');
    };
    try {
      if (u.pathname === '/status' || u.pathname === '/') {
        return send(200, { browser: browser.status(), device: device.status(), sessions: sessions.list() });
      }
      if (u.pathname === '/show' && req.method === 'POST') {
        const url = u.searchParams.get('url');
        if (!url) return send(400, 'show: no url');
        return browser.show(url) ? send(200, 'ok') : send(503, NO_PHONE);
      }
      let m = u.pathname.match(new RegExp(`^/sessions/(${SESSION})$`));
      if (m && req.method === 'POST') {
        const id = decodeURIComponent(m[1]), title = u.searchParams.get('title') ?? '';
        titles.set(id, title);
        return send(200, 'ok');
      }
      if (u.pathname === '/close' && req.method === 'POST') {
        const g = browser.close(u.searchParams.get('session') ?? '');
        return send(200, g ? `closed group ${g}` : 'no tabs');
      }
      m = u.pathname.match(new RegExp(`^/s/(${SESSION})/json(/version|/list)?/?$`));
      if (m) {
        if (!browser.phone()) return send(503, NO_PHONE);
        if (m[2] === '/version') {
          return send(200, { Browser: 'Chrome (phone)', 'Protocol-Version': '1.3', webSocketDebuggerUrl: `ws://127.0.0.1:${port}/s/${m[1]}/browser` });
        }
        return send(200, browser.targets(decodeURIComponent(m[1])).map(id => ({ id, type: 'page' })));
      }
      m = u.pathname.match(new RegExp(`^/browser/(${SESSION})/(\\w+)$`));
      if (m && req.method === 'POST') {
        if (!browser.phone()) return send(503, NO_PHONE);
        const id = decodeURIComponent(m[1]);
        if (u.searchParams.get('title') && !sessions.get(id)) titles.set(id, u.searchParams.get('title')!);
        const raw = await body(req);
        try {
          const r = await driver.run(id, m[2], raw.length ? JSON.parse(raw.toString('utf8')) : {});
          if (r.image) { res.writeHead(200, { 'content-type': 'image/png' }); return res.end(r.image); }
          return send(200, r.text ?? 'ok');
        } catch (e: any) {
          return send(502, e.message.split('\n')[0]);
        }
      }
      m = u.pathname.match(/^\/phone\/(\w+)$/);
      if (m && req.method === 'POST') return await phone(m[1], u, req, res, device);
      if (await clips.serve(req, res, false)) return;
      send(404, 'not found');
    } catch (e: any) {
      if (!res.headersSent) send(500, e.message);
    }
  });

  server.on('upgrade', (req, sock, head) => {
    const m = new URL(req.url ?? '/', 'http://x').pathname.match(new RegExp(`^/s/(${SESSION})/browser$`));
    if (!m) return void sock.destroy();
    const id = decodeURIComponent(m[1]);
    wss.handleUpgrade(req, sock, head, ws => browser.session(id, titles.get(id) ?? '', ws).catch(e => {
      log(`session ${id}: ${e.message}`);
      ws.close(1011, e.message.slice(0, 120));
    }));
  });

  server.listen(port, '127.0.0.1', () => log(`local API on 127.0.0.1:${port}`));
  return server;
}

/** The box API, for the app: HTTP on each pipe it dials (kept alive, as many requests as it likes). */
export function serveBox(tunnel: Tunnel, clips: Clips, voicePort: number, log = console.log) {
  const server = http.createServer(async (req, res) => {
    try {
      if (req.url?.startsWith('/voice/')) return voice(req, res, voicePort);
      if (await clips.serve(req, res, true)) return;
      res.writeHead(404, { 'content-type': 'text/plain' });
      res.end('not found\n');
    } catch (e: any) {
      log(`box API: ${e.message}`);
      if (!res.headersSent) res.writeHead(500, { 'content-type': 'text/plain' });
      res.end(e.message + '\n');
    }
  });
  tunnel.on('dial', (_p: Phone, service: string, s: net.Socket) => {
    if (service !== 'box') return void s.destroy();
    server.emit('connection', s);
    s.resume();
  });
  return server;
}

/** A box API request for the voice adapter, passed on and its answer streamed back. */
function voice(req: http.IncomingMessage, res: http.ServerResponse, port: number) {
  const up = http.request({ host: '127.0.0.1', port, method: req.method, path: req.url, headers: { 'content-type': req.headers['content-type'] ?? 'application/json' } }, r => {
    res.writeHead(r.statusCode ?? 502, { 'content-type': r.headers['content-type'] ?? 'text/plain', 'cache-control': 'no-store' });
    r.pipe(res);
  });
  up.on('error', (e: any) => {
    if (res.headersSent) return void res.destroy();
    res.writeHead(503, { 'content-type': 'text/plain' });
    res.end(e.code === 'ECONNREFUSED' ? 'voice: the voice adapter (otto-voice) is not running\n' : `voice: ${e.message}\n`);
  });
  // The phone giving up on a long poll ends ours too.
  res.on('close', () => { if (!res.writableFinished) up.destroy() });
  req.pipe(up);
}

async function phone(cmd: string, u: URL, req: http.IncomingMessage, res: http.ServerResponse, device: Device) {
  const raw = await body(req);
  let args: Record<string, unknown> = {}, apk: Buffer | undefined;
  if (cmd === 'install') apk = raw;
  else if (raw.length) args = JSON.parse(raw.toString('utf8'));
  const timeoutMs = Number(u.searchParams.get('timeout')) || (cmd === 'install' ? 300_000 : cmd === 'uninstall' ? 200_000 : 60_000);
  try {
    const r = await device.run(cmd, args, { body: apk, name: u.searchParams.get('name') ?? 'app.apk', timeoutMs });
    if (r.body) {
      res.writeHead(200, { 'content-type': 'application/octet-stream', 'x-otto-result': JSON.stringify(r.result ?? null) });
      return res.end(r.body);
    }
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ result: r.result ?? null }) + '\n');
  } catch (e: any) {
    res.writeHead(e.message === NO_DEVICE ? 503 : 502, { 'content-type': 'text/plain' });
    res.end(e.message + '\n');
  }
}

function body(req: http.IncomingMessage): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}
