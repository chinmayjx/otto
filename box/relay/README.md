# relay

The daemon on the box that holds the phone's tunnel and serves the phone's tools on localhost:

- the tunnel (`tunnel.ts`): the Otto app's one control connection, and a pipe per stream (`devtools` or `device`),
  all dialled from the phone; the protocol is in `docs/architecture.md`;
- the phone browser over Chrome's DevTools protocol, one tab group per agent session (`browser.ts`, `driver.ts`; a
  persistent Playwright connection, so CLI commands answer at once);
- the device API (screenshots, UI tree, input, installs, logs; `device.ts`): `POST /phone/<cmd>` on localhost,
  forwarded as HTTP to the app through kept-alive `device` pipes, screen commands one at a time;
- `show`: open a page in the second screen.

The local API is listed at the top of `api.ts`. Config: `~/.config/otto/config.toml` (`relay.tunnel_port`,
`relay.pipe_port`, `relay.local_port`, `phone.token_file`, which falls back to `phone.browser_token_file`).

`npm test` runs it end to end against a fake phone (`src/test/fake-phone.ts`) that speaks the tunnel: headless
Chromium behind its `devtools` pipes, a small fake device API behind its `device` pipes.

Replaces cc-mobile's `tools/phone-browser-relay.js` and device-agent's `devbox/server.js`. Sessions are named by the agent (`OTTO_SESSION`), never looked up from an agent's own files.
