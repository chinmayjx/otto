// The Claude provider against a fake `claude` (CLAUDE_BIN): its arguments, stream-json in and out, and stopping.
// Also Telegram's Markdown to HTML and message splitting, which need no network.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { claude } from '../providers/claude.js';
import { html } from '../channels/telegram-html.js';
import { parts } from '../channels/telegram.js';

// Starts with a system event, then for each user message an assistant event and a result echoing the text.
const FAKE = `#!/usr/bin/env node
console.log(JSON.stringify({ type: 'system', session_id: 'sess-1' }));
require('node:readline').createInterface({ input: process.stdin }).on('line', l => {
  const m = JSON.parse(l);
  if (m.message.content === 'die') process.exit(3);
  console.log(JSON.stringify({ type: 'assistant', session_id: 'sess-1' }));
  console.log(JSON.stringify({ type: 'result', session_id: 'sess-1', result: 'echo ' + m.message.content }));
});
`;

test('claude: arguments, events, resume and stop', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'otto-claude-test-'));
  const bin = path.join(dir, 'claude.cjs');
  fs.writeFileSync(bin, FAKE, { mode: 0o755 });
  process.env.CLAUDE_BIN = bin;
  process.env.CLAUDECODE = '1';
  let session: string | null = 'old';
  const seen: string[] = [];
  const exits: (number | null)[] = [];
  const results: string[] = [];
  const agent = claude.create({
    cwd: dir, session: () => session, permissionMode: 'auto', model: 'opus', instructions: 'BE BRIEF',
    tools: { command: 'node', args: ['tools.js'], env: { OTTO_BOT_SOCKET: '/s' } }, env: { OTTO_SESSION: 'bot-x' },
  }, {
    session: id => { session = id; seen.push(`session ${id}`); },
    activity: () => seen.push('activity'),
    result: r => results.push(r.text),
    exit: code => exits.push(code),
  });
  assert.equal(agent.running, false);
  agent.write('hello');
  assert.equal(agent.running, true);
  for (let i = 0; i < 100 && results.length < 1; i++) await new Promise(r => setTimeout(r, 20));
  assert.deepEqual(results, ['echo hello']);
  assert.deepEqual(seen, ['session sess-1', 'activity']);

  agent.write('die');
  for (let i = 0; i < 100 && !exits.length; i++) await new Promise(r => setTimeout(r, 20));
  assert.deepEqual(exits, [3]);
  assert.equal(agent.running, false);

  // A new process resumes the session it was given; stopping it reports no exit.
  agent.write('again');
  for (let i = 0; i < 100 && results.length < 2; i++) await new Promise(r => setTimeout(r, 20));
  agent.stop();
  await new Promise(r => setTimeout(r, 100));
  assert.deepEqual(exits, [3]);
});

test('claude: the command line', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'otto-claude-test-'));
  const bin = path.join(dir, 'claude.sh');
  const out = path.join(dir, 'args.json');
  fs.writeFileSync(bin, `#!/bin/sh\nnode -e 'require("fs").writeFileSync(process.argv[1], JSON.stringify({args: process.argv.slice(2), cwd: process.cwd(), s: process.env.OTTO_SESSION, nested: process.env.CLAUDECODE || null}))' ${out} "$@"\ncat >/dev/null\n`, { mode: 0o755 });
  process.env.CLAUDE_BIN = bin;
  process.env.CLAUDECODE = '1';
  const agent = claude.create({
    cwd: dir, session: () => 'sess-9', permissionMode: 'acceptEdits', model: 'opus', instructions: 'BE BRIEF',
    tools: { command: 'node', args: ['tools.js'], env: { OTTO_BOT_SOCKET: '/s' } }, env: { OTTO_SESSION: 'bot-x' },
  }, { session() {}, activity() {}, result() {}, exit() {} });
  agent.write('x');
  for (let i = 0; i < 100 && !fs.existsSync(out); i++) await new Promise(r => setTimeout(r, 20));
  agent.stop();
  const r = JSON.parse(fs.readFileSync(out, 'utf8'));
  const a: string[] = r.args;
  const after = (flag: string) => a[a.indexOf(flag) + 1];
  assert.equal(a[0], '-p');
  assert.equal(after('--input-format'), 'stream-json');
  assert.equal(after('--output-format'), 'stream-json');
  assert.ok(a.includes('--verbose'));
  assert.equal(after('--permission-mode'), 'acceptEdits');
  assert.equal(after('--append-system-prompt'), 'BE BRIEF');
  assert.equal(after('--model'), 'opus');
  assert.equal(after('--resume'), 'sess-9');
  assert.deepEqual(a.slice(a.indexOf('--allowedTools') + 1, a.indexOf('--allowedTools') + 3), ['mcp__bot__send_message', 'mcp__bot__send_file']);
  assert.deepEqual(JSON.parse(after('--mcp-config')), { mcpServers: { bot: { command: 'node', args: ['tools.js'], env: { OTTO_BOT_SOCKET: '/s' } } } });
  assert.equal(fs.realpathSync(r.cwd), fs.realpathSync(dir));
  assert.equal(r.s, 'bot-x');
  assert.equal(r.nested, null, 'not a child session of the Claude that started the service');
});

test('telegram: Markdown to HTML', () => {
  assert.equal(html('**b** *i* `a<b` ~~s~~'), '<b>b</b> <i>i</i> <code>a&lt;b</code> <s>s</s>');
  assert.equal(html('# Title\n- one\n- [x] done'), '<b>Title</b>\n• one\n✅ done');
  assert.equal(html('see [docs](https://x.y/z)'), 'see docs\nhttps://x.y/z');
  assert.equal(html('> [!expand]\n> hidden'), '<blockquote expandable>hidden</blockquote>');
  assert.equal(html('```js\nif (a < b) {}\n```'), '<pre><code class="language-js">if (a &lt; b) {}</code></pre>');
  assert.equal(html('| a | bb |\n|---|---|\n| ccc | d |'), '<pre>a    bb\n───  ──\nccc  d</pre>');
});

test('telegram: long text in parts at paragraph breaks', () => {
  const para = 'x'.repeat(3000);
  assert.deepEqual(parts(`${para}\n\n${para}`), [para, para]);
  assert.equal(parts('y'.repeat(9000)).length, 3);
  assert.deepEqual(parts('short'), ['short']);
});
