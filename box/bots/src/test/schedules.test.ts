// Schedules: their timing in the user's time zone, the scheduler against a fake clock and a fake agent (due runs,
// overlap, missed times, three failures), the local API that otto schedule and the app use, and channels' logs.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'otto-schedules-test-'));
process.env.OTTO_BOTS_CONFIG = path.join(root, 'config/bots');
process.env.OTTO_BOTS_STATE = path.join(root, 'state/bots');

const { next, check, describe, parseOnce } = await import('../schedules/when.js');
const store = await import('../schedules/store.js');
const { Scheduler } = await import('../schedules/runner.js');
const { local } = await import('../local.js');
const { logChannel, readChannel } = await import('../channel-log.js');
import type { Launcher, Outcome } from '../schedules/agents.js';

const IST = 'Asia/Kolkata';
const iso = (t: number | null) => (t === null ? null : new Date(t).toISOString());

test('timing: times of day in the time zone, days, cron, once', () => {
  const mon9utc = Date.parse('2026-11-09T09:00:00Z'); // Monday 14:30 in India
  assert.equal(iso(next({ at: '08:00' }, mon9utc, IST)), '2026-11-10T02:30:00.000Z'); // Tuesday 08:00 IST
  assert.equal(iso(next({ at: '20:15' }, mon9utc, IST)), '2026-11-09T14:45:00.000Z'); // tonight
  assert.equal(iso(next({ at: '08:00', every: 'weekend' }, mon9utc, IST)), '2026-11-14T02:30:00.000Z'); // Saturday
  assert.equal(iso(next({ at: '08:00', every: 'fri' }, mon9utc, IST)), '2026-11-13T02:30:00.000Z');
  assert.equal(iso(next({ at: '07:00', every: 'weekday' }, Date.parse('2026-11-13T05:00:00Z'), IST)), '2026-11-16T01:30:00.000Z'); // Fri → Mon
  assert.equal(iso(next({ cron: '30 9 1 * *' }, mon9utc, IST)), '2026-12-01T04:00:00.000Z');
  assert.equal(iso(next({ cron: '*/20 * * * *' }, mon9utc, IST)), '2026-11-09T09:10:00.000Z'); // 14:40 IST
  assert.equal(iso(next({ once: '2026-11-10 18:00' }, mon9utc, IST)), '2026-11-10T12:30:00.000Z');
  assert.equal(next({ once: '2026-11-08 18:00' }, mon9utc, IST), null);
  assert.equal(iso(next({ at: '08:00' }, mon9utc, 'Europe/London')), '2026-11-10T08:00:00.000Z');
  assert.equal(parseOnce('2026-07-01 08:00', 'Europe/London'), Date.parse('2026-07-01T07:00:00Z')); // summer time
  assert.equal(describe({ at: '08:00', every: 'mon,thu' }), 'Mon, Thu at 08:00');
  assert.equal(describe({ at: '08:00', every: 'weekday' }), 'weekdays at 08:00');
  assert.throws(() => check({ at: '25:00' }, IST), /time of day/);
  assert.throws(() => check({ at: '08:00', cron: '* * * * *' }, IST), /exactly one/);
  assert.throws(() => check({ at: '08:00', every: 'someday' }, IST), /not a day/);
  assert.throws(() => check({ cron: '* * *' }, IST), /5 fields/);
});

/** A fake agent: each run waits until finished from the test. */
function fakeAgent() {
  const runs: { prompt: string; env: Record<string, string>; finish: (o: Outcome) => void; stopped: boolean }[] = [];
  const launch: Launcher = l => {
    let finish!: (o: Outcome) => void;
    const done = new Promise<Outcome>(r => { finish = r; });
    const r = { prompt: l.prompt, env: l.env, finish, stopped: false };
    runs.push(r);
    return { done, stop: () => { r.stopped = true; finish({ status: 'timeout' }); } };
  };
  return { runs, launch };
}

const settle = () => new Promise(r => setTimeout(r, 10));

test('the scheduler: a run when due, with a preamble; overlap skipped; missed times recorded; three failures notify once', async () => {
  let now = Date.parse('2026-11-09T02:29:00Z'); // 07:59 IST
  const agent = fakeAgent();
  const notes: string[] = [];
  const s = new Scheduler({ timezone: IST, log: () => {}, notify: async t => { notes.push(t); }, agents: { claude: agent.launch }, now: () => now });
  store.save('morning', { prompt: 'Check the weather.', at: '08:00', since: now - 60_000 });
  s.tick();
  assert.equal(agent.runs.length, 0, 'not yet');
  now += 60_000;
  s.tick();
  assert.equal(agent.runs.length, 1);
  assert.match(agent.runs[0].prompt, /Scheduled run of "morning".*Nobody is watching[\s\S]*Check the weather\.$/);
  assert.equal(agent.runs[0].env.OTTO_SCHEDULE, 'morning');
  assert.match(agent.runs[0].env.OTTO_SENDER, /^schedule morning \(run r\w+\)$/);
  s.tick();
  assert.equal(agent.runs.length, 1, 'once per time');
  assert.equal(s.view('morning')!.running, true);
  assert.equal(s.runNow('morning').status, 'skipped', 'the last run is still going');
  agent.runs[0].finish({ status: 'ok', session: 'abc', result: 'Sunny.' });
  await settle();
  let runs = store.runs('morning');
  assert.deepEqual(runs.map(r => r.status).sort(), ['ok', 'skipped']);
  assert.equal(runs.find(r => r.status === 'ok')!.result, 'Sunny.');
  assert.equal(s.view('morning')!.next, '2026-11-10T02:30:00.000Z');

  // Off for two days: the time that was missed is recorded, not run late.
  now = Date.parse('2026-11-11T09:00:00Z');
  s.tick();
  assert.equal(agent.runs.length, 1);
  assert.equal(store.runs('morning').at(-1)!.status, 'missed');

  // Three failures in a row: one notification.
  for (let i = 0; i < 4; i++) {
    s.runNow('morning');
    agent.runs.at(-1)!.finish({ status: 'failed', result: `boom ${i}` });
    await settle();
  }
  assert.equal(notes.length, 1);
  assert.match(notes[0], /"morning" failed 3 times in a row: boom 2/);

  // Paused: nothing runs, and there's no next time.
  store.save('morning', { ...store.load('morning')!, paused: true });
  now = Date.parse('2026-11-12T02:30:30Z');
  const before = agent.runs.length;
  s.tick();
  assert.equal(agent.runs.length, before);
  assert.equal(s.view('morning')!.next, null);
  runs = store.runs('morning');
  assert.ok(runs.length >= 6);
});

