// The voice channel's parts that need no network: the transcript's bundles, speakable text, the config's old shape,
// and an android call end to end through the voice server.
import test from 'node:test';
import assert from 'node:assert/strict';
import { Transcript } from '../channels/voice/transcript.js';
import { speakable } from '../channels/voice/call.js';
import { normalize } from '../config.js';
import { Voice } from '../channels/voice/index.js';
import { accessTokenUsable, jwtExp } from '../channels/voice/codex-auth.js';
import { serveVoice } from '../channels/voice/server.js';
import type { Incoming } from '../channel.js';

test('transcript: each line goes once, in the order said, waiting for words still being transcribed', async () => {
  const t = new Transcript();
  t.hold('u1', 'user');
  t.add('voice', 'Hi there!');
  setTimeout(() => t.fill('u1', 'hello'), 20);
  await t.settled(1000);
  assert.deepEqual(t.take(), ['User: hello', 'Voice: Hi there!']);
  t.add('otto', 'Done.');
  t.add('user', 'thanks');
  assert.equal(t.unheard, true);
  assert.deepEqual(t.take(), ['User: thanks'], "Otto's own words aren't sent back");
  assert.deepEqual(t.take(), []);
  t.hold('u2', 'user');
  assert.equal(await Promise.race([t.settled(30).then(() => 'gave up'), new Promise(r => setTimeout(() => r('hung'), 200))]), 'gave up');
});

test('speakable text', () => {
  assert.equal(speakable('**Done.** See [the report](https://x.y/z) and https://a.b\n- one\n- two'), 'Done. See the report and the link\none\ntwo');
  assert.equal(speakable('my_file and `code`'), 'my_file and code');
});

test('openai voice: an API key, or the Codex sign-in', () => {
  const deps = { notify: async () => {}, log: () => {} };
  assert.equal(new Voice({ provider: 'openai-realtime', auth: 'codex' }, deps).provider, 'openai-realtime');
  assert.throws(() => new Voice({ provider: 'openai-realtime' }, deps), /apiKey/);
  assert.throws(() => new Voice({ provider: 'openai-realtime', auth: 'nope' }, deps), /unknown voice auth/);
});

test('a Codex access token is used until shortly before it expires', () => {
  const jwt = (exp: number) => `${Buffer.from('{"alg":"none"}').toString('base64url')}.${Buffer.from(JSON.stringify({ exp })).toString('base64url')}.x`;
  const now = 1_000_000;
  assert.equal(jwtExp(jwt(now + 600)), now + 600);
  assert.equal(accessTokenUsable(jwt(now + 600), now), true);
  assert.equal(accessTokenUsable(jwt(now + 30), now), false);
  assert.equal(accessTokenUsable('not-a-jwt', now), true);
});

test('config: the first shape reads as the new one', () => {
  assert.deepEqual(normalize({ channel: 'telegram', token: 't', owner: 5, session: 's' }), { session: 's', channels: { telegram: { token: 't', owner: 5 } } });
  assert.deepEqual(normalize({ channels: {} }), { channels: {} }, 'a bot with no channel left is kept');
  assert.throws(() => normalize({ session: 's' }), /needs a channels object/);
});

test('an android call through the voice server: heard, spoken, ended; a reply with no call is a notification', async () => {
  const notes: string[] = [];
  const got: Incoming[] = [];
  const voice = new Voice({ provider: 'android' }, { notify: async t => { notes.push(t); }, log: () => {} });
  await voice.start(m => got.push(m));
  const server = serveVoice(0, { assistant: () => ({ bot: 'otto', agent: 'Claude Code', voice }), bots: () => ['otto'], assign: async () => {}, log: () => {} });
  await new Promise(r => server.once('listening', r));
  const port = (server.address() as any).port;
  const call = (p: string, body?: unknown) => fetch(`http://127.0.0.1:${port}${p}`, body === undefined ? {} : { method: 'POST', body: JSON.stringify(body) }).then(r => r.json().catch(() => null));

  assert.deepEqual(await call('/voice/assistant'), { bot: 'otto', agent: 'Claude Code', provider: 'android', mode: 'android', bots: ['otto'] });
  const s = await call('/voice/sessions', {});
  assert.equal(s.mode, 'android');
  await call(`/voice/sessions/${s.id}/heard`, { text: 'what time is it' });
  assert.equal(got[0].text, 'what time is it');
  await voice.typing();
  await voice.send('user', 'It is **noon**.');
  const ev = await call(`/voice/sessions/${s.id}/events?after=0`);
  assert.deepEqual(ev.events.map((e: any) => e.type === 'state' ? e.state : `${e.type}:${e.text}`), ['listening', 'thinking', 'speak:It is noon.']);
  await call(`/voice/sessions/${s.id}/stop`, {});
  assert.equal(voice.call, null);
  await voice.send('user', 'later');
  assert.deepEqual(notes, ['later']);
  server.close();
});

test('fillers wait for the next real sentence', async () => {
  const { filler } = await import('../channels/voice/android.js');
  for (const t of ['so', 'yeah so', 'Um...', 'and then', 'oh well', '']) assert.equal(filler(t), true, t);
  for (const t of ['yes', 'no', 'okay', 'stop', 'so what time is it', 'of the open work on Iraq']) assert.equal(filler(t), false, t);
});

test("realtime: Otto's message refused while the voice starts an answer is said after that answer", async () => {
  const { OpenAICall } = await import('../channels/voice/openai.js');
  const call: any = new OpenAICall({ toAgent: () => {}, log: () => {} }, { apiKey: 'k' });
  const sent: any[] = [];
  call.ws = { readyState: 1, send: (s: string) => sent.push(JSON.parse(s)) };
  call.up = true;
  const wait = (ms: number) => new Promise(r => setTimeout(r, ms));
  const creates = () => sent.filter(e => e.type === 'response.create' && e.response?.tool_choice === 'none').length;
  call.say('The build passed.');
  await wait(20);
  assert.equal(creates(), 1);
  call.event({ type: 'error', error: { code: 'conversation_already_has_active_response', message: 'busy' } });
  await wait(400);
  assert.equal(creates(), 1, 'waits for the answer under way');
  call.event({ type: 'response.done', response: { output: [] } });
  await wait(400);
  assert.equal(creates(), 2, 'then says it');
  assert.match(sent.filter(e => e.type === 'conversation.item.create').at(-1).item.content[0].text, /The build passed/);
  call.event({ type: 'response.created' });
  call.event({ type: 'response.done', response: { output: [] } });
  clearTimeout(call.gapTimer);
});
