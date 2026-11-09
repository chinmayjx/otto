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
//   POST /voice/sessions/<id>/interrupt        the voice stops talking (a tap on the pill)
//   POST /voice/sessions/<id>/mute             body {on}: the phone muted or unmuted its microphone
//   POST /voice/sessions/<id>/stop             ends it
//   WS   /voice/transcribe                     live dictation (transcribe.ts), for the collector's mic: the phone
//                                              sends 16-bit mono PCM at 24 kHz as binary messages, then {"type":
//                                              "stop"}; the box sends {type: "ready", engine}, {type: "draft",
//                                              text} (the phrase so far), {type: "final", text}, {type: "error",
//                                              message}, and {type: "done"} once the last phrase is in, then closes
//
// A call whose phone stops asking for events (gone, or out of signal) for a while is ended, so a lost phone never
// leaves a call running; so is one left muted for 10 minutes (the next call picks up where it left off).
//
// The same server answers otto-bots' other local routes (local.ts: otto send, channel logs, schedules).
import http from 'node:http';
import { WebSocketServer, type WebSocket } from 'ws';
import { transcriber, type Transcriber } from '../../transcribe.js';
import { AndroidCall } from './android.js';
import type { Voice } from './index.js';

const POLL_MS = 25_000;
const UNWATCHED_MS = 45_000;
const MUTED_MS = 10 * 60_000;

export interface Assistant { bot: string; agent: string; voice: Voice }

export interface ServerDeps {
  /** The assistant now, if any bot is. */
  assistant(): Assistant | null;
  /** Every bot's name. */
  bots(): string[];
  /** Makes a bot the assistant; throws with a reason it can't. */
  assign(bot: string): Promise<void>;
  log(m: string): void;
  /** A Deepgram key's file, for dictation when OpenAI can't start. */
  deepgramKeyFile?: string;
  /** otto-bots' other routes (local.ts): true when it answered. */
  route?(req: http.IncomingMessage, res: http.ServerResponse, u: URL): Promise<boolean>;
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
      const m = u.pathname.match(/^\/voice\/sessions\/([\w-]+)\/(events|heard|report|interrupt|mute|stop)$/);
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
        if (m[2] === 'interrupt' && req.method === 'POST') {
          call.interrupt();
          return send(res, 200, 'ok');
        }
        if (m[2] === 'mute' && req.method === 'POST') {
          call.mute(!!(await body(req)).on);
          return send(res, 200, 'ok');
        }
        if (req.method === 'POST' && call instanceof AndroidCall) {
          const b = await body(req);
          if (m[2] === 'heard') call.heard(String(b.text ?? ''));
          else call.report(b);
          return send(res, 200, 'ok');
        }
      }
      if (d.route && await d.route(req, res, u)) return;
      send(res, 404, 'not found');
    } catch (e: any) {
      if (!res.headersSent) send(res, 400, `${u.pathname.startsWith('/voice/') ? 'voice: ' : ''}${e.message}`);
    }
  });

  // The watchdog: a call nobody has asked about for a while is ended.
  setInterval(() => {
    const a = d.assistant(), call = a?.voice.call;
    if (call && call.state !== 'ended' && Date.now() - call.seen > UNWATCHED_MS) {
      d.log(`voice [${call.id}] the phone stopped asking`);
      void a!.voice.hangUp('unwatched');
    } else if (call && call.state !== 'ended' && call.mutedSince && Date.now() - call.mutedSince > MUTED_MS) {
      void a!.voice.hangUp('muted', 'Hung up after 10 minutes on mute. Call again to carry on where you left off.');
    }
  }, 5000).unref();

  const wss = new WebSocketServer({ noServer: true, maxPayload: 1 << 20 });
  server.on('upgrade', (req, sock, head) => {
    if (new URL(req.url ?? '/', 'http://x').pathname !== '/voice/transcribe') return void sock.destroy();
    wss.handleUpgrade(req, sock, head, ws => dictate(ws, d));
  });

  server.on('error', e => d.log(`voice server: ${e.message}`));
  server.listen(port, '127.0.0.1', () => d.log(`voice on 127.0.0.1:${port}`));
  return server;
}

/** One dictation: the phone's audio to a transcriber (the assistant's OpenAI sign-in, or the Codex one when there's no
 * assistant), its text back as it comes. Audio that arrives while the transcriber starts waits for it. */
function dictate(ws: WebSocket, d: ServerDeps) {
  const voice = d.assistant()?.voice.config;
  const started = Date.now();
  let t: Transcriber | null = null;
  let waiting: Buffer[] = [];
  let bytes = 0, finals = 0, stopping = false;
  const out = (m: unknown) => { if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(m)); };
  const stop = async () => {
    if (stopping) return;
    stopping = true;
    if (t) await t.finish();
    out({ type: 'done' });
    ws.close();
  };
  transcriber({
    auth: voice ? String(voice.auth ?? 'api-key') : 'codex',
    apiKey: typeof voice?.apiKey === 'string' ? voice.apiKey : undefined,
    deepgramKeyFile: d.deepgramKeyFile,
    log: d.log,
  }, {
    draft: text => out({ type: 'draft', text }),
    final: text => { finals++; out({ type: 'final', text }); },
    error: message => out({ type: 'error', message }),
  }).then(tr => {
    if (ws.readyState !== ws.OPEN) return tr.close();
    t = tr;
    for (const b of waiting) tr.append(b);
    waiting = [];
    out({ type: 'ready', engine: tr.engine });
    if (stopping) { stopping = false; void stop(); }
  }, e => {
    d.log(`transcribe: can't start: ${e.message}`);
    out({ type: 'error', message: e.message });
    ws.close();
  });
  ws.on('message', (data, binary) => {
    if (binary) {
      const b = Buffer.isBuffer(data) ? data : Buffer.concat(data as Buffer[]);
      bytes += b.length;
      if (t) t.append(b); else waiting.push(b);
      return;
    }
    let m: any;
    try { m = JSON.parse(String(data)); } catch { return; }
    if (m.type === 'stop') {
      if (t) void stop(); else stopping = true; // stopped before it was up: finished once it is
    }
  });
  ws.on('close', () => {
    t?.close();
    d.log(`transcribe: ${Math.round(bytes / 2 / 24_000)} s of audio, ${finals} phrases, ${Math.round((Date.now() - started) / 1000)} s`);
  });
}

/** A phone notification, through the relay's local API (the app's device command "notify"). */
export async function notify(relayPort: number, text: string): Promise<void> {
  const r = await fetch(`http://127.0.0.1:${relayPort}/phone/notify`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ title: 'Otto', text }),
    signal: AbortSignal.timeout(15_000),
  }).catch(e => { throw new Error(`the relay isn't running (${e.cause?.code ?? e.message})`); });
  if (!r.ok) throw new Error((await r.text()).trim() || `relay: HTTP ${r.status}`);
}
