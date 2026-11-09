#!/usr/bin/env node
// A bot's tools for its agent: an MCP server on stdio, which the agent runs for the bot's conversation (the
// provider passes it in, e.g. with Claude's --mcp-config). Each call goes to the bot over its socket
// (OTTO_BOT_SOCKET), which sends it to the owner's chat: this server holds no token and can't reach anyone else.
//
//   send_message(channel, text)          a message to the user on one of the bot's channels, in Markdown
//   send_file(channel, path, caption?)   a file from the box, as a photo when it's an image
// The channels come in OTTO_BOT_CHANNELS ([{kind, label}]); with one channel, `channel` may be left out.
import http from 'node:http';
import readline from 'node:readline';

const SOCK = process.env.OTTO_BOT_SOCKET;
const CHANNELS: { kind: string; label: string }[] = JSON.parse(process.env.OTTO_BOT_CHANNELS || '[]');
const ON = CHANNELS.length ? CHANNELS.map(c => `${c.kind} (${c.label})`).join(', ') : 'their chat app';
const channel = {
  type: 'string',
  ...(CHANNELS.length ? { enum: CHANNELS.map(c => c.kind) } : {}),
  description: 'The channel to send it on: the one the message you answer came from, unless it belongs elsewhere',
};
const required = (...r: string[]) => (CHANNELS.length > 1 ? ['channel', ...r] : r);

const TOOLS = [
  {
    name: 'send_message',
    description: `Send a message to the user on one of their channels: ${ON}. This is the only way the user sees anything from you: your `
      + 'plain final text is not shown. Send a short heads-up before long work, then the result; several messages '
      + 'in a turn are fine. Follow the channel\'s own rules: Markdown for a chat, plain speakable sentences for a voice.',
    inputSchema: {
      type: 'object',
      properties: { channel, text: { type: 'string', description: 'The message' } },
      required: required('text'),
    },
  },
  {
    name: 'send_file',
    description: `Send a file from this machine to the user on a channel that shows files (${ON}; up to 50 MB; images `
      + 'show as photos). Make the file first, then pass its absolute path.',
    inputSchema: {
      type: 'object',
      properties: {
        channel,
        path: { type: 'string', description: 'Absolute path of the file' },
        caption: { type: 'string', description: 'A short caption, in Markdown' },
      },
      required: required('path'),
    },
  },
];

/** One request to the bot over its socket: the reply's text, or an error. */
function bot(body: unknown): Promise<string> {
  return new Promise((resolve, reject) => {
    if (!SOCK) return reject(new Error('OTTO_BOT_SOCKET is not set'));
    const req = http.request({ socketPath: SOCK, path: '/send', method: 'POST', headers: { 'content-type': 'application/json' } }, res => {
      let data = '';
      res.on('data', d => { data += d; });
      res.on('end', () => (res.statusCode === 200 ? resolve(data) : reject(new Error(data || `HTTP ${res.statusCode}`))));
    });
    req.on('error', reject);
    req.end(JSON.stringify(body));
  });
}

const write = (msg: Record<string, unknown>) => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...msg }) + '\n');

readline.createInterface({ input: process.stdin }).on('line', async line => {
  let m: any;
  try { m = JSON.parse(line); } catch { return; }
  if (m.id === undefined) return; // notifications
  if (m.method === 'initialize') {
    return write({ id: m.id, result: {
      protocolVersion: m.params?.protocolVersion || '2025-06-18',
      capabilities: { tools: {} },
      serverInfo: { name: 'otto-bot', version: '0.1.0' },
    } });
  }
  if (m.method === 'tools/list') return write({ id: m.id, result: { tools: TOOLS } });
  if (m.method === 'tools/call') {
    const { name, arguments: a = {} } = m.params || {};
    try {
      if (name === 'send_message') await bot({ kind: 'message', channel: a.channel, text: String(a.text ?? '') });
      else if (name === 'send_file') await bot({ kind: 'file', channel: a.channel, path: String(a.path ?? ''), caption: a.caption });
      else throw new Error(`unknown tool ${name}`);
      return write({ id: m.id, result: { content: [{ type: 'text', text: 'sent' }] } });
    } catch (e: any) {
      return write({ id: m.id, result: { content: [{ type: 'text', text: `not sent: ${e.message}` }], isError: true } });
    }
  }
  write({ id: m.id, error: { code: -32601, message: `unknown method ${m.method}` } });
});
