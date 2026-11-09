# voice

Live voice calls from the phone to Codex: the Otto app's Assistant (a long press on the handle, then Assistant).
A proof of concept: `otto-voice` runs on the box by hand (`node box/voice/dist/main.js`), on 127.0.0.1 only; the
app reaches it through the relay's box API, which passes `/voice/…` on to it, so a call is behind the tunnel's
pairing like everything else the app sends to the box.

Each call is one `codex app-server` (stdio JSON-RPC) and a fresh thread in `voice.cwd`, with the user's Codex
sign-in and config. What this installed Codex (0.164) accepts with a ChatGPT sign-in, found by trying:

- realtime `version: "v3"` with `transport: {type: "webrtc", sdp: <the phone's offer>}`; a websocket transport,
  or v1/v2, wants an API key. Codex answers with `thread/realtime/sdp` (the answer), then
  `thread/realtime/started`.
- The audio goes straight between the phone and OpenAI over WebRTC; it never passes through the box. Codex holds
  a side connection to the same call, for its events (transcripts, errors, closed).
- The voice never hands work to Codex on its own. The adapter does it: every finished user utterance
  (`thread/realtime/transcript/done`, role user) becomes `turn/start` on the thread, or `turn/steer` into the turn
  that's running. Codex's answer goes back into the call by itself, and the voice says it. Every utterance, small
  talk included, is a Codex turn: that costs.

The routes, the events the phone reads and the config are at the top of `src/main.ts` and `src/session.ts`. The log
has states, timings and sizes, never transcripts or audio.