/** A request to a local API server. */
function call(port: number, method: string, p: string, body?: unknown): Promise<{ status: number; json: any; text: string }> {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, method, path: p }, res => {
      let text = '';
      res.on('data', d => { text += d; });
      res.on('end', () => { let json; try { json = JSON.parse(text); } catch {} resolve({ status: res.statusCode!, json, text }); });
    });
    req.on('error', reject);
    req.end(body === undefined ? undefined : JSON.stringify(body));
  });
}

test('the local API: otto send, channel logs, and schedules made, changed, run and read', async t => {
  const agent = fakeAgent();
  const scheduler = new Scheduler({ timezone: IST, log: () => {}, notify: async () => {}, agents: { claude: agent.launch } });
  const sends: any[] = [];
  const server = http.createServer(async (req, res) => {
    const u = new URL(req.url!, 'http://x');
    try {
      if (!await local(req, res, u, { scheduler, send: async m => { sends.push(m); return `${m.channel} (otto)`; } })) res.writeHead(404).end();
    } catch (e: any) { res.writeHead(400).end(e.message); }
  });
  await new Promise<void>(r => server.listen(0, '127.0.0.1', r));
  t.after(() => { server.close(); server.closeAllConnections(); });
  const port = (server.address() as any).port;

  let r = await call(port, 'POST', '/send', { channel: 'telegram', text: 'Hi', by: 'schedule x (run r1)' });
  assert.equal(r.text.trim(), 'sent on telegram (otto)');
  assert.equal(sends[0].by, 'schedule x (run r1)');
  assert.equal((await call(port, 'POST', '/send', { channel: 'telegram' })).status, 400);

  r = await call(port, 'POST', '/schedules', { name: 'gym', prompt: 'Remind the user about the gym.', at: '18:00', every: 'mon,wed' });
  assert.equal(r.status, 200, r.text);
  assert.equal(r.json.description, 'Mon, Wed at 18:00');
  assert.equal((await call(port, 'POST', '/schedules', { name: 'gym', prompt: 'x', at: '18:00' })).status, 409);
  assert.equal((await call(port, 'POST', '/schedules', { name: 'bad', prompt: 'x', at: '18:00', cron: '* * * * *' })).status, 400);
  r = await call(port, 'PATCH', '/schedules/gym', { cron: '0 7 * * *' });
  assert.equal(r.json.description, 'cron 0 7 * * *');
  assert.equal(r.json.at, undefined, 'a new timing replaces the old');
  r = await call(port, 'PATCH', '/schedules/gym', { paused: true });
  assert.equal(r.json.paused, true);
  assert.equal(r.json.next, null);
  r = await call(port, 'GET', '/schedules');
  assert.equal(r.json.timezone, IST);
  assert.deepEqual(r.json.schedules.map((s: any) => s.name).filter((n: string) => n === 'gym'), ['gym']);

  r = await call(port, 'POST', '/schedules/gym/run');
  const id = r.json.run.id;
  fs.writeFileSync(store.logOf('gym', id), [
    { type: 'system', session_id: 's1' },
    { type: 'assistant', message: { content: [{ type: 'text', text: 'Sending the reminder.' }, { type: 'tool_use', name: 'Bash', input: { command: 'otto send telegram "Gym at 6"' } }] } },
    { type: 'result', result: 'Done.' },
  ].map(e => JSON.stringify(e)).join('\n') + '\n');
  agent.runs.at(-1)!.finish({ status: 'ok', session: 's1', result: 'Done.' });
  await settle();
  r = await call(port, 'GET', `/schedules/gym/runs/${id}`);
  assert.deepEqual(r.json.steps, [
    { kind: 'text', text: 'Sending the reminder.' }, { kind: 'tool', text: '$ otto send telegram "Gym at 6"' }, { kind: 'result', text: 'Done.' },
  ]);
  r = await call(port, 'GET', '/schedules/-/runs?limit=50');
  assert.ok(r.json.runs.some((x: any) => x.id === id && x.status === 'ok'));
  assert.equal((await call(port, 'DELETE', '/schedules/gym')).status, 200);
  assert.equal((await call(port, 'GET', '/schedules/gym')).status, 404);
  assert.equal((await call(port, 'GET', '/schedules/gym/runs')).json.runs.length, 1, 'its runs stay');

  logChannel('telegram', { dir: 'out', by: 'schedule gym (run r1)', text: 'Gym at 6' });
  logChannel('telegram', { dir: 'in', by: 'user', text: 'why?' });
  assert.deepEqual(readChannel('telegram').map(e => e.text), ['Gym at 6', 'why?']);
  r = await call(port, 'GET', '/channels/telegram/log?limit=1');
  assert.equal(r.json.entries[0].text, 'why?');
  assert.equal(fs.statSync(path.join(root, 'state/channels/telegram.jsonl')).mode & 0o777, 0o600);
});
