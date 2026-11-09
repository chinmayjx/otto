// Live speech to text on the box: audio streams in as it's recorded, and the words come back a phrase at a time, a
// draft while a phrase is being written down, then its final text. For the collector's mic in the Otto app (the voice
// server's /voice/transcribe), and anything else on the box that dictates.
//
// The engine is an OpenAI realtime transcription session (gpt-4o-transcribe): its key is minted per session, from
// the Codex ChatGPT sign-in or an API key, as the assistant's voice is set up (OpenAI's plain transcription endpoint
// doesn't take the Codex sign-in; a realtime session does). When that can't start (the sign-in has expired, OpenAI
// is down) and there's a Deepgram key, Deepgram's live Nova-3 does it instead.
//
// Audio in is 16-bit mono PCM at 24 kHz. Transcripts never go to the log: sizes and timings only.
import fs from 'node:fs';
import WebSocket from 'ws';
import { codexAccessToken, mintRealtimeKey } from './channels/voice/codex-auth.js';

export const RATE = 24_000;
const OPENAI = 'wss://api.openai.com/v1/realtime?intent=transcription';
const DEEPGRAM = `wss://api.deepgram.com/v1/listen?model=nova-3&encoding=linear16&sample_rate=${RATE}&channels=1`
  + '&interim_results=true&smart_format=true&endpointing=400';
/** How long finishing waits for the last phrase's text. */
const FLUSH_MS = 6000;

export interface TranscribeOptions {
  /** "codex" mints the session's key from the Codex sign-in; anything else uses apiKey. */
  auth?: string;
  apiKey?: string;
  model?: string;
  /** A file holding a Deepgram key (DEEPGRAM_API_KEY=…, or the key alone), for when OpenAI can't start. */
  deepgramKeyFile?: string;
  log(m: string): void;
}

export interface Heard {
  /** The phrase being written down, as far as it goes (replaces the draft before it). */
  draft(text: string): void;
  /** A phrase's final text. */
  final(text: string): void;
  /** The engine failed mid-way. */
  error(message: string): void;
}

export interface Transcriber {
  readonly engine: string;
  append(pcm: Buffer): void;
  /** The audio is over: resolves once the last phrase's text is in (or after a while). */
  finish(): Promise<void>;
  close(): void;
}

/** A live transcriber, started: OpenAI, else Deepgram. */
export async function transcriber(o: TranscribeOptions, on: Heard): Promise<Transcriber> {
  try {
    return await openai(o, on);
  } catch (e: any) {
    const key = deepgramKey(o.deepgramKeyFile);
    if (!key) throw e;
    o.log(`transcribe: OpenAI didn't start (${redact(e.message).replace(/\s+/g, ' ').slice(0, 200)}); using Deepgram`);
    return deepgram(key, on, o.log);
  }
}

function deepgramKey(file?: string): string | null {
  if (!file) return null;
  try {
    const text = fs.readFileSync(file, 'utf8');
    const m = text.match(/^\s*DEEPGRAM_API_KEY\s*=\s*(\S+)/m);
    return m ? m[1].replace(/^["']|["']$/g, '') : text.trim() || null;
  } catch {
    return null;
  }
}

function connect(url: string, headers: Record<string, string>): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { headers });
    const t = setTimeout(() => { ws.terminate(); reject(new Error('timed out connecting')); }, 10_000);
    ws.once('open', () => { clearTimeout(t); resolve(ws); });
    ws.once('error', e => { clearTimeout(t); reject(e); });
    ws.once('unexpected-response', (_req, res) => {
      clearTimeout(t);
      reject(new Error(`HTTP ${res.statusCode}`));
      ws.terminate();
    });
  });
}

