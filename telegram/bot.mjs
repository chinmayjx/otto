#!/usr/bin/env node
// Otto on Telegram (proof of concept): a Telegram bot that is one long-running Claude Code conversation on the box.
//
// One Claude process runs for the conversation (`claude -p --input-format stream-json`, resumed by session id), and
// every message from the owner goes straight to it: while Claude is working, a message steers the turn in progress;
// while it's idle, it starts one. Claude answers only through its tools (tools.mjs: send_message, send_file), which
// reach this bot over a socket; a turn that ends without sending anything gets one reminder, then a notice. Only the
// owner can use it: the first person to send `/start <code>` (printed when the bot starts unowned) is the owner.
//
// Config and state: ~/.config/otto/telegram.json  { token, owner?, session?, cwd? }
// Commands: /new (a fresh conversation), /stop (stop Claude; the next message resumes the conversation), /status.

import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import dns from 'node:dns';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { html } from './format.mjs';

// The box has no IPv6 route, and Node's happy-eyeballs gives the IPv4 attempt only 250ms before moving on to IPv6,
// which Telegram's API sometimes needs more than: IPv4 only.
dns.setDefaultResultOrder('ipv4first');
net.setDefaultAutoSelectFamily(false);

const CONFIG = path.join(os.homedir(), '.config/otto/telegram.json');
const FILES = path.join(os.homedir(), '.local/state/otto/telegram/files');
const CLAUDE = process.env.CLAUDE_BIN || path.join(os.homedir(), '.local/bin/claude');
const LIMIT = 4000; // Telegram's message limit is 4096 characters
const SOCK = path.join(os.homedir(), '.local/state/otto/telegram/bot.sock');
const TOOLS = path.join(path.dirname(new URL(import.meta.url).pathname), 'tools.mjs');
const SYSTEM = [
  'You are talking with the user through a Telegram bot on their phone.',
  'The user sees only what you send with the send_message and send_file tools (mcp__telegram__*); your plain text',
  'is not shown. Every message from the user needs at least one send_message back. For work that takes more than a',
  'moment, send a short heads-up first, then the result. New messages from the user can arrive while you work: take',
  'them into account as you go. Attach files you make with send_file.',
  'Keep messages short and easy to read on a phone: plain sentences, short lists.',
  'Your Markdown is shown with Telegram formatting: **bold**, *italic*, __underline__, ~~strike~~, ||spoiler||,',
  '`code`, ```lang code blocks```, > quotes (first line `> [!expand]` makes one fold up; `> [!NOTE]`, `[!TIP]`,',
  '`[!WARNING]` give it a title), bullets, `- [ ]` tasks, and small tables (shown monospace). Headings show as bold lines.',
  'For anything long or visual (a report, a table, a plan, a page), publish an artifact with `otto artifact publish`',
  'and send a short summary and its link.',
].join(' ');

let config = JSON.parse(fs.readFileSync(CONFIG, 'utf8'));
const save = () => fs.writeFileSync(CONFIG, JSON.stringify(config, null, 2), { mode: 0o600 });
const cwd = config.cwd || path.join(os.homedir(), 'code');
const api = `https://api.telegram.org/bot${config.token}`;
const pairCode = config.owner ? null : randomBytes(4).toString('hex');
if (pairCode) console.log(`unowned: send "/start ${pairCode}" to the bot to claim it`);

