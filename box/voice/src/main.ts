#!/usr/bin/env node
// otto-voice: live voice calls from the phone to Codex (session.ts), on 127.0.0.1 only. The phone reaches it
// through the relay's box API, which passes /voice/… on to here (the relay's api.ts), so it's behind the tunnel's
// pairing like everything else the app sends to the box.
//
//   POST /voice/sessions                      body {sdp}: the phone's WebRTC offer; replies {id, sdp} (the answer).
//                                             One call at a time: a new one ends the one before.
//   GET  /voice/sessions/<id>/events?after=N  long poll (up to 25 s): {state, working, events: [{n, type, …}]}
//   POST /voice/sessions/<id>/stop            ends it
//   GET  /voice/status                        the sessions, for a look from the box
//
// A session whose phone stops asking for events (gone, or out of signal) for a while is ended, so a lost phone
// never leaves Codex or the call running.
//
// Config, in [voice] of ~/.config/otto/config.toml (OTTO_CONFIG): port (9336), cwd (the folder Codex works in, ~),
// and realtime (a table of extras for thread/realtime/start, e.g. voice = "marin").
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { parse } from 'smol-toml';
import { Session } from './session.js';

const POLL_MS = 25_000;
const UNWATCHED_MS = 45_000;

const home = os.homedir();
const expand = (p: string) => p.replace(/^~(?=\/|$)/, home);
const file = process.env.OTTO_CONFIG || path.join(home, '.config/otto/config.toml');
let toml: Record<string, any> = {};
try { toml = parse(fs.readFileSync(file, 'utf8')) as Record<string, any> } catch (e: any) {
  if (e.code !== 'ENOENT') throw new Error(`${file}: ${e.message}`);
}
const voice = toml.voice ?? {};
const port: number = voice.port ?? 9336;
const cwd = expand(voice.cwd ?? '~');
const realtime: Record<string, unknown> = voice.realtime ?? {};

const log = (m: string) => console.log(`${new Date().toISOString()} ${m}`);
const sessions = new Map<string, Session>();

function send(res: http.ServerResponse, code: number, body: unknown) {
  res.writeHead(code, { 'content-type': typeof body === 'string' ? 'text/plain' : 'application/json', 'cache-control': 'no-store' });
  res.end(typeof body === 'string' ? body + '\n' : JSON.stringify(body) + '\n');
}

function body(req: http.IncomingMessage, max = 1 << 20): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on('data', c => {
      size += c.length;
      if (size > max) { reject(new Error('too big')); req.destroy() } else chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

/** Ended sessions stay a minute, so the phone can still read how they ended. */
function forget(s: Session) {
  setTimeout(() => sessions.get(s.id) === s && sessions.delete(s.id), 60_000).unref();
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url ?? '/', 'http://x');
  try {
    if (u.pathname === '/voice/status') {
      return send(res, 200, [...sessions.values()].map(s => ({ id: s.id, state: s.state, working: s.working, age: Math.round((Date.now() - s.created) / 1000) })));
    }
    if (u.pathname === '/voice/sessions' && req.method === 'POST') {
      const sdp = JSON.parse(await body(req)).sdp;
      if (typeof sdp !== 'string' || !sdp.startsWith('v=')) return send(res, 400, 'voice: no SDP offer');
      for (const s of sessions.values()) if (s.state !== 'ended') await s.stop('replaced');
      const s = new Session({ cwd, realtime, log });
      sessions.set(s.id, s);
      log(`[${s.id}] new call`);
      try {
        const answer = await s.start(sdp);
        return send(res, 200, { id: s.id, sdp: answer });
      } catch (e: any) {
        forget(s);
        return send(res, 502, `voice: ${e.message}`);
      }
    }
    const m = u.pathname.match(/^\/voice\/sessions\/([\w-]+)\/(events|stop)$/);
    if (m) {
      const s = sessions.get(m[1]);
      if (!s) return send(res, 404, 'voice: no such session');
      if (m[2] === 'events' && req.method === 'GET') {
        const r = await s.poll(Number(u.searchParams.get('after')) || 0, POLL_MS);
        return send(res, 200, r);
      }
      if (m[2] === 'stop' && req.method === 'POST') {
        await s.stop('phone');
        forget(s);
        return send(res, 200, 'ok');
      }
    }
    send(res, 404, 'not found');
  } catch (e: any) {
    if (!res.headersSent) send(res, 500, `voice: ${e.message}`);
  }
});

// The watchdog: a session nobody has asked about for a while is ended.
setInterval(() => {
  for (const s of sessions.values()) {
    if (s.state !== 'ended' && Date.now() - s.seen > UNWATCHED_MS) {
      log(`[${s.id}] the phone stopped asking`);
      void s.stop('unwatched').then(() => forget(s));
    }
  }
}, 5000).unref();

async function shutdown() {
  await Promise.all([...sessions.values()].map(s => s.stop('shutdown')));
  process.exit(0);
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

server.listen(port, '127.0.0.1', () => log(`voice on 127.0.0.1:${port}, Codex in ${cwd}`));