async function openai(o: TranscribeOptions, on: Heard): Promise<Transcriber> {
  const session = {
    type: 'transcription',
    audio: {
      input: {
        format: { type: 'audio/pcm', rate: RATE },
        transcription: { model: o.model || 'gpt-4o-transcribe' },
        turn_detection: { type: 'server_vad', silence_duration_ms: 500 },
        noise_reduction: { type: 'near_field' },
      },
    },
  };
  const bearer = o.auth === 'codex' ? await codexAccessToken() : o.apiKey;
  if (!bearer) throw new Error('no OpenAI API key');
  const key = await mintRealtimeKey(bearer, session);
  const ws = await connect(OPENAI, { authorization: `Bearer ${key}` });
  o.log('transcribe: OpenAI session up');

  // Phrases in the order they were committed; each one's text so far, and whether it's final.
  const order: string[] = [];
  const text = new Map<string, string>();
  const done = new Set<string>();
  let settle: (() => void) | null = null;
  const draft = () => on.draft(order.filter(id => !done.has(id)).map(id => text.get(id) ?? '').join(' ').trim());
  const flush = () => {
    while (order.length && done.has(order[0])) {
      const id = order.shift()!;
      const t = (text.get(id) ?? '').trim();
      text.delete(id);
      done.delete(id);
      if (t) on.final(t);
    }
    draft();
    if (!order.length) settle?.();
  };
  const track = (id: string) => { if (id && !text.has(id)) { order.push(id); text.set(id, ''); } };

  ws.on('message', d => {
    let e: any;
    try { e = JSON.parse(String(d)); } catch { return; }
    switch (e.type) {
      case 'input_audio_buffer.committed':
        track(e.item_id);
        break;
      case 'conversation.item.input_audio_transcription.delta':
        track(e.item_id);
        text.set(e.item_id, (text.get(e.item_id) ?? '') + (e.delta ?? ''));
        draft();
        break;
      case 'conversation.item.input_audio_transcription.completed':
        track(e.item_id);
        text.set(e.item_id, String(e.transcript ?? ''));
        done.add(e.item_id);
        flush();
        break;
      case 'conversation.item.input_audio_transcription.failed':
        track(e.item_id);
        text.set(e.item_id, '');
        done.add(e.item_id);
        flush();
        break;
      case 'error':
        // Committing an empty buffer (the user stopped in a silence) is no error of theirs.
        if (/empty|too small/i.test(String(e.error?.message ?? '')) || e.error?.code === 'input_audio_buffer_commit_empty') {
          if (!order.length) settle?.();
          break;
        }
        o.log(`transcribe: OpenAI: ${e.error?.code ?? ''} ${redact(String(e.error?.message ?? '')).slice(0, 200)}`);
        on.error(String(e.error?.message ?? 'OpenAI failed'));
        break;
    }
  });
  ws.on('close', () => settle?.());
  ws.on('error', e => o.log(`transcribe: ${redact(e.message)}`));

  return {
    engine: 'openai',
    append(pcm) {
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'input_audio_buffer.append', audio: pcm.toString('base64') }));
    },
    finish() {
      return new Promise<void>(resolve => {
        const t = setTimeout(() => resolve(), FLUSH_MS);
        settle = () => { clearTimeout(t); resolve(); };
        if (ws.readyState !== WebSocket.OPEN) return settle();
        ws.send(JSON.stringify({ type: 'input_audio_buffer.commit' }));
      });
    },
    close() { ws.close(); },
  };
}

async function deepgram(key: string, on: Heard, log: (m: string) => void): Promise<Transcriber> {
  const ws = await connect(DEEPGRAM, { authorization: `Token ${key}` });
  log('transcribe: Deepgram session up');
  let closed: (() => void) | null = null;
  ws.on('message', d => {
    let e: any;
    try { e = JSON.parse(String(d)); } catch { return; }
    if (e.type !== 'Results') return;
    const t = String(e.channel?.alternatives?.[0]?.transcript ?? '').trim();
    if (e.is_final) {
      if (t) on.final(t);
      on.draft('');
    } else on.draft(t);
  });
  ws.on('close', () => closed?.());
  ws.on('error', e => log(`transcribe: Deepgram: ${redact(e.message)}`));
  // Deepgram closes a stream that's quiet for ten seconds: keep it open between phrases.
  const alive = setInterval(() => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'KeepAlive' })); }, 5000);
  ws.on('close', () => clearInterval(alive));
  return {
    engine: 'deepgram',
    append(pcm) { if (ws.readyState === WebSocket.OPEN) ws.send(pcm); },
    finish() {
      return new Promise<void>(resolve => {
        const t = setTimeout(() => resolve(), FLUSH_MS);
        closed = () => { clearTimeout(t); resolve(); };
        if (ws.readyState !== WebSocket.OPEN) return closed();
        ws.send(JSON.stringify({ type: 'CloseStream' })); // Deepgram sends the last results, then closes
      });
    },
    close() { clearInterval(alive); ws.close(); },
  };
}

function redact(s: string): string {
  return s.replace(/(sk-[\w-]{6,}|ek_[\w-]{6,}|eyJ[\w-]{20,})/g, '[key]');
}