async function call(method, body) {
  const r = await fetch(`${api}/${method}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  const j = await r.json();
  if (!j.ok) throw new Error(`${method}: ${j.description}`);
  return j.result;
}

// Long text in parts, split at paragraph breaks where possible.
function parts(text) {
  const out = [];
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

// A message to the owner, in parts when it's long. Telegram rejects HTML it can't parse: that part goes as plain text.
async function send(chat, text) {
  for (const part of parts(text || '(empty)')) {
    const opts = { chat_id: chat, link_preview_options: { is_disabled: true } };
    await call('sendMessage', { ...opts, text: html(part), parse_mode: 'HTML' })
      .catch(e => { console.error('html', e.message); return call('sendMessage', { ...opts, text: part }); });
  }
}

const IMAGE = /\.(jpe?g|png|webp)$/i;

// A file from the box: a photo when it's an image under 10 MB, otherwise a document (Telegram's limit is 50 MB).
async function sendFile(chat, file, caption) {
  if (!path.isAbsolute(file)) throw new Error('the path must be absolute');
  const size = fs.statSync(file).size;
  if (size > 50 * 1024 * 1024) throw new Error('over Telegram\'s 50 MB limit');
  const photo = IMAGE.test(file) && size < 10 * 1024 * 1024;
  const form = new FormData();
  form.append('chat_id', String(chat));
  form.append(photo ? 'photo' : 'document', new Blob([fs.readFileSync(file)]), path.basename(file));
  if (caption) { form.append('caption', html(String(caption).slice(0, 1000))); form.append('parse_mode', 'HTML'); }
  const r = await fetch(`${api}/${photo ? 'sendPhoto' : 'sendDocument'}`, { method: 'POST', body: form });
  const j = await r.json();
  if (!j.ok) throw new Error(j.description);
}

// --- Claude ---------------------------------------------------------------------------------------------------------

let claude = null; // the Claude process, while there is one
let chat = null; // where its messages go: the owner's chat
let busy = false; // a turn is in progress
let sent = false; // the turn in progress has sent the user something
let reminded = false; // the turn in progress was reminded to reply
let typing = null;

function start() {
  const mcp = { mcpServers: { telegram: { command: process.execPath, args: [TOOLS], env: { OTTO_TELEGRAM_SOCK: SOCK } } } };
  const args = ['-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose',
    '--permission-mode', 'auto', '--append-system-prompt', SYSTEM, '--mcp-config', JSON.stringify(mcp),
    '--allowedTools', 'mcp__telegram__send_message', 'mcp__telegram__send_file'];
  if (config.session) args.push('--resume', config.session);
  const p = spawn(CLAUDE, args, { cwd, stdio: ['pipe', 'pipe', 'pipe'] });
  claude = p;
  let err = '';
  let buf = '';
  p.stdout.on('data', d => {
    buf += d;
    let n;
    while ((n = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, n);
      buf = buf.slice(n + 1);
      let e;
      try { e = JSON.parse(line); } catch { continue; }
      if (e.session_id && e.session_id !== config.session) { config.session = e.session_id; save(); }
      if (e.type === 'assistant' && !busy) begin(); // a turn Claude started on its own (a message it took late)
      if (e.type === 'result') ended(e);
    }
  });
  p.stderr.on('data', d => { err = (err + d).slice(-3000); });
  p.on('close', code => {
    if (claude !== p) return;
    claude = null;
    const was = busy;
    finish();
    if (was && code !== null && code !== 143) send(chat, `⚠️ Claude stopped (exit ${code}).\n${err.slice(-1500)}`).catch(() => {});
  });
}

function begin() {
  busy = true;
  sent = false;
  reminded = false;
  clearInterval(typing);
  const ping = () => call('sendChatAction', { chat_id: chat, action: 'typing' }).catch(() => {});
  ping();
  typing = setInterval(ping, 4500);
}

function finish() {
  busy = false;
  clearInterval(typing);
  typing = null;
}

function write(text) {
  if (!claude) start();
  claude.stdin.write(JSON.stringify({ type: 'user', message: { role: 'user', content: text } }) + '\n');
}

// The end of a turn. One that sent the user nothing is reminded once, then the user gets a notice.
function ended(result) {
  if (result.is_error) {
    finish();
    return send(chat, `⚠️ ${String(result.result || 'Claude failed').slice(-1500)}`).catch(() => {});
  }
  if (sent) return finish();
  if (!reminded) {
    reminded = true;
    return write('[From the bot, not the user: you ended your turn without sending the user anything, and they see '
      + 'only what you send with send_message. Reply to them now with send_message.]');
  }
  finish();
  const last = String(result.result || '').trim();
  send(chat, `⚠️ Otto didn't reply.${last ? `\nIts last words: ${last.slice(0, 1500)}` : ''}`).catch(() => {});
}

// The tools' requests (tools.mjs), over a socket only this user can reach.
fs.mkdirSync(path.dirname(SOCK), { recursive: true });
fs.rmSync(SOCK, { force: true });
http.createServer((req, res) => {
  let body = '';
  req.on('data', d => { body += d; });
  req.on('end', async () => {
    try {
      const m = JSON.parse(body);
      if (!chat) throw new Error('no chat yet');
      if (m.kind === 'message') await send(chat, m.text);
      else if (m.kind === 'file') await sendFile(chat, m.path, m.caption);
      else throw new Error('unknown kind');
      sent = true;
      res.writeHead(200).end('sent');
    } catch (e) {
      res.writeHead(400).end(e.message);
    }
  });
}).listen(SOCK, () => fs.chmodSync(SOCK, 0o600));

// --- Messages ------------------------------------------------------------------------------------------------------

async function download(fileId, name) {
  const f = await call('getFile', { file_id: fileId });
  const r = await fetch(`https://api.telegram.org/file/bot${config.token}/${f.file_path}`);
  fs.mkdirSync(FILES, { recursive: true });
  const out = path.join(FILES, `${Date.now()}-${name || path.basename(f.file_path)}`);
  fs.writeFileSync(out, Buffer.from(await r.arrayBuffer()));
  return out;
}

async function onMessage(m) {
  const text = (m.text || m.caption || '').trim();
  if (!config.owner) {
    if (pairCode && text === `/start ${pairCode}`) {
      config.owner = m.from.id;
      save();
      await call('sendMessage', { chat_id: m.chat.id, text: 'Paired. This bot is a Claude Code conversation on your box: just write.' });
    }
    return;
  }
  if (m.from?.id !== config.owner) return; // anyone else: silence

  if (text === '/new' || text === '/stop') {
    const p = claude;
    claude = null;
    finish();
    p?.kill('SIGTERM');
    if (text === '/new') {
      config.session = null;
      save();
      return call('sendMessage', { chat_id: m.chat.id, text: '🆕 New conversation.' });
    }
    return call('sendMessage', { chat_id: m.chat.id, text: p ? '⏹ Stopped.' : 'Nothing running.' });
  }
  if (text === '/status') {
    return call('sendMessage', {
      chat_id: m.chat.id,
      text: `Session: ${config.session || '(new)'}\nFolder: ${cwd}\n${busy ? 'Working' : claude ? 'Idle' : 'Not running'}`,
    });
  }

  let prompt = text;
  if (m.photo?.length) prompt += `\n\n[The user sent a photo, saved at ${await download(m.photo.at(-1).file_id, 'photo.jpg')}. Look at it with the Read tool.]`;
  if (m.document) prompt += `\n\n[The user sent a file, saved at ${await download(m.document.file_id, m.document.file_name)}.]`;
  if (!prompt.trim()) return;
  chat = m.chat.id;
  if (!busy) begin(); // otherwise it steers the turn in progress
  write(prompt);
}

let offset = 0;
await call('setMyCommands', { commands: [
  { command: 'new', description: 'Start a new conversation' },
  { command: 'stop', description: 'Stop Claude (the next message resumes)' },
  { command: 'status', description: 'Session and state' },
] }).catch(e => console.error(e.message));
console.log(`otto-telegram: polling; folder ${cwd}; session ${config.session || '(new)'}`);
for (;;) {
  try {
    const updates = await call('getUpdates', { offset, timeout: 50, allowed_updates: ['message'] });
    for (const u of updates) {
      offset = u.update_id + 1;
      if (u.message) onMessage(u.message).catch(e => console.error('message', e));
    }
  } catch (e) {
    console.error('poll', e.message);
    await new Promise(r => setTimeout(r, 3000));
  }
}
