// The bot's core against a fake channel and a fake agent: pairing, the owner-only rule, commands, steering, the
// reply rule, and the tools' socket (as tools.ts reaches it, and through the real tools server).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import type { Channel, Command, Id, Incoming } from '../channel.js';
import type { BotConfig } from '../config.js';
import { Bot } from '../core.js';
import type { Agent, AgentEvents, AgentOptions, Provider } from '../provider.js';

class FakeChannel implements Channel {
  label = 'Fake';
  formatting = 'Markdown shows as is.';
  handle = '@fake_bot';
  sent: { chat: Id; text?: string; file?: string }[] = [];
  typings = 0;
  commands: Command[] = [];
  deliver!: (m: Incoming) => void;
  async start(on: (m: Incoming) => void) { this.deliver = on; }
  stop() {}
  pairLink(code: string) { return `https://fake/${code}`; }
  async send(chat: Id, text: string) { this.sent.push({ chat, text }); }
  async sendFile(chat: Id, file: string) { this.sent.push({ chat, file }); }
  async typing() { this.typings++; }
  async setCommands(c: Command[]) { this.commands = c; }
  texts() { return this.sent.map(s => s.text); }
}

class FakeAgent implements Agent {
  running = false;
  writes: string[] = [];
  starts = 0;
  constructor(public options: AgentOptions, public events: AgentEvents) {}
  write(text: string) { if (!this.running) { this.running = true; this.starts++; } this.writes.push(text); }
  stop() { this.running = false; }
}

function setup(config: Partial<BotConfig> = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'otto-bots-test-'));
  const channel = new FakeChannel();
  let agent!: FakeAgent;
  const provider: Provider = { label: 'Agent', toolName: t => `x_${t}`, create: (o, e) => (agent = new FakeAgent(o, e)) };
  let file: BotConfig = { channel: 'fake', token: 't', ...config };
  const logs: string[] = [];
  const bot = new Bot({
    name: 'test', config: file, channel, provider, stateDir: dir, tools: { command: 'node', args: [] },
    save: patch => (file = { ...file, ...patch }), log: m => logs.push(m), typingEvery: 20,
  });
  const msg = (text: string, from: Id = 42, attachments: Incoming['attachments'] = []) => bot.onMessage({ chat: 7, from, text, attachments });
  return { dir, channel, bot, msg, logs, agent: () => agent, file: () => file };
}

const status = (dir: string) => JSON.parse(fs.readFileSync(path.join(dir, 'status.json'), 'utf8'));

/** A request to the bot's socket, as tools.ts makes it. */
function tool(dir: string, body: unknown): Promise<{ status: number; text: string }> {
  return new Promise((resolve, reject) => {
    const req = http.request({ socketPath: path.join(dir, 'tools.sock'), path: '/send', method: 'POST' }, res => {
      let text = '';
      res.on('data', d => { text += d; });
      res.on('end', () => resolve({ status: res.statusCode!, text }));
    });
    req.on('error', reject);
    req.end(JSON.stringify(body));
  });
}

test('an unowned bot pairs with whoever sends its code, and then answers only them', async () => {
  const t = setup();
  await t.bot.start();
  const s = status(t.dir);
  assert.equal(s.owned, false);
  assert.match(s.pair.code, /^[0-9a-f]{8}$/);
  assert.equal(s.pair.link, `https://fake/${s.pair.code}`);
  assert.equal(t.channel.commands.map(c => c.command).join(), 'new,stop,status');

  await t.msg('/start wrong', 1);
  await t.msg('hello', 1);
  assert.equal(t.channel.sent.length, 0);
  await t.msg(`/start ${s.pair.code}`, 42);
  assert.equal(t.file().owner, 42);
  assert.match(t.channel.texts()[0]!, /^Paired/);
  assert.equal(status(t.dir).pair, null);

  await t.msg('from someone else', 1);
  assert.equal(t.agent().writes.length, 0);
  await t.msg('from the owner');
  assert.deepEqual(t.agent().writes, ['from the owner']);
  t.bot.stop();
});

test('the instructions name the tools the agent\'s way, and the tools get the socket', async () => {
  const t = setup({ owner: 42, cwd: '/tmp' });
  const o = t.agent().options;
  assert.match(o.instructions, /x_send_message/);
  assert.match(o.instructions, /a Fake bot/);
  assert.equal(o.tools.env.OTTO_BOT_SOCKET, path.join(t.dir, 'tools.sock'));
  assert.equal(o.cwd, '/tmp');
  assert.equal(o.permissionMode, 'auto');
  assert.equal(o.env.OTTO_SESSION, 'bot-test');
});

test('a turn that replies ends quietly; a message during a turn steers it', async () => {
  const t = setup({ owner: 42 });
  await t.bot.start();
  await t.msg('first');
  await t.msg('and also');
  assert.deepEqual(t.agent().writes, ['first', 'and also']);
  assert.equal(t.agent().starts, 1);
  await new Promise(r => setTimeout(r, 50));
  assert.ok(t.channel.typings >= 2, 'typing while busy');

  assert.deepEqual(await tool(t.dir, { kind: 'message', text: 'done' }), { status: 200, text: 'sent' });
  t.agent().events.session('s-1');
  assert.equal(t.file().session, 's-1');
  t.agent().events.result({ error: false, text: 'final' });
  assert.deepEqual(t.channel.texts(), ['done']);
  const typings = t.channel.typings;
  await new Promise(r => setTimeout(r, 50));
  assert.equal(t.channel.typings, typings, 'no typing once idle');
  t.bot.stop();
});

