// The voice channel's parts that need no network: the transcript's bundles, speakable text, the config's old shape,
// and an android call end to end through the voice server.
import test from 'node:test';
import assert from 'node:assert/strict';
import { Transcript, weight, worth } from '../channels/voice/transcript.js';
import { Stale } from '../channel.js';
import { speakable } from '../channels/voice/call.js';
import { normalize } from '../config.js';
import { Voice } from '../channels/voice/index.js';
import { accessTokenUsable, jwtExp } from '../channels/voice/codex-auth.js';
import { serveVoice } from '../channels/voice/server.js';
import type { Incoming } from '../channel.js';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { History } from '../channels/voice/history.js';

test('transcript: each line goes once, in the order said, waiting for words still being transcribed', async () => {
  const t = new Transcript();
  t.hold('u1', 'user');
  t.add('voice', 'Hi there!');
  setTimeout(() => t.fill('u1', 'hello'), 20);
  await t.settled(1000);
  assert.deepEqual(t.take().lines, ['User: hello', 'Voice: Hi there!']);
  t.add('otto', 'Done.');
  t.add('user', 'thanks');
  assert.equal(t.unheard, true);
  assert.deepEqual(t.take().lines, ['User: thanks'], "Otto's own words aren't sent back");
  assert.deepEqual(t.take().lines, []);
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

test('history: lines on disk, marked once delivered, the last 50 kept, what never reached the agent left over', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'otto-voice-'));
  const h = History.in(dir);
  const t = new Transcript(h);
  t.add('user', 'remind me to call mum');
  t.add('voice', "I'll pass that on.");
  const b = t.take();
  t.hold('u2', 'user');
  t.fill('u2', 'and buy milk');
  t.add('otto', 'Done.');
  assert.equal((fs.statSync(h.file).mode & 0o777).toString(8), '600');
  b.delivered();
  // Read again, as a restarted service would: only what the agent never got is left over.
  const again = new (History as any)(h.file) as History;
  assert.deepEqual(again.pending().lines, ['User: and buy milk']);
  assert.deepEqual(again.all.map(e => e.delivered), [true, true, false, true]);

  for (let i = 0; i < 60; i++) h.add('voice', `line ${i}`, true);
  assert.equal(h.all.length, 51, 'the last 50, and the line still waiting');
  assert.equal(h.all[0].text, 'and buy milk');

  // The voice's own words alone aren't worth a record: they're settled, not sent.
  const h2 = History.in(fs.mkdtempSync(path.join(os.tmpdir(), 'otto-voice-')));
  h2.add('voice', 'Hello!', false);
  assert.deepEqual(h2.pending().lines, []);
  assert.equal(h2.all[0].delivered, true);
});

test('a call that ends leaves its unheard lines as a record, delivered when the agent gets them', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'otto-voice-'));
  const h = History.in(dir);
  const got: Incoming[] = [];
  const voice = new Voice({ provider: 'android' }, { notify: async () => {}, log: () => {}, history: h });
  await voice.start(m => got.push(m));
  await voice.open({});
  (voice.call as any).heard('what is on my calendar');
  got[0].delivered?.();
  (voice.call as any).transcript.add('user', 'never mind');
  await voice.hangUp('test');
  assert.equal(got.length, 2);
  assert.equal(got[1].record, true);
  assert.deepEqual(h.all.map(e => e.delivered), [true, false]);
  got[1].delivered?.();
  assert.deepEqual(h.all.map(e => e.delivered), [true, true]);
});

