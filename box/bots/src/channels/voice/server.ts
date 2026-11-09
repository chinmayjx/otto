// The voice server: the phone's way to the assistant's voice channel, on 127.0.0.1 only. The phone reaches it through
// the relay's box API, which passes /voice/… on to here, so it's behind the tunnel's pairing like everything else
// the app sends to the box.
//
//   GET  /voice/assistant                      {bot, agent, provider, mode, bots}: the assistant (bot null when
//                                              none) and every bot, for the app's picker
//   POST /voice/assistant                      body {bot}: makes that bot the assistant (moves its voice channel)
//   POST /voice/sessions                       starts a call, ending the one before. webrtc: body {sdp}, the phone's
//                                              offer; replies {id, mode, sdp}. android: replies {id, mode}
//   GET  /voice/sessions/<id>/events?after=N   long poll (up to 25 s): {state, events: [{n, type, …}]} (call.ts)
//   POST /voice/sessions/<id>/heard            android: body {text}, a sentence the phone heard
//   POST /voice/sessions/<id>/report           android: body {speaking?, hearing?}
//   POST /voice/sessions/<id>/stop             ends it
//
// A call whose phone stops asking for events (gone, or out of signal) for a while is ended, so a lost phone never
// leaves a call running.
import http from 'node:http';
import { AndroidCall } from './android.js';
import type { Voice } from './index.js';

const POLL_MS = 25_000;
const UNWATCHED_MS = 45_000;

export interface Assistant { bot: string; agent: string; voice: Voice }

export interface ServerDeps {
  /** The assistant now, if any bot is. */
  assistant(): Assistant | null;
  /** Every bot's name. */
  bots(): string[];
  /** Makes a bot the assistant; throws with a reason it can't. */
  assign(bot: string): Promise<void>;
  log(m: string): void;
}

function send(res: http.ServerResponse, code: number, body: unknown) {
  res.writeHead(code, { 'content-type': typeof body === 'string' ? 'text/plain' : 'application/json', 'cache-control': 'no-store' });
  res.end(typeof body === 'string' ? body + '\n' : JSON.stringify(body) + '\n');
}

function body(req: http.IncomingMessage, max = 1 << 20): Promise<any> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on('data', c => {
      size += c.length;
      if (size > max) { reject(new Error('too big')); req.destroy(); } else chunks.push(c);
    });
    req.on('end', () => {
      const s = Buffer.concat(chunks).toString('utf8');
      try { resolve(s ? JSON.parse(s) : {}); } catch { reject(new Error('not JSON')); }
    });
    req.on('error', reject);
  });
}

export function serveVoice(port: number, d: ServerDeps): http.Server {
  const server = http.createServer(async (req, res) => {
    const u = new URL(req.url ?? '/', 'http://x');
    try {
      const a = d.assistant();
      if (u.pathname === '/voice/assistant') {
        if (req.method === 'POST') {
          const b = await body(req);
          await d.assign(String(b.bot ?? ''));
          return send(res, 200, 'ok');
        }
        return send(res, 200, {
          bot: a?.bot ?? null, agent: a?.agent ?? null, provider: a?.voice.provider ?? null,
          mode: a ? (a.voice.provider === 'android' ? 'android' : 'webrtc') : null, bots: d.bots(),
        });
      }
      if (u.pathname === '/voice/sessions' && req.method === 'POST') {
        if (!a) return send(res, 404, 'voice: no assistant yet: give a bot the otto_voice channel (otto bot voice <name>)');
        const b = await body(req);
        try {
          return send(res, 200, await a.voice.open(b));
        } catch (e: any) {
          return send(res, 502, `voice: ${e.message}`);
        }
      }
      const m = u.pathname.match(/^\/voice\/sessions\/([\w-]+)\/(events|heard|report|stop)$/);
      if (m) {
        const call = a?.voice.call;
        if (!call || call.id !== m[1]) {
          // An ended call reads as ended, so the phone lets go of it.
          if (m[2] === 'events') return send(res, 200, { state: 'ended', events: [{ n: Number(u.searchParams.get('after') || 0) + 1, type: 'state', state: 'ended' }] });
          return send(res, m[2] === 'stop' ? 200 : 404, m[2] === 'stop' ? 'ok' : 'voice: no such call');
        }
        if (m[2] === 'events' && req.method === 'GET') return send(res, 200, await call.poll(Number(u.searchParams.get('after')) || 0, POLL_MS));
        if (m[2] === 'stop' && req.method === 'POST') {
          await a!.voice.hangUp('phone');
          return send(res, 200, 'ok');
        }
        if (req.method === 'POST' && call instanceof AndroidCall) {
          const b = await body(req);
          if (m[2] === 'heard') call.heard(String(b.text ?? ''));
          else call.report(b);
          return send(res, 200, 'ok');
        }
      }
      send(res, 404, 'not found');
    } catch (e: any) {
      if (!res.headersSent) send(res, 400, `voice: ${e.message}`);
    }
  });

  // The watchdog: a call nobody has asked about for a while is ended.
  setInterval(() => {
    const a = d.assistant(), call = a?.voice.call;
    if (call && call.state !== 'ended' && Date.now() - call.seen > UNWATCHED_MS) {
      d.log(`voice [${call.id}] the phone stopped asking`);
      void a!.voice.hangUp('unwatched');
    }
  }, 5000).unref();

  server.on('error', e => d.log(`voice server: ${e.message}`));
  server.listen(port, '127.0.0.1', () => d.log(`voice on 127.0.0.1:${port}`));
  return server;
}

/** A phone notification, through the relay's local API (the app's device command "notify"). */
export async function notify(relayPort: number, text: string): Promise<void> {
  const r = await fetch(`http://127.0.0.1:${relayPort}/phone/notify`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ title: 'Otto', text }),
    signal: AbortSignal.timeout(15_000),
  }).catch(e => { throw new Error(`the relay isn't running (${e.cause?.code ?? e.message})`); });
  if (!r.ok) throw new Error((await r.text()).trim() || `relay: HTTP ${r.status}`);
}