test('a turn that sends nothing is reminded once, then the user gets a notice', async () => {
  const t = setup({ owner: 42 });
  await t.bot.start();
  await t.msg('hi');
  t.agent().events.result({ error: false, text: '' });
  assert.equal(t.agent().writes.length, 2);
  assert.match(t.agent().writes[1], /From the bot, not the user/);
  t.agent().events.result({ error: false, text: 'I said it in plain text' });
  assert.deepEqual(t.channel.texts(), ["⚠️ Otto didn't reply.\nIts last words: I said it in plain text"]);
  t.bot.stop();
});

test('errors, crashes and turns the agent starts on its own', async () => {
  const t = setup({ owner: 42 });
  await t.bot.start();
  await t.msg('hi');
  t.agent().events.result({ error: true, text: 'overloaded' });
  assert.equal(t.channel.texts().at(-1), '⚠️ overloaded');

  await t.msg('again');
  t.agent().events.exit(1, 'boom');
  assert.equal(t.channel.texts().at(-1), '⚠️ Agent stopped (exit 1).\nboom');

  // Idle, then output: a turn of its own, which also has to reply.
  t.agent().events.activity();
  t.agent().events.result({ error: false, text: '' });
  assert.match(t.agent().writes.at(-1)!, /From the bot/);
  t.bot.stop();
});

test('/new, /stop and /status', async () => {
  const t = setup({ owner: 42, session: 'old', cwd: '/w' });
  await t.bot.start();
  await t.msg('/status');
  assert.equal(t.channel.texts().at(-1), 'Session: old\nFolder: /w\nNot running');
  await t.msg('/stop');
  assert.equal(t.channel.texts().at(-1), 'Nothing running.');
  await t.msg('work');
  await t.msg('/status@fake_bot');
  assert.match(t.channel.texts().at(-1)!, /Working$/);
  await t.msg('/stop');
  assert.equal(t.channel.texts().at(-1), '⏹ Stopped.');
  assert.equal(t.agent().running, false);
  assert.equal(t.file().session, 'old', '/stop keeps the conversation');
  await t.msg('/new');
  assert.equal(t.channel.texts().at(-1), '🆕 New conversation.');
  assert.equal(t.file().session, null);
  assert.equal(t.agent().options.session(), null);
  t.bot.stop();
});

test('attachments are saved and their paths handed to the agent', async () => {
  const t = setup({ owner: 42 });
  await t.bot.start();
  const att = (kind: 'photo' | 'file', name: string) => ({ kind, name, download: async (dir: string) => path.join(dir, name) });
  await t.msg('look', 42, [att('photo', 'p.jpg'), att('file', 'a.pdf')]);
  const w = t.agent().writes[0];
  assert.match(w, /^look\n\n\[The user sent a photo, saved at .*files\/p\.jpg\. Look at it with the Read tool\.\]/);
  assert.match(w, /\[The user sent a file, saved at .*files\/a\.pdf\.\]$/);
  t.bot.stop();
});

test('the socket: refused before a chat, bad requests, files; only this user can open it', async () => {
  const t = setup({ owner: 42 });
  await t.bot.start();
  assert.equal((fs.statSync(path.join(t.dir, 'tools.sock')).mode & 0o777), 0o600);
  assert.deepEqual(await tool(t.dir, { kind: 'message', text: 'x' }), { status: 400, text: 'no chat yet' });
  await t.msg('hi');
  assert.equal((await tool(t.dir, { kind: 'nope' })).status, 400);
  assert.equal((await tool(t.dir, { kind: 'file', path: '/tmp/a.png' })).status, 200);
  assert.deepEqual(t.channel.sent.at(-1), { chat: 7, file: '/tmp/a.png' });
  t.bot.stop();
});

test('the tools server speaks MCP and forwards calls to the socket', async () => {
  const t = setup({ owner: 42 });
  await t.bot.start();
  await t.msg('hi');
  const tools = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'tools.js');
  const p = spawn(process.execPath, [tools], { env: { ...process.env, OTTO_BOT_SOCKET: path.join(t.dir, 'tools.sock'), OTTO_BOT_CHANNEL: 'Fake' } });
  const replies: any[] = [];
  let buf = '';
  p.stdout.on('data', d => { buf += d; let n; while ((n = buf.indexOf('\n')) >= 0) { replies.push(JSON.parse(buf.slice(0, n))); buf = buf.slice(n + 1); } });
  const send = (m: unknown) => p.stdin.write(JSON.stringify({ jsonrpc: '2.0', ...(m as object) }) + '\n');
  send({ id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18' } });
  send({ method: 'notifications/initialized' });
  send({ id: 2, method: 'tools/list' });
  send({ id: 3, method: 'tools/call', params: { name: 'send_message', arguments: { text: 'from the tool' } } });
  send({ id: 4, method: 'tools/call', params: { name: 'send_file', arguments: { path: 'relative.txt' } } });
  send({ id: 5, method: 'nope' });
  for (let i = 0; i < 100 && replies.length < 5; i++) await new Promise(r => setTimeout(r, 20));
  p.kill();
  const by = (id: number) => replies.find(r => r.id === id);
  assert.equal(by(1).result.serverInfo.name, 'otto-bot');
  assert.deepEqual(by(2).result.tools.map((x: any) => x.name), ['send_message', 'send_file']);
  assert.match(by(2).result.tools[0].description, /on Fake/);
  assert.equal(by(3).result.content[0].text, 'sent');
  assert.ok(t.channel.texts().includes('from the tool'));
  assert.equal(by(4).result.isError, undefined, 'the fake channel takes any path; Telegram checks it');
  assert.equal(by(5).error.code, -32601);
  t.bot.stop();
});
