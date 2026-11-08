# Otto: architecture

Written after the proof of concept: Claude through Happy, and Codex through Happy and through the ChatGPT app, all passed the artifact link, phone browser, phone control and page push tests with the existing second screen.

## What Otto is

Your phone and a Linux box, wired together over Tailscale for any coding agent. Agents run on the box, with its real shell and files; Otto gives them, and you, what a phone otherwise lacks:

- a **desktop** (VNC), a **terminal** and a **files** API on the box;
- an **artifact hub** that stays up while the box sleeps;
- a **browser on your phone** that agents drive, signed in to your accounts;
- **control of the phone**: screenshots, UI tree, taps, typing, installing apps, logs;
- the **second screen**: an overlay that shows all of it over whatever chat app you use.

Otto never sits between you and the agent. You talk to the agent through its own frontend: Claude's remote sessions, the ChatGPT app for Codex, Happy, a terminal, a Kindle page.

## Principles

1. **The agent runs on the box.** Otto only adds what the box can't do natively: the phone, the phone's browser, artifacts, the desktop.
2. **Agent-neutral.** Nothing in Otto reads `~/.claude`, `CLAUDE_CODE_SESSION_ID` or a provider API. Provider knowledge lives in adapters.
3. **Tailscale only.** No public URL, domain or Cloudflare in the core. `tailscale serve` gives every service HTTPS on its `*.ts.net` name. Public sharing comes later, as an add-on.
4. **Devices dial out.** The phone opens connections to the box; the box serves tools on localhost. No listening ports on the phone.
5. **Standard protocols over dumb pipes.** The phone's services (the browser's DevTools, the device API) are reached through one reverse tunnel. Inside it travel CDP and plain HTTP, never a home-made format.
6. **Links work without the app.** Anything written into a transcript is an https URL on the tailnet that opens in any browser; the phone app only opens it more nicely.
7. **One CLI is the interface.** Agents, people and scripts use the same `otto` command. A small MCP server exists only to return screenshots inline.

## Two roles: the box and the hub

| Role | Runs | Uptime |
|---|---|---|
| **Box** | agents and their sessions; files, terminal, desktop; the phone relay; the CLI and the eyes MCP server; adapters | may sleep (idle shutdown) |
| **Hub** | the artifact hub; starting and stopping the box; later, notifications and the scheduler's wake-up | always on, small |

Artifacts live on the hub because they must outlive the box: a report or a deck is opened later, often when the box is asleep. One machine may play both roles; the recommended setup is two (today: devbox as the box, deploy-box as the hub). Every component finds the other role through one config value, its Tailscale URL.

## Repositories

