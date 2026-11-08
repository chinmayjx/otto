#!/usr/bin/env node
// Otto on Telegram (proof of concept): a Telegram bot that is one long-running Claude Code conversation on the box.
//
// Each message goes to `claude -p --resume <session>` in the working folder; while Claude works, one status message
// shows the tools it's running; the answer comes as a new message (so the phone notifies) and the status goes. Only
// the owner can use it: the first person to send `/start <code>` (printed when the bot starts unowned) is the owner.
//
// Config and state: ~/.config/otto/telegram.json  { token, owner?, session?, cwd? }
// Commands: /new (a fresh conversation), /stop (stop the running turn), /status.

import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import dns from 'node:dns';
import fs from 'node:fs';
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
const SYSTEM = [
  'You are talking with the user through a Telegram bot on their phone.',
  'Keep replies short and easy to read on a phone: plain sentences, short lists.',
  'Your Markdown is shown with Telegram formatting: **bold**, *italic*, __underline__, ~~strike~~, ||spoiler||,',
  '`code`, ```lang code blocks```, > quotes (first line `> [!expand]` makes one fold up; `> [!NOTE]`, `[!TIP]`,',
  '`[!WARNING]` give it a title), bullets, `- [ ]` tasks, and small tables (shown monospace). Headings show as bold lines.',
  'For anything long or visual (a report, a table, a plan, a page), publish an artifact with `otto artifact publish`',
  'and reply with a short summary and its link.',
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

// The answer goes out as new messages, not as an edit of the status message: Telegram notifies about a new message
// but not an edit, so the phone pings when the turn is done. The status message is deleted after.
async function send(chat, text, status) {
  for (const part of parts(text || '(no reply)')) {
    const opts = { chat_id: chat, link_preview_options: { is_disabled: true } };
    await call('sendMessage', { ...opts, text: html(part), parse_mode: 'HTML' })
      // Telegram rejects HTML it can't parse: send that part as plain text.
      .catch(e => { console.error('html', e.message); return call('sendMessage', { ...opts, text: part }); })
      .catch(e => console.error(e.message));
  }
  if (status) await call('deleteMessage', { chat_id: chat, message_id: status }).catch(() => {});
}

// --- One turn of Claude ----------------------------------------------------------------------------------------------

let running = null; // the claude process of the turn in progress
const queue = [];

function describe(tool) {
  const i = tool.input || {};
  const what = i.description || i.command || i.file_path || i.pattern || i.url || i.query || i.prompt || '';
  return `${tool.name}${what ? ': ' + String(what).split('\n')[0].slice(0, 80) : ''}`;
}

async function turn(chat, prompt) {
  const status = await call('sendMessage', { chat_id: chat, text: '⏳ Working…' });
  const steps = [];
  let shown = '';
  let last = 0;
  const show = async (force) => {
    const text = ['⏳ Working…', ...steps.slice(-6).map(s => '· ' + s)].join('\n');
    if (text === shown || (!force && Date.now() - last < 1500)) return;
    shown = text;
    last = Date.now();
    await call('editMessageText', { chat_id: chat, message_id: status.message_id, text }).catch(() => {});
  };
  const typing = setInterval(() => call('sendChatAction', { chat_id: chat, action: 'typing' }).catch(() => {}), 4500);

  const args = ['-p', prompt, '--output-format', 'stream-json', '--verbose', '--permission-mode', 'auto',
    '--append-system-prompt', SYSTEM];
  if (config.session) args.push('--resume', config.session);
  const p = spawn(CLAUDE, args, { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
  running = p;
  let result = null;
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
      if (e.type === 'assistant') {
        for (const c of e.message?.content || []) if (c.type === 'tool_use') steps.push(describe(c));
        show(false);
      } else if (e.type === 'result') result = e;
    }
  });
  p.stderr.on('data', d => { err += d; });
  const code = await new Promise(r => p.on('close', r));
  clearInterval(typing);
  running = null;

  if (result && !result.is_error) await send(chat, result.result, status.message_id);
  else if (code === null || code === 143) await send(chat, '⏹ Stopped.', status.message_id);
  else await send(chat, `⚠️ Claude failed (exit ${code}).\n${(result?.result || err).slice(-1500)}`, status.message_id);
}

async function drain() {
  if (running || !queue.length) return;
  const { chat, prompt } = queue.shift();
  try { await turn(chat, prompt); } catch (e) { console.error('turn', e); }
  drain();
}

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
  const chat = m.chat.id;
  const text = (m.text || m.caption || '').trim();
  if (!config.owner) {
    if (pairCode && text === `/start ${pairCode}`) {
      config.owner = m.from.id;
      save();
      await call('sendMessage', { chat_id: chat, text: 'Paired. This bot is a Claude Code conversation on your box: just write.' });
    }
    return;
  }
  if (m.from?.id !== config.owner) return; // anyone else: silence

  if (text === '/new') {
    config.session = null;
    save();
    return call('sendMessage', { chat_id: chat, text: '🆕 New conversation.' });
  }
  if (text === '/stop') {
    queue.length = 0;
    if (running) running.kill('SIGTERM');
    else await call('sendMessage', { chat_id: chat, text: 'Nothing running.' });
    return;
  }
  if (text === '/status') {
    return call('sendMessage', {
      chat_id: chat,
      text: `Session: ${config.session || '(new)'}\nFolder: ${cwd}\n${running ? 'Working' : 'Idle'}; ${queue.length} queued`,
    });
  }

  let prompt = text;
  if (m.photo?.length) prompt += `\n\n[The user sent a photo, saved at ${await download(m.photo.at(-1).file_id, 'photo.jpg')}. Look at it with the Read tool.]`;
  if (m.document) prompt += `\n\n[The user sent a file, saved at ${await download(m.document.file_id, m.document.file_name)}.]`;
  if (!prompt.trim()) return;
  queue.push({ chat, prompt });
  if (running) await call('sendMessage', { chat_id: chat, text: '📥 Queued: I’ll get to it after the current turn.' });
  drain();
}

let offset = 0;
await call('setMyCommands', { commands: [
  { command: 'new', description: 'Start a new conversation' },
  { command: 'stop', description: 'Stop the running turn' },
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