test("acknowledging depends on the voice: a realtime voice already did, the phone's own speech didn't", async () => {
  const deps = { notify: async () => {}, log: () => {} };
  assert.match(new Voice({ provider: 'openai-realtime', auth: 'codex' }, deps).formatting, /don't acknowledge/);
  assert.match(new Voice({ provider: 'android' }, deps).formatting, /first send a short acknowledgement/);
  const { OpenAICall } = await import('../channels/voice/openai.js');
  const handed: string[] = [];
  const call: any = new OpenAICall({ toAgent: t => handed.push(t), log: () => {} }, { apiKey: 'k' });
  const sent: any[] = [];
  call.ws = { readyState: 1, send: (s: string) => sent.push(JSON.parse(s)) };
  call.up = true;
  call.event({ type: 'response.done', response: { output: [{ type: 'function_call', name: 'ask_otto', call_id: 'c1', arguments: '{"request":"book a table"}' }] } });
  assert.deepEqual(sent.map(e => e.type), ['conversation.item.create'], "ask_otto's answer asks the voice for nothing more: no filler while Otto works");
  assert.match(sent[0].item.output, /say nothing until then/);
  await new Promise(r => setTimeout(r, 20));
  assert.match(handed[0], /The voice passes on: book a table\n\(The user knows you're on it: send only the answer/);
  assert.deepEqual((await call.poll(0, 0)).events.filter((e: any) => e.type !== 'state'), [], 'no mute: the user can talk and interrupt');
  clearTimeout(call.gapTimer);
});

test('realtime: a new call starts with what was said on earlier calls, marked as earlier', async () => {
  const { OpenAICall } = await import('../channels/voice/openai.js');
  const h = History.in(fs.mkdtempSync(path.join(os.tmpdir(), 'otto-voice-')));
  h.add('user', 'I parked on level three.', true);
  h.add('voice', 'Got it.', true);
  h.add('otto', 'Saved it to your notes.', true);
  await new Promise(r => setTimeout(r, 5));
  const call: any = new OpenAICall({ toAgent: () => {}, log: () => {}, history: h }, { apiKey: 'k' });
  const sent: any[] = [];
  call.ws = { readyState: 1, send: (s: string) => sent.push(JSON.parse(s)) };
  call.remember();
  const items = sent.map(e => [e.item.role, e.item.content[0].type, e.item.content[0].text]);
  assert.match(items[0][2], /earlier calls, oldest first .*don't answer it again/);
  assert.deepEqual(items.slice(1), [
    ['user', 'input_text', 'I parked on level three.'],
    ['assistant', 'output_text', 'Got it.'],
    ['assistant', 'output_text', '(Otto) Saved it to your notes.'],
    ['system', 'input_text', 'This call starts now.'],
  ]);
});

test('realtime: muting mid-sentence takes what was said as the turn; muted for long, the call is hung up with a reason', async () => {
  const { OpenAICall } = await import('../channels/voice/openai.js');
  const call: any = new OpenAICall({ toAgent: () => {}, log: () => {} }, { apiKey: 'k' });
  const sent: any[] = [];
  call.ws = { readyState: 1, send: (s: string) => sent.push(JSON.parse(s)) };
  call.up = true;
  call.mute(true);
  call.mute(false);
  assert.equal(sent.length, 0, 'muting in silence sends nothing');
  assert.equal(call.mutedSince, 0);
  call.event({ type: 'input_audio_buffer.speech_started' });
  call.mute(true);
  assert.deepEqual(sent.map(e => e.type), ['input_audio_buffer.commit', 'response.create']);
  assert.ok(call.mutedSince > 0);
  clearTimeout(call.gapTimer);

  // The box ending a call itself tells the phone why before it ends.
  const { AndroidCall } = await import('../channels/voice/android.js');
  const a = new AndroidCall({ toAgent: () => {}, log: () => {} });
  await a.begin();
  await a.stop('muted', 'Hung up after 10 minutes on mute.');
  const { events } = await a.poll(0, 10);
  const bye = events.findIndex(e => e.type === 'bye'), ended = events.findIndex(e => e.type === 'state' && e.state === 'ended');
  assert.ok(bye >= 0 && bye < ended);
  assert.equal(events[bye].message, 'Hung up after 10 minutes on mute.');
});

test('what counts as newer talk on a call: real words, not noise, from the user or the voice', () => {
  const u = (text: string) => ({ role: 'user' as const, text }), v = (text: string) => ({ role: 'voice' as const, text });
  for (const l of ['hmm', 'Uh, okay.', 'yes', 'yeah so', 'Thanks!']) assert.equal(weight([u(l)]), 0, l);
  assert.equal(worth([v('On it.'), v('Checking.')]), false, 'an acknowledgement');
  assert.equal(weight([u('um, so what is on at six')]), 'what is on at six'.length, 'fillers dropped');
  assert.equal(worth([u('what time?')]), false, 'a short question alone is under the line');
  assert.equal(worth([u('what time?'), v("It's three fifteen.")]), true, "with the answer the voice gave it, it is");
  assert.equal(worth([v("It's about three in the afternoon.")]), true, 'the voice answering on its own counts');
  assert.equal(worth([{ role: 'otto', text: 'Your meeting moved to four this afternoon.' }]), false, "Otto's own words, never");
});

/** A voice with an android call on, lines put straight in its transcript. */
async function onCall() {
  const h = History.in(fs.mkdtempSync(path.join(os.tmpdir(), 'otto-voice-')));
  const got: Incoming[] = [], notes: string[] = [], logs: string[] = [];
  const voice = new Voice({ provider: 'android' }, { notify: async t => { notes.push(t); }, log: m => logs.push(m), history: h });
  await voice.start(m => got.push(m));
  await voice.open({});
  const call: any = voice.call;
  const spoken = async () => (await call.poll(0, 0)).events.filter((e: any) => e.type === 'speak').map((e: any) => e.text);
  return { voice, call, t: call.transcript as Transcript, got, notes, logs, h, spoken };
}

test("a reply that's behind the call isn't said: the agent gets the newer lines instead, at most twice in a row", async () => {
  const c = await onCall();
  c.t.add('user', 'hmm');
  await c.voice.send('user', 'Your flight is at nine.', { reply: true });
  assert.deepEqual(await c.spoken(), ['Your flight is at nine.'], 'noise holds nothing back');

  c.t.add('user', 'what is the weather tomorrow');
  c.t.add('voice', 'It looks sunny, about twenty five degrees.');
  await assert.rejects(c.voice.send('user', 'Tomorrow is sunny.', { reply: true }), (e: any) => e instanceof Stale
    && e.message === 'Not said: the call has moved on since you wrote this. Newer on the call:\nUser: hmm\nUser: what is the weather tomorrow\n'
      + 'Voice: It looks sunny, about twenty five degrees.\nTake it into account and send your reply again, or send nothing if the call already covered it.');
  assert.deepEqual(c.t.unseen(), [], 'the lines went to the agent: seen');
  assert.ok(c.h.all.filter(e => e.role !== 'otto').every(e => e.delivered), 'and delivered on disk');
  assert.ok(c.logs.some(l => /held back a reply for 3 newer lines on the call \(1 in a row\)/.test(l)));

  c.t.add('user', 'and what about Saturday and Sunday');
  await assert.rejects(c.voice.send('user', 'Sunny.', { reply: true }), Stale);
  c.t.add('user', 'actually also check on Monday please');
  await c.voice.send('user', 'Sunny all week.', { reply: true });
  assert.equal((await c.spoken()).at(-1), 'Sunny all week.', 'the third in a row is said');

  // Not the agent's own reply (`otto send` from a scheduled run): said whatever's new.
  c.t.add('user', 'one more thing about the trip');
  await c.voice.send('user', 'Reminder: pack.');
  assert.equal((await c.spoken()).at(-1), 'Reminder: pack.');
  await c.voice.hangUp('test');

  // No call: a notification, never held back.
  await c.voice.send('user', 'Done.', { reply: true });
  assert.deepEqual(c.notes, ['Done.']);
});

test("a call's new lines go into the agent's turn as one block; a reply sent after them is said", async () => {
  const c = await onCall();
  c.t.add('user', 'uh');
  c.t.add('user', 'and add milk to the list');
  c.t.add('voice', 'Sure, milk too.');
  await new Promise(r => setTimeout(r, 1100));
  assert.equal(c.got.length, 1);
  assert.equal(c.got[0].steer, true);
  assert.equal(c.got[0].text, 'On the call just now:\nUser: uh\nUser: and add milk to the list\nVoice: Sure, milk too.');
  assert.equal(c.t.unseen().length, 3, 'unseen until the core writes it (an idle agent drops it)');
  c.got[0].delivered!();
  assert.deepEqual(c.t.unseen(), []);
  await c.voice.send('user', 'Added eggs and milk.', { reply: true });
  assert.deepEqual(await c.spoken(), ['Added eggs and milk.'], 'not held back: the agent had seen it');

  c.t.add('user', 'okay');
  await new Promise(r => setTimeout(r, 1100));
  assert.equal(c.got.length, 1, 'noise alone steers nothing');
  await c.voice.hangUp('test');
});
