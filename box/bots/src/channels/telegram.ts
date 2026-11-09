// Telegram as a bot's channel, through the Bot API: long polling for messages, and sendMessage, sendPhoto and
// sendDocument for replies. Text goes as Telegram's HTML (telegram-html.ts), split to fit its message limit.
import dns from 'node:dns';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import type { Channel, Command, Id, Incoming } from '../channel.js';
import { html } from './telegram-html.js';

// A box without an IPv6 route still gets Telegram's IPv6 addresses, and Node's happy-eyeballs gives the IPv4 attempt
// only 250ms before moving on to IPv6, which Telegram's API sometimes needs more than: IPv4 only.
dns.setDefaultResultOrder('ipv4first');
net.setDefaultAutoSelectFamily(false);

const LIMIT = 4000; // Telegram's message limit is 4096 characters
const IMAGE = /\.(jpe?g|png|webp)$/i;

const FORMATTING = [
  'Your Markdown is shown with Telegram formatting: **bold**, *italic*, __underline__, ~~strike~~, ||spoiler||,',
  '`code`, ```lang code blocks```, > quotes (first line `> [!expand]` makes one fold up; `> [!NOTE]`, `[!TIP]`,',
  '`[!WARNING]` give it a title), bullets, `- [ ]` tasks, and small tables (shown monospace). Headings show as bold lines.',
].join(' ');

/** Long text in parts, split at paragraph breaks where possible. */
export function parts(text: string): string[] {
  const out: string[] = [];
  let rest = text;
  while (rest.length > LIMIT) {
    let cut = rest.lastIndexOf('\n\n', LIMIT);
    if (cut < LIMIT / 2) cut = rest.lastIndexOf('\n', LIMIT);
    if (cut < LIMIT / 2) cut = LIMIT;
    out.push(rest.slice(0, cut));
    rest = rest.slice(cut).replace(/^\n+/, '');
  }
  if (rest.trim()) out.push(rest);
  return out;
}

/** Calls one Bot API method with a token: its result, or an error with Telegram's description. */
export async function telegram(token: string, method: string, body: unknown, signal?: AbortSignal): Promise<any> {
  const r = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });
  const j: any = await r.json();
  if (!j.ok) throw new Error(`${method}: ${j.description}`);
  return j.result;
}

export class Telegram implements Channel {
  readonly label = 'Telegram';
  readonly formatting = FORMATTING;
  handle: string | null = null;
  private abort = new AbortController();
  private log: (m: string) => void;

  constructor(private token: string, log: (m: string) => void = console.error) {
    this.log = log;
  }

  private call(method: string, body: unknown) {
    return telegram(this.token, method, body, this.abort.signal);
  }

  async start(onMessage: (m: Incoming) => void): Promise<void> {
    const me = await this.call('getMe', {});
    this.handle = `@${me.username}`;
    void this.poll(onMessage);
  }

  stop(): void {
    this.abort.abort();
  }

  // Long polling, until stopped. A failed poll (the network, or Telegram's 409 while an old poller winds down) waits
  // a few seconds and tries again.
  private async poll(onMessage: (m: Incoming) => void) {
    let offset = 0;
    while (!this.abort.signal.aborted) {
      try {
        const updates = await this.call('getUpdates', { offset, timeout: 50, allowed_updates: ['message'] });
        for (const u of updates) {
          offset = u.update_id + 1;
          if (u.message) onMessage(this.incoming(u.message));
        }
      } catch (e: any) {
        if (this.abort.signal.aborted) return;
        this.log(`poll: ${e.message}`);
        await new Promise(r => setTimeout(r, 3000));
      }
    }
  }

  private incoming(m: any): Incoming {
    const attachments: Incoming['attachments'] = [];
    if (m.photo?.length) attachments.push({ kind: 'photo', name: 'photo.jpg', download: dir => this.download(m.photo.at(-1).file_id, dir, 'photo.jpg') });
    if (m.document) attachments.push({ kind: 'file', name: m.document.file_name, download: dir => this.download(m.document.file_id, dir, m.document.file_name) });
    return { chat: m.chat.id, from: m.from?.id, text: (m.text || m.caption || '').trim(), attachments };
  }

  private async download(fileId: string, dir: string, name?: string): Promise<string> {
    const f = await this.call('getFile', { file_id: fileId });
    const r = await fetch(`https://api.telegram.org/file/bot${this.token}/${f.file_path}`, { signal: this.abort.signal });
    if (!r.ok) throw new Error(`download: HTTP ${r.status}`);
    fs.mkdirSync(dir, { recursive: true });
    const out = path.join(dir, `${Date.now()}-${path.basename(name || f.file_path)}`);
    fs.writeFileSync(out, Buffer.from(await r.arrayBuffer()));
    return out;
  }

  pairLink(code: string): string | null {
    return this.handle ? `https://t.me/${this.handle.slice(1)}?start=${code}` : null;
  }

  // Telegram rejects HTML it can't parse: that part goes as plain text.
  async send(chat: Id, text: string): Promise<void> {
    for (const part of parts(text || '(empty)')) {
      const opts = { chat_id: chat, link_preview_options: { is_disabled: true } };
      await this.call('sendMessage', { ...opts, text: html(part), parse_mode: 'HTML' })
        .catch(e => { this.log(`html: ${e.message}`); return this.call('sendMessage', { ...opts, text: part }); });
    }
  }

  // A photo when it's an image under 10 MB, otherwise a document (Telegram's limit is 50 MB).
  async sendFile(chat: Id, file: string, caption?: string): Promise<void> {
    if (!path.isAbsolute(file)) throw new Error('the path must be absolute');
    const size = fs.statSync(file).size;
    if (size > 50 * 1024 * 1024) throw new Error('over Telegram\'s 50 MB limit');
    const photo = IMAGE.test(file) && size < 10 * 1024 * 1024;
    const form = new FormData();
    form.append('chat_id', String(chat));
    form.append(photo ? 'photo' : 'document', new Blob([fs.readFileSync(file)]), path.basename(file));
    if (caption) { form.append('caption', html(String(caption).slice(0, 1000))); form.append('parse_mode', 'HTML'); }
    const r = await fetch(`https://api.telegram.org/bot${this.token}/${photo ? 'sendPhoto' : 'sendDocument'}`, { method: 'POST', body: form, signal: this.abort.signal });
    const j: any = await r.json();
    if (!j.ok) throw new Error(j.description);
  }

  async typing(chat: Id): Promise<void> {
    await this.call('sendChatAction', { chat_id: chat, action: 'typing' });
  }

  async setCommands(commands: Command[]): Promise<void> {
    await this.call('setMyCommands', { commands });
  }
}
