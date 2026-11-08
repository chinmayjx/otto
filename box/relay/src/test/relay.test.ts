// The relay end to end: a real relay process, a fake phone (headless Chromium as its browser), and Playwright
// connecting as two agent sessions.
import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, type ChildProcess } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';
import { FakeBrowserApp, fakeDevice } from './fake-phone.js';

const base = 19300 + Math.floor(Math.random() * 600);
const ports = { browser: base, pipe: base + 1, device: base + 2, local: base + 3 };
const api = `http://127.0.0.1:${ports.local}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'otto-relay-test-'));
let relay: ChildProcess;
const app = new FakeBrowserApp();

before(async () => {
  fs.writeFileSync(path.join(dir, 'browser-token'), 'browser-secret\n');
  fs.writeFileSync(path.join(dir, 'device-token'), 'device-secret\n');
  fs.writeFileSync(path.join(dir, 'config.toml'), `
[relay]
tailnet_host = "127.0.0.1"
browser_port = ${ports.browser}
pipe_port = ${ports.pipe}
device_port = ${ports.device}
local_port = ${ports.local}
state_file = "${dir}/state.json"
[phone]
browser_token_file = "${dir}/browser-token"
device_token_file = "${dir}/device-token"
`);
  const main = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'main.js');
  relay = spawn(process.execPath, [main], { env: { ...process.env, OTTO_CONFIG: path.join(dir, 'config.toml') }, stdio: ['ignore', 'pipe', 'inherit'] });
  await new Promise<void>(resolve => relay.stdout!.on('data', d => { if (String(d).includes('local API')) resolve(); }));
});

after(() => {
  app.stop();
  relay?.kill();
});

const post = (p: string, body?: any) => fetch(api + p, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });

test('without a phone, the browser and the device say so', async () => {
  const r = await post('/show?url=https://example.com');
  assert.equal(r.status, 503);
  assert.match(await r.text(), /not connected/);
  assert.equal((await post('/phone/current', {})).status, 503);
});

test('a wrong token is refused', async () => {
  const bad = new FakeBrowserApp();
  await bad.start('127.0.0.1', ports.browser, ports.pipe, 'nope').catch(() => {});
  await new Promise(r => setTimeout(r, 200));
  const s = await (await fetch(api + '/status')).json();
  assert.equal(s.browser.phones.length, 0);
  bad.stop();
});

test('each session sees only its own tabs', async () => {
  await app.start('127.0.0.1', ports.browser, ports.pipe, 'browser-secret');
  await post('/sessions/alpha?title=' + encodeURIComponent('Alpha work'));

  const a = await chromium.connectOverCDP(`${api}/s/alpha`);
  const b = await chromium.connectOverCDP(`${api}/s/beta`);
  const pa = a.contexts()[0].pages(), pb = b.contexts()[0].pages();
  assert.equal(pa.length, 1, 'alpha has its first tab');
  assert.equal(pb.length, 1, 'beta has its first tab');
  await pa[0].goto('data:text/html,<title>A</title>');
  await pb[0].goto('data:text/html,<title>B</title>');
  assert.equal(await pa[0].title(), 'A');
  assert.equal(await pb[0].title(), 'B');

  // A second tab for alpha lands in alpha's group, and beta doesn't see it.
  const pa2 = await a.contexts()[0].newPage();
  await pa2.goto('data:text/html,<title>A2</title>');
  assert.equal(a.contexts()[0].pages().length, 2);
  assert.equal(b.contexts()[0].pages().length, 1);

  const s = await (await fetch(api + '/status')).json();
  assert.equal(s.sessions.alpha.title, 'Alpha work');
  assert.notEqual(s.sessions.alpha.group, s.sessions.beta.group);
  const groups = [...app.tabs.values()].map(t => t.group);
  assert.equal(groups.filter(g => g === s.sessions.alpha.group).length, 2);

  await a.close();
  await b.close();
});

test('a session comes back to its group', async () => {
  const before = (await (await fetch(api + '/status')).json()).sessions.alpha.group;
  const a = await chromium.connectOverCDP(`${api}/s/alpha`);
  assert.equal(a.contexts()[0].pages().length, 2, 'its two tabs, woken');
  assert.equal((await (await fetch(api + '/status')).json()).sessions.alpha.group, before);
  await a.close();
});

test('show and close reach the phone', async () => {
  assert.equal((await post('/show?url=https://example.com/x')).status, 200);
  const group = (await (await fetch(api + '/status')).json()).sessions.beta.group;
  assert.match(await (await post('/close?session=beta')).text(), new RegExp(group));
  await new Promise(r => setTimeout(r, 100));
  assert.deepEqual(app.shown, ['https://example.com/x']);
  assert.deepEqual(app.closedGroups, [group]);
  assert.equal((await (await fetch(api + '/status')).json()).sessions.beta, undefined);
});

test('device commands: JSON results and bytes', async () => {
  const d = await fakeDevice('127.0.0.1', ports.device, 'device-secret');
  await new Promise(r => setTimeout(r, 100));
  const cur = await post('/phone/current', {});
  assert.deepEqual((await cur.json()).result, { package: 'me.test.app', activity: '.Main' });
  const shot = await post('/phone/screenshot', { scale: 1 });
  assert.equal(Buffer.from(await shot.arrayBuffer()).toString(), 'PNGBYTES');
  assert.deepEqual(JSON.parse(shot.headers.get('x-otto-result')!), { width: 2, height: 1, format: 'png' });
  const bad = await post('/phone/nonsense', {});
  assert.equal(bad.status, 502);
  assert.match(await bad.text(), /unknown command/);
  d.destroy();
});
