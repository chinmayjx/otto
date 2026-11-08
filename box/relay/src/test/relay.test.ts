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
import { FakePhone } from './fake-phone.js';

const base = 19300 + Math.floor(Math.random() * 600);
const ports = { tunnel: base, pipe: base + 1, local: base + 3 };
const api = `http://127.0.0.1:${ports.local}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'otto-relay-test-'));
let relay: ChildProcess;
const app = new FakePhone();

before(async () => {
  fs.writeFileSync(path.join(dir, 'token'), 'phone-secret\n');
  fs.writeFileSync(path.join(dir, 'config.toml'), `
[relay]
tailnet_host = "127.0.0.1"
tunnel_port = ${ports.tunnel}
pipe_port = ${ports.pipe}
local_port = ${ports.local}
state_file = "${dir}/state.json"
[phone]
token_file = "${dir}/token"
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
  const bad = new FakePhone();
  await bad.start('127.0.0.1', ports.tunnel, ports.pipe, 'nope').catch(() => {});
  await new Promise(r => setTimeout(r, 200));
  const s = await (await fetch(api + '/status')).json();
  assert.equal(s.browser.phones.length, 0);
  assert.equal(s.device.connected, false);
  bad.stop();
});

test('each session sees only its own tabs', async () => {
  await app.start('127.0.0.1', ports.tunnel, ports.pipe, 'phone-secret');
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

test('device commands over HTTP pipes: JSON, text and bytes', async () => {
  const s = await (await fetch(api + '/status')).json();
  assert.deepEqual([s.device.connected, s.device.device, s.device.android], [true, 'Test Phone', '16']);
  const cur = await post('/phone/current', {});
  assert.deepEqual((await cur.json()).result, { package: 'me.test.app', activity: '.Main' });
  const shot = await post('/phone/screenshot', { scale: 0.5 });
  assert.equal(Buffer.from(await shot.arrayBuffer()).toString(), 'PNGBYTES');
  assert.deepEqual(JSON.parse(shot.headers.get('x-otto-result')!), { width: 2, height: 1, format: 'png', scale: '0.5' });
  assert.equal((await (await post('/phone/ui', { all: true })).json()).result, 'Button "OK" all=true');
  const log = await post('/phone/logcat', { tags: ['A', 'B'], lines: 5 });
  assert.equal(Buffer.from(await log.arrayBuffer()).toString(), 'log lines tags=A,B\n');
  assert.deepEqual(JSON.parse(log.headers.get('x-otto-result')!), { fullAccess: true });
  assert.equal((await (await post('/phone/tap', { x: 1, y: 2 })).json()).result, 'tapped 1,2');
  const bad = await post('/phone/nonsense', {});
  assert.equal(bad.status, 502);
  assert.match(await bad.text(), /unknown command nonsense/);
  const tap = await post('/phone/tap', {});
  assert.equal(tap.status, 502);
  assert.match(await tap.text(), /give x and y/);
  const apk = await fetch(api + '/phone/install?name=t.apk', { method: 'POST', body: Buffer.alloc(1000, 1) });
  assert.equal((await apk.json()).result, 'Success: t.apk (1000 bytes)');
  assert.ok(app.deviceRequests.includes('GET /screenshot?scale=0.5'), app.deviceRequests.join(', '));
});

test('device pipes are kept alive: ten commands, a few pipes', async () => {
  const before = app.devicePipes;
  const t0 = Date.now();
  for (let i = 0; i < 10; i++) assert.equal((await post('/phone/current', {})).status, 200);
  const ms = Date.now() - t0;
  assert.ok(app.devicePipes - before <= 2, `${app.devicePipes - before} new pipes`);
  assert.ok(ms < 1000, `${ms} ms`);
  // Several at once (reading commands don't queue) open more pipes, kept for later.
  const all = await Promise.all(Array.from({ length: 4 }, () => post('/phone/info', {})));
  assert.deepEqual(all.map(r => r.status), [200, 200, 200, 200]);
});

test('the browser going down leaves the device up; it comes back with its tabs', async () => {
  const tabs = app.tabs.size;
  app.browserDown();
  await new Promise(r => setTimeout(r, 100));
  let s = await (await fetch(api + '/status')).json();
  assert.equal(s.browser.phones.length, 0);
  assert.equal(s.device.connected, true);
  assert.equal((await post('/show?url=https://example.com/down')).status, 503);
  assert.equal((await post('/phone/current', {})).status, 200);
  await app.browserUp();
  await new Promise(r => setTimeout(r, 100));
  s = await (await fetch(api + '/status')).json();
  assert.equal(Object.keys(s.browser.phones[0].tabs).length, tabs);
});

test('browser commands: open, snapshot refs, click, type, tabs, screenshot', async () => {
  const run = async (cmd: string, args: any = {}) => {
    const r = await post(`/browser/gamma/${cmd}?title=Gamma`, args);
    assert.equal(r.status, 200, `${cmd}: ${r.status} ${r.status !== 200 ? await r.clone().text() : ''}`);
    return r;
  };
  const html = '<title>Form</title><input aria-label="Name"><button onclick="document.title=document.querySelector(\'input\').value">Go</button>';
  assert.match(await (await run('open', { url: 'data:text/html,' + encodeURIComponent(html) })).text(), /"Form"/);
  const snap = await (await run('snapshot')).text();
  const name = snap.match(/textbox "Name" \[ref=(e\d+)\]/)?.[1];
  const go = snap.match(/button "Go" \[ref=(e\d+)\]/)?.[1];
  assert.ok(name && go, snap);
  await run('type', { ref: name, text: 'Otto' });
  assert.match(await (await run('click', { ref: go })).text(), /"Otto"/);
  assert.match(await (await run('eval', { js: 'document.title' })).text(), /^Otto/);
  await run('open', { url: 'data:text/html,<title>Second</title>', new: true });
  const tabs = await (await run('tabs')).text();
  assert.match(tabs, /\* 1 {2}Second/);
  assert.match(tabs, /0 {2}Otto/);
  const png = Buffer.from(await (await run('screenshot')).arrayBuffer());
  assert.equal(png.subarray(1, 4).toString(), 'PNG');
  const s = await (await fetch(api + '/status')).json();
  assert.equal(s.sessions.gamma.title, 'Gamma');
});

test('the otto CLI, against this relay', async () => {
  const cli = path.join(path.dirname(fileURLToPath(import.meta.url)), '../../../../cli/dist/main.js');
  const shots = fs.mkdtempSync(path.join(os.tmpdir(), 'otto-shots-'));
  const otto = (...args: string[]) => new Promise<{ code: number; out: string; err: string }>(resolve => {
    const p = spawn(process.execPath, [cli, ...args], { env: { ...process.env, OTTO_CONFIG: path.join(dir, 'config.toml'), OTTO_SESSION: 'delta', OTTO_SESSION_TITLE: 'Delta task', OTTO_SHOTS: shots } });
    let out = '', err = '';
    p.stdout.on('data', d => out += d);
    p.stderr.on('data', d => err += d);
    p.on('close', code => resolve({ code: code ?? 1, out, err }));
  });
  let r = await otto('browser', 'open', 'data:text/html,<title>CLI</title><a href="/x">Link here</a>');
  assert.equal(r.code, 0, r.err);
  assert.match(r.out, /"CLI"/);
  r = await otto('browser', 'snapshot');
  assert.match(r.out, /link "Link here" \[ref=e\d+\]/);
  r = await otto('browser', 'screenshot');
  assert.ok(fs.existsSync(r.out.trim()), r.out + r.err);
  r = await otto('status');
  assert.match(r.out, /session: delta \(group tg\d+, "Delta task"\)/);
  r = await otto('phone', 'current');
  assert.match(r.out, /me\.test\.app/);
  r = await otto('phone', 'screenshot');
  const [file, size] = r.out.trim().split('\n');
  assert.equal(fs.readFileSync(file).toString(), 'PNGBYTES');
  assert.equal(size, '2x1');
  r = await otto('show', 'https://example.com/cli');
  assert.equal(r.code, 0, r.err);
  r = await otto('phone', 'tap');
  assert.notEqual(r.code, 0);
  r = await otto('frobnicate');
  assert.match(r.err, /unknown command/);
});
