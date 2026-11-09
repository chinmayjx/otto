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
  call.ws = { readyState: 1, send: () => {} };
  call.up = true;
  call.event({ type: 'response.done', response: { output: [{ type: 'function_call', name: 'ask_brain', call_id: 'c1', arguments: '{"request":"book a table"}' }] } });
  await new Promise(r => setTimeout(r, 20));
  assert.match(handed[0], /The voice passes on: book a table\n\(The voice has told the user you're on it: send only the answer/);
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
  call.event({ type: 'input_audio_buffer.speech_started', item_id: 'u1', audio_start_ms: 0 });
  call.mute(true);
  assert.deepEqual(sent.map(e => e.type), ['input_audio_buffer.commit']);
  call.event({ type: 'input_audio_buffer.committed', item_id: 'u1' });
  assert.deepEqual(sent.map(e => e.type), ['input_audio_buffer.commit', 'response.create'], 'answered as any turn');
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

test('stop words: on their own, in English or Hindi; not inside a sentence', async () => {
  const { stopWord } = await import('../channels/voice/openai.js');
  for (const t of ['Wait.', 'wait, wait', 'Stop!', 'Hold on', 'Ruko', 'bas', 'Ek minute.', 'रुको।', 'Otto, wait', 'wait please']) assert.ok(stopWord(t), t);
  for (const t of ['Wait, what time is it?', 'I said stop the music', 'okay', '', 'Thank you.']) assert.ok(!stopWord(t), t);
});

test('realtime: talking over the voice: noise dropped; clean words, a stop word or long speech stop it', async () => {
  const { OpenAICall } = await import('../channels/voice/openai.js');
  const call: any = new OpenAICall({ toAgent: () => {}, log: () => {} }, { apiKey: 'k', bargeIn: 1.5 });
  const sent: any[] = [];
  call.ws = { readyState: 1, send: (s: string) => sent.push(JSON.parse(s)) };
  call.up = true;
  const wait = (ms: number) => new Promise(r => setTimeout(r, ms));
  const count = (t: string) => sent.filter(e => e.type === t).length;
  const turns = () => sent.filter(e => e.type === 'session.update').at(-1).session.audio.input.turn_detection;
  const heard = (id: string, text: string) => {
    call.event({ type: 'input_audio_buffer.committed', item_id: id });
    call.event({ type: 'conversation.item.added', item: { id, type: 'message', role: 'user', content: [{ type: 'input_audio' }] } });
    call.event({ type: 'conversation.item.input_audio_transcription.completed', item_id: id, transcript: text });
  };
  const voiceTalks = () => {
    call.event({ type: 'response.created' });
    call.event({ type: 'response.output_item.added', item: { id: 'a1', type: 'message' } });
    call.event({ type: 'output_audio_buffer.started' });
  };
  const voiceDone = () => {
    call.event({ type: 'output_audio_buffer.stopped' });
    call.event({ type: 'response.done', response: { output: [] } });
  };

  // Not talking: a turn is answered at once.
  heard('u0', 'Hello there');
  assert.equal(count('response.create'), 1);
  voiceTalks();
  assert.equal(turns(), null, 'turn detection off while it talks');
  await wait(1100);
  assert.ok(count('input_audio_buffer.commit') >= 1, 'listening in pieces');

  // A horn heard as words: a stray piece, dropped once nothing follows; the voice goes on.
  heard('h', 'Thank you.');
  heard('n', '');
  assert.equal(count('conversation.item.delete'), 2);
  assert.equal(count('response.cancel'), 0);

  // A short question heard cleanly stops it at once, and is answered once it's done.
  heard('q1', "What's the capital");
  assert.equal(count('response.cancel'), 1);
  heard('q2', 'of France?');
  voiceDone();
  assert.equal(turns().type, 'semantic_vad', 'turn detection back on');
  call.event({ type: 'input_audio_buffer.committed', item_id: 't' }); // the last piece
  call.event({ type: 'conversation.item.input_audio_transcription.completed', item_id: 't', transcript: '' });
  await wait(900);
  assert.equal(count('response.create'), 2, 'answered after');

  // "Stop." or "No, no." stops it at once, and isn't answered; what it never said is cut.
  voiceTalks();
  heard('w', 'No, no.');
  assert.equal(count('response.cancel'), 2);
  assert.equal(count('conversation.item.truncate'), 2);
  voiceDone();

  // Speech that isn't clean, piece after piece, stops it after bargeIn seconds.
  voiceTalks();
  heard('l1', 'Sim.');
  assert.equal(count('response.cancel'), 2);
  await wait(600);
  heard('l2', 'de');
  assert.equal(count('response.cancel'), 3);
  voiceDone();
  clearInterval(call.pieceTimer);
  clearTimeout(call.gapTimer);
  await wait(900);
});
