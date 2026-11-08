// Clips: what the user collected on the phone (the lens, or notes on an artifact) for an agent to read, kept on the
// box. The app uploads a clip through the tunnel (a pipe it dials itself: tunnel.ts "dial"), and gets back a short
// id it puts on the phone's clipboard as "run `otto clip <id>`"; the agent reads it with that command.
//
// On disk, one folder per clip under clips_dir (~/.local/state/otto/clips):
//   <id>/clip.json   {id, created, text, images: [{file, width, height}], source?, thumb?}
//   <id>/1.png …     the images, in order
//   <id>/thumb.jpg   a small picture of the first image, made by the app for its list (optional)
// source: {kind: "screen", app, label?} for the lens, or
//         {kind: "artifact", id, version, title, url, notes: [{where: "p.4" | "§ Heading", text}]}.
//
// The same routes serve both sides: the relay's local API (the CLI) and the box API the phone reaches through its
// pipes. Only the phone uploads.
//   GET    /clips[?limit=N]          newest first
//   GET    /clips/<id>               the clip (with "dir", where it is on the box)
//   GET    /clips/<id>/<n>.png       an image; /clips/<id>/thumb.jpg its thumbnail
//   DELETE /clips/<id>
//   POST   /clips                    (phone only) multipart/form-data: a "clip" part (JSON: text, source), then
//                                    "image" parts (PNGs, in order) and an optional "thumb" part (a JPEG)
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import type http from 'node:http';

/** Crockford's base32, lowercase: no i, l, o or u, so nothing reads as something else. */
const ALPHABET = '0123456789abcdefghjkmnpqrstvwxyz';
const ID = /^[0-9a-z]{6}$/;
const KEEP = 200;
const KEEP_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_UPLOAD = 128 << 20;

export interface Image { file: string; width: number; height: number }
export interface Clip {
  id: string;
  created: string;
  text: string;
  images: Image[];
  source?: Record<string, any>;
  thumb?: string;
}

/** A clip id as typed: lowercase, and the letters that look like digits read as those digits. */
export function normalizeId(s: string): string {
  return s.trim().toLowerCase().replace(/o/g, '0').replace(/[il]/g, '1');
}

export class Clips {
  constructor(readonly dir: string) {}

  private newId(): string {
    for (;;) {
      const b = crypto.randomBytes(6);
      const id = [...b].map(x => ALPHABET[x & 31]).join('');
      if (!fs.existsSync(path.join(this.dir, id))) return id;
    }
  }

  /** A new clip from its text, images (PNG bytes) and source; the thumbnail is optional. */
  add(text: string, pngs: Buffer[], source?: Record<string, any>, thumb?: Buffer): Clip {
    const images = pngs.map((b, i) => ({ file: `${i + 1}.png`, ...pngSize(b) }));
    fs.mkdirSync(this.dir, { recursive: true });
    const id = this.newId();
    // Written beside it, then moved into place: a clip is there whole or not at all.
    const tmp = path.join(this.dir, `.${id}.tmp`);
    fs.mkdirSync(tmp, { recursive: true });
    pngs.forEach((b, i) => fs.writeFileSync(path.join(tmp, images[i].file), b));
    const clip: Clip = { id, created: new Date().toISOString(), text, images };
    if (source && typeof source === 'object') clip.source = source;
    if (thumb?.length) {
      fs.writeFileSync(path.join(tmp, 'thumb.jpg'), thumb);
      clip.thumb = 'thumb.jpg';
    }
    fs.writeFileSync(path.join(tmp, 'clip.json'), JSON.stringify(clip, null, 1) + '\n');
    fs.renameSync(tmp, path.join(this.dir, id));
    this.prune();
    return clip;
  }

  get(id: string): Clip | null {
    id = normalizeId(id);
    if (!ID.test(id)) return null;
    try { return JSON.parse(fs.readFileSync(path.join(this.dir, id, 'clip.json'), 'utf8')); } catch { return null; }
  }

  /** Newest first. */
  list(limit = KEEP): Clip[] {
    let names: string[] = [];
    try { names = fs.readdirSync(this.dir).filter(n => ID.test(n)); } catch {}
    const all = names.map(n => this.get(n)).filter((c): c is Clip => !!c);
    all.sort((a, b) => b.created.localeCompare(a.created));
    return all.slice(0, limit);
  }

  remove(id: string): boolean {
    const c = this.get(id);
    if (!c) return false;
    fs.rmSync(path.join(this.dir, c.id), { recursive: true, force: true });
    return true;
  }

  /** The file of one of a clip's images (n.png) or its thumbnail, if there is one. */
  file(id: string, name: string): string | null {
    const c = this.get(id);
    if (!c) return null;
    if (name !== c.thumb && !c.images.some(i => i.file === name)) return null;
    return path.join(this.dir, c.id, name);
  }

