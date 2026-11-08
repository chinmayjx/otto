# relay

The daemon on the box that holds the phone's tunnel and serves the phone's tools on localhost:

- the phone browser over Chrome's DevTools protocol, one tab group per agent session (a persistent Playwright connection, so CLI commands answer at once);
- the device API (screenshots, UI tree, input, installs, logs), proxied from the phone;
- `show`: open a page in the second screen.

Replaces cc-mobile's `tools/phone-browser-relay.js` and device-agent's `devbox/server.js`. Sessions are named by the agent (`OTTO_SESSION`), never looked up from an agent's own files.
