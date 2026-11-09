#!/usr/bin/env node
// The Telegram bot's tools for Claude, an MCP server on stdio that Claude Code runs only for the bot's turns
// (bot.mjs passes it with --mcp-config). Each call goes to the bot over its socket (OTTO_TELEGRAM_SOCK), which sends
// it to the owner's chat: this server holds no token and can't reach anyone else.
//
//   send_message(text)            a message to the user, in Markdown
//   send_file(path, caption?)     a file from the box, as a photo when it's an image

import http from 'node:http';
import readline from 'node:readline';

const SOCK = process.env.OTTO_TELEGRAM_SOCK;

const TOOLS = [
  {
    name: 'send_message',
    description: 'Send a message to the user on Telegram. This is the only way the user sees anything from you: your '
      + 'plain final text is not shown. Send a short heads-up before long work, then the result; several messages '
      + 'in a turn are fine. Markdown, kept short for a phone screen.',
    inputSchema: {
      type: 'object',
      properties: { text: { type: 'string', description: 'The message, in Markdown' } },
      required: ['text'],
    },
  },
  {
    name: 'send_file',
    description: 'Send a file from this machine to the user on Telegram (up to 50 MB; images show as photos). Make '
      + 'the file first, then pass its absolute path.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Absolute path of the file' },
        caption: { type: 'string', description: 'A short caption, in Markdown' },
      },
      required: ['path'],
    },
  },
];

// One request to the bot over its socket: the reply's text, or an error.
function bot(body) {
  return new Promise((resolve, reject) => {
    const req = http.request({ socketPath: SOCK, path: '/send', method: 'POST', headers: { 'content-type': 'application/json' } }, res => {
      let data = '';
      res.on('data', d => { data += d; });
      res.on('end', () => (res.statusCode === 200 ? resolve(data) : reject(new Error(data || `HTTP ${res.statusCode}`))));
    });
    req.on('error', reject);
    req.end(JSON.stringify(body));
  });
}

const write = msg => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...msg }) + '\n');

readline.createInterface({ input: process.stdin }).on('line', async line => {
  let m;
  try { m = JSON.parse(line); } catch { return; }
  if (m.id === undefined) return; // notifications
  if (m.method === 'initialize') {
    return write({ id: m.id, result: {
      protocolVersion: m.params?.protocolVersion || '2025-06-18',
      capabilities: { tools: {} },
      serverInfo: { name: 'telegram', version: '0.1.0' },
    } });
  }
  if (m.method === 'tools/list') return write({ id: m.id, result: { tools: TOOLS } });
  if (m.method === 'tools/call') {
    const { name, arguments: a = {} } = m.params || {};
    try {
      if (name === 'send_message') await bot({ kind: 'message', text: String(a.text ?? '') });
      else if (name === 'send_file') await bot({ kind: 'file', path: String(a.path ?? ''), caption: a.caption });
      else throw new Error(`unknown tool ${name}`);
      return write({ id: m.id, result: { content: [{ type: 'text', text: 'sent' }] } });
    } catch (e) {
      return write({ id: m.id, result: { content: [{ type: 'text', text: `not sent: ${e.message}` }], isError: true } });
    }
  }
  write({ id: m.id, error: { code: -32601, message: `unknown method ${m.method}` } });
});