- **`otto`** (public, monorepo): everything in this document. Its parts change together (the tunnel protocol touches the Android app, the relay and the CLI at once), it installs as one thing, and recognition gathers in one place.
- **The Claude frontend** (public, separate; today's cc-mobile, renamed later): the Android wrapper around claude.ai/code, the sessions API for Claude remote control, and the Kindle web frontend. Separate because it's Claude-specific and relies on an undocumented Anthropic API: if that changes, Otto is unaffected.
- **Ubun2** (private): personal setup, unchanged.

An organisation on GitHub can wait until there are more repos or contributors.

## The `otto` repo

```
otto/
  README.md              the pitch, the picture, a 5-minute install
  docs/                  architecture, protocols, security, writing an adapter
  box/                   on the box
    relay/               phone tunnel, browser CDP per session, device API, page push   (TypeScript)
    services/            files, terminal, desktop                                       (Python, from devbox-hub)
  cli/                   `otto`                                                         (TypeScript)
  mcp/                   the eyes MCP server                                            (TypeScript)
  hub/
    artifacts/           the artifact hub                                               (Python/FastAPI, from artifact-hub)
    box-control/         start and stop the box                                         (from Ubun2's devbox panel)
  android/               the Otto app: second screen + device agent, one APK           (Kotlin; from second-screen + device-agent)
  web/                   shared UI: files, terminal, desktop, artifacts                  (from assets/ide)
  adapters/
    claude/              setup for Claude Code
    codex/               setup for Codex
  install/               `otto setup`, systemd units, pairing
```

TypeScript for the relay, CLI and MCP server: Playwright, which the browser commands need, is native to Node, and the three share code. The Python services stay Python; porting them gains nothing yet.

## Components

### Relay (`box/relay`)

One daemon on the box, replacing cc-mobile's `phone-browser-relay.js` and device-agent's `server.js`.

- **Tunnel**: the phone keeps one control connection open (TCP over Tailscale, token in the first message) and opens a pipe per request when the relay asks, as `BrowserLink` does today. Behind the tunnel the phone serves two things: the WebView's DevTools socket, and the device HTTP API.
- **Browser**: a persistent Playwright connection over the tunnelled DevTools socket. Each agent session gets its own tab group; `Target.getTargets` is filtered to the session's own tabs, as now.
- **Device API**: proxied to localhost for the CLI.
- **Page push**: `show` opens a URL in the second screen.
- **State**: session → group map, with old sessions dropped after 30 days of disuse.

Removed in the move, as Claude-specific or obsolete:
- finding a Claude chat from the MCP server's parent process and `~/.claude/sessions` (`chatOf`, `claudeOf`);
- titles read from Claude transcripts (`sessionTitle`);
- the `chat` command and the "go to chat" link on tab groups;
- the app-side workarounds that kept claude.ai's own WebView out of the DevTools socket: the Otto app has no claude.ai WebView.
- per-app group prefixes (`g` in cc-mobile, `sg` in second-screen): one app, one namespace.

### Session identity

The CLI and the eyes MCP server read the session from the environment: `OTTO_SESSION`, set by the adapter (Claude's adapter sets it from `CLAUDE_CODE_SESSION_ID`; Codex's from its thread id where available), and `OTTO_SESSION_TITLE` for the group's name. Without one, commands use a shared default group. The proof of concept showed why: under Codex every session fell back to the same literal key, `pid`, and would have shared one tab group.

### `otto` CLI (`cli/`)

A thin client: each command is one HTTP call to the relay or the hub. State lives in the daemons, so commands answer in milliseconds.

| Command | Does |
|---|---|
| `otto browser open \| snapshot \| click \| type \| screenshot \| tabs \| eval` | snapshot-first, with element refs that click and type use |
| `otto phone screenshot \| ui \| tap \| type \| key \| launch \| install \| logcat` | the device API |
| `otto desktop screenshot` | the box's VNC desktop |
| `otto show <url>` | open a page in the second screen |
| `otto artifact publish \| list \| share` | the hub's artifact API |
| `otto notify <text>` | a notification on the phone |
| `otto status` | box, hub, phone, relay at a glance |
| `otto setup` | install, pair, run adapters |

Output is short text for a model. Screenshots are saved as files and the path is printed.

### Eyes MCP server (`mcp/`)

Three tools: `desktop_screenshot`, `browser_screenshot`, `phone_screenshot`. Each runs the CLI command named in its description and returns the image inline, downscaled to about 1,500px on the long side unless asked for full size or a region. It replaces today's `phone` and `phone-browser` MCP servers; Playwright MCP is no longer needed for the phone.

### Android app (`android/`)

The second screen and the device agent in one APK.

- The handle, the overlay, `ScreenActivity`, link capture and boot restore, from second-screen.
- The browser runs in its own process (`:browser`), drawn into the second screen with `SurfaceControlViewHost`. Android's switch for WebView debugging (the DevTools socket agents drive) is per process: in one process, agents could also reach the app's other WebViews, such as the terminal (and type into your shell) or HTML artifacts. Its own process also gives the browser its own cookies and lets a crash or a memory kill take only the browser down.
- The accessibility service, screenshots, input, installs and logs, from device-agent, now served as a device HTTP API through the tunnel. Handled in-process from tunnel pipes, never on a localhost port (every app on the phone can reach localhost).
- Link capture: `*.ts.net` with path prefixes, keeping only the configured box and hub hosts at runtime and handing every other link to the browser. *To verify: whether Android lets the user approve a wildcard host under Open by default.* If not, the host goes into the build.
- Pairing replaces the two tokens baked into today's APKs: `otto setup` shows a code or QR, the app stores the box URL and token.
- One-time adb grants for logs (`READ_LOGS`) during setup.

#### Stack: Kotlin, native screens

The app is written in Kotlin; the existing Java (second-screen, and device-agent's Android side) is rewritten as it moves in, not left alongside. New screens use Jetpack Compose.

Everything is native except what is web by nature:

| Section | Native, on Android | Stays web |
|---|---|---|
| Files | file manager: lists, thumbnails, search, open-with, offline-cached listings | |
| Artifacts | list and search; viewer for PDFs (Android's `PdfRenderer`), office files (their PDF), images (Coil), audio and video (Media3), markdown (Markwon), text and code | HTML artifacts (they are web pages: a WebView) |
| Browser | tabs, groups, toolbar, menus (already native views) | the pages themselves (embedded Chromium) |
| Box status | start, stop, status | |
| Terminal | | xterm.js |
| Desktop | | noVNC |

The web UI (`web/`) stays as the UI for desktop browsers and the Kindle, and as the fallback; the Android app no longer uses it for the native sections. Native and web read the same box and hub APIs, so neither needs anything of its own.

Done after the restructure (order of work, step 9), one section at a time, starting with the artifact viewer: the biggest latency win, and it uses the same hub change as the web viewer (serving the PDF itself).

### Box services (`box/services/`)

Files API, terminal (tmux over websocket) and desktop (Xvnc, XFCE, websockify, noVNC) from devbox-hub, minus Claude session cwd lookups, Claude tab titles, the sessions API, Kindle and dictation.

### Artifact hub (`hub/artifacts/`)

From artifact-hub, with tailnet-header auth as the default and Cloudflare Access as a later add-on (off). Two changes:

- **PDF previews render from the PDF itself.** Today every page is converted to an SVG with pdftocairo, which costs time on upload and in the browser for heavy pages. Instead: office files still become one PDF through Gotenberg, and the viewer loads that PDF directly with PDF.js, which renders pages on demand and fetches the file in ranges, so the first page shows before the rest has loaded. PDFs are served as they are.
- **Thumbnails become small raster images** (WebP from the first page) instead of SVGs.

HTML artifacts, markdown, images, audio and video are unchanged.

### Web UI (`web/`)

Files, terminal, desktop and the artifacts browser, from `assets/ide`. Used by any desktop browser on the tailnet and the Kindle; on Android, only the terminal, the desktop and HTML artifacts use it once the native sections are in. Hosts come from config at runtime instead of being written into the code.

### Adapters (`adapters/`)

Each adapter does three things, none of them in the conversation:

1. **Setup**: put `otto` on the agent's PATH and allow it in the agent's permissions; register the eyes MCP server; install the shared instructions as `CLAUDE.md` / `AGENTS.md` from one source; set `OTTO_SESSION`; install hooks where the agent has them (Claude's Stop and Notification → `otto notify`); check the agent's own login (`claude login`, `codex login`). Otto never holds provider credentials.
2. **Run headless**: `claude -p …`, `codex exec …`, for the scheduler later.
3. **List sessions** (optional).

What the proof of concept taught about Codex:
- Codex refuses to run in an untrusted folder ("Can't verify project trust"). The adapter marks project folders as trusted and expects them to be git repos.
- Codex's default sandbox has no network, so it can't reach the relay or the hub. The adapter configures a sandbox profile that allows the box's and hub's own services, or full access.
- Codex remote control (the ChatGPT app) requires two-factor login on the ChatGPT account.

## Deployment

Setup is by hand at first, per machine, and gets standardised later. Which machine runs what (one machine or two; the artifact hub and Gotenberg on the hub or the box) is a setup choice, not part of the design. Today's split stays as it is: the artifact hub on the hub, Gotenberg on the box. Previews are made when an artifact is published, which happens from the box, so Gotenberg is up whenever it's needed.

## Network and config

- One config file on the box, `~/.config/otto/config.toml`: the box's and hub's Tailscale URLs, ports, the pairing token. The phone gets what it needs at pairing.
- `tailscale serve` publishes each service on the box's and hub's `*.ts.net` names with real certificates.
- Short, memorable links (a redirect page per service on your own domain) are documented as an optional extra.

## Security

Anyone on your tailnet is treated as you: the files API, the terminal and VNC have no login of their own today, and VNC has no password. Before the first release, either add per-service tokens or state this plainly in the README with the tailnet ACLs to apply. The tunnel token is per install and never part of a public build.

## The Claude frontend repo

- The claude.ai wrapper app, with its second screen removed: it uses the Otto app, installed alongside.
- The sessions API for Claude remote control, from devbox-hub.
- `kindle/`: the Kindle web frontend and its dictation, from devbox-hub.
- Claude-specific hooks (the transcript-reading notifier) live in Otto's Claude adapter.

## Order of work

Today's setup keeps working throughout; each piece switches over once its Otto version passes the same four tests as the proof of concept.

1. **Scaffold** the repo: layout, docs, config file, pairing token.
2. **Relay** with agent-neutral sessions, replacing `phone-browser-relay.js` and device-agent's server. Removes the Claude couplings listed above.
3. **CLI and eyes MCP server**, on the relay.
4. **Adapters** for Claude and Codex; rerun the four tests with the CLI.
5. **Android**: fold the device agent into the second screen app; the reverse tunnel and device HTTP API.
6. **Web UI and box services** from devbox-hub.
7. **Artifact hub** into `hub/artifacts/`, with PDF.js previews and raster thumbnails.
8. **The Claude frontend repo**: remove the second screen; move in the sessions API and Kindle.
9. **Native Android, one section at a time**: artifact viewer, artifacts list, file manager, markdown, box status. (The Kotlin rewrite of the existing Java happens in step 5, as the code moves into `android/`.)

## Open decisions

- Wildcard link capture versus the host in the build: to be tested on a phone first.
- Per-service tokens: later; tailnet-only until then.
- A GitHub organisation: later.