  /** The newest 200 stay, and nothing older than 30 days. */
  prune() {
    const old = Date.now() - KEEP_MS;
    this.list(Infinity).forEach((c, i) => {
      if (i >= KEEP || Date.parse(c.created) < old) fs.rmSync(path.join(this.dir, c.id), { recursive: true, force: true });
    });
    // Uploads that died half-way.
    try {
      for (const n of fs.readdirSync(this.dir)) {
        const f = path.join(this.dir, n);
        if (n.endsWith('.tmp') && fs.statSync(f).mtimeMs < Date.now() - 3600_000) fs.rmSync(f, { recursive: true, force: true });
      }
    } catch {}
  }

  /**
   * Serves the clip routes, if the request is one of them (else false). upload: whether POST /clips is allowed
   * (only from the phone).
   */
  async serve(req: http.IncomingMessage, res: http.ServerResponse, upload: boolean): Promise<boolean> {
    const u = new URL(req.url ?? '/', 'http://x');
    const m = u.pathname.match(/^\/clips(?:\/([0-9A-Za-z]{6})(?:\/([\w.]+))?)?\/?$/);
    if (!m) return false;
    const send = (code: number, body: unknown) => {
      res.writeHead(code, { 'content-type': typeof body === 'string' ? 'text/plain' : 'application/json' });
      res.end(typeof body === 'string' ? body + '\n' : JSON.stringify(body, null, 1) + '\n');
    };
    const [, id, name] = m;
    const withDir = (c: Clip) => ({ ...c, dir: path.join(this.dir, c.id) });
    if (!id) {
      if (req.method === 'GET') return send(200, this.list(Number(u.searchParams.get('limit')) || KEEP).map(withDir)), true;
      if (req.method === 'POST' && upload) {
        try {
          const { clip, images, thumb } = parseUpload(String(req.headers['content-type'] ?? ''), await body(req));
          return send(201, withDir(this.add(String(clip.text ?? ''), images, clip.source, thumb))), true;
        } catch (e: any) {
          return send(400, `clip: ${e.message}`), true;
        }
      }
      return send(405, 'clips: GET only'), true;
    }
    if (!name) {
      if (req.method === 'GET') {
        const c = this.get(id);
        return (c ? send(200, withDir(c)) : send(404, `no clip ${id}`)), true;
      }
      if (req.method === 'DELETE') return (this.remove(id) ? send(200, 'deleted') : send(404, `no clip ${id}`)), true;
      return send(405, 'GET or DELETE'), true;
    }
    const f = req.method === 'GET' ? this.file(id, name) : null;
    if (!f) return send(404, `no ${name} in clip ${id}`), true;
    const data = fs.readFileSync(f);
    res.writeHead(200, {
      'content-type': name.endsWith('.png') ? 'image/png' : 'image/jpeg',
      'content-length': data.length,
      'cache-control': 'max-age=31536000, immutable', // a clip never changes
    });
    res.end(data);
    return true;
  }
}

/** A PNG's size, from its header (which also shows it is one). */
export function pngSize(b: Buffer): { width: number; height: number } {
  if (b.length < 24 || b.readUInt32BE(0) !== 0x89504e47 || b.toString('latin1', 12, 16) !== 'IHDR') throw new Error('an image is not a PNG');
  return { width: b.readUInt32BE(16), height: b.readUInt32BE(20) };
}

function body(req: http.IncomingMessage): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let n = 0;
    req.on('data', c => {
      n += c.length;
      if (n > MAX_UPLOAD) return void req.destroy(new Error('too big'));
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

/** An upload's parts (multipart/form-data): "clip" (JSON), "image" (PNGs, in order), "thumb" (a JPEG). */
export function parseUpload(type: string, data: Buffer): { clip: Record<string, any>; images: Buffer[]; thumb?: Buffer } {
  const boundary = type.match(/boundary="?([^";]+)"?/i)?.[1];
  if (!/^multipart\/form-data/i.test(type) || !boundary) throw new Error('expected multipart/form-data');
  const delim = Buffer.from(`--${boundary}`);
  let clip: Record<string, any> | null = null, thumb: Buffer | undefined;
  const images: Buffer[] = [];
  let at = data.indexOf(delim);
  while (at >= 0) {
    const start = at + delim.length;
    if (data.subarray(start, start + 2).toString() === '--') break; // the closing delimiter
    const head = data.indexOf('\r\n\r\n', start);
    if (head < 0) break;
    const next = data.indexOf(Buffer.concat([Buffer.from('\r\n'), delim]), head);
    if (next < 0) throw new Error('an unfinished part');
    const headers = data.subarray(start, head).toString('utf8');
    const content = data.subarray(head + 4, next);
    const name = headers.match(/name="([^"]*)"/i)?.[1];
    if (name === 'clip') clip = JSON.parse(content.toString('utf8'));
    else if (name === 'image') images.push(content);
    else if (name === 'thumb') thumb = content;
    at = next + 2;
  }
  if (!clip) throw new Error('no "clip" part');
  return { clip, images, thumb };
}
