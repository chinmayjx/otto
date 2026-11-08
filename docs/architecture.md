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

## One machine first

The core runs on one machine, the box: agents, the relay, files, terminal, desktop, and the artifact hub. Deployment strategies (an always-on second machine for the artifact hub and for waking the box, which suits a box that sleeps) come later, as configuration.

Today's own setup is the exception, kept as it is for now: the artifact hub runs on a second, always-on machine (deploy-box), and Gotenberg, which makes its office previews, runs on the box. Components find each other through URLs in the config, so this works without the core knowing about it.

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
    box-control/         start and stop the box, for deployments with a second machine  (later)
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

- **Tunnel**: the phone keeps one control connection open (TCP over Tailscale, token in the first message) and opens a pipe per stream when the relay asks. Behind the tunnel the phone serves two things: the WebView's DevTools socket, and the device HTTP API. The protocol is below.
- **Browser**: a persistent Playwright connection over the tunnelled DevTools socket. Each agent session gets its own tab group; `Target.getTargets` is filtered to the session's own tabs, as now.
- **Device API**: served to the CLI on localhost as before (`POST /phone/<cmd>`, JSON args), each command forwarded as an HTTP request to the phone through a `device` pipe.
- **Page push**: `show` opens a URL in the second screen.
- **State**: session → group map, with old sessions dropped after 30 days of disuse.

#### The phone's tunnel

Two TCP ports on the box's tailnet address: the control port (9401, `relay.tunnel_port`) and the pipe port (9402, `relay.pipe_port`). The phone dials both; nothing listens on the phone. Tailscale encrypts; the token (`phone.token_file`, given to the app at pairing) authenticates the control connection. Code: `box/relay/src/tunnel.ts` and `android/…/Tunnel.kt`.

**Control connection**: lines of UTF-8 text, each ending in `\n`.

| Direction | Line | Means |
|---|---|---|
| app → relay | `hello <package> <token> 2` | first line; `2` is the protocol version (an app that sends none is an old browser link: no device API) |
| | `info <json>` | the phone: `{"device","android","app","versionCode"}` |
| | `browser up` / `browser down` | the browser's process came up (its `tab` lines follow) or went away |
| | `active` | the user is looking at this app: new agent tabs go to it |
| | `tab <target> <tab> <group\|->`, `gone <target>` | a live tab and its DevTools target; a tab closed or gone dormant |
| | `made <req> <target> <group>`, `woke <req> <n>`, `fail <req>` | answers to `newtab` and `wake` |
| | `ping` | now and then; ignored |
| relay → app | `open <pipe> <service>` | open a pipe for `devtools` or `device` |
| | `newtab <req> <group\|-> <title>`, `wake <req> <group>`, `idle <group>` | a session's tabs: open one, bring its group to life, let it rest |
| | `close <target>`, `closegroup <group>`, `focus <target>`, `show <url>` | close a tab or a group; show a tab or a page in the second screen |
| | `ping` | every 30 s; the app takes 90 s of silence as a dead path and reconnects |

**Pipes**: for each `open`, the app connects to the pipe port and sends `<pipe>\n` (24 hex digits, asked for moments before and good once), then the connection is the service's own stream:

- `devtools`: the browser process's DevTools socket, spoken to in CDP (WebSocket over HTTP). One pipe per agent session's connection. The browser's process opens these pipes itself; its other lines go through the main process's control connection.
- `device`: the device HTTP API, HTTP/1.1 served by the app in-process from the pipe. Pipes are kept alive between requests; the relay keeps a couple of spares open ahead of need, so a command costs one round trip. Commands that touch the screen still run one at a time.

**Device API** (one route per command; `GET` for those that only read, args in the query, lists comma-separated; `POST` with JSON args for the rest):

| Route | Args | Reply |
|---|---|---|
| `GET /info` | | JSON: model, Android, screen, battery, network, permissions, the app in front |
| `GET /current` | | JSON: the app in front (`package`, `activity`) |
| `GET /screenshot` | `scale`, `format` (`png`, `jpg`), `quality` | the image; `X-Otto-Result: {"width","height","format"}` |
| `GET /ui` | `all`, `xml` | text: one line per element, with bounds (or XML) |
| `GET /wait` | `text` \| `id` \| `desc`, `index`, `gone`, `timeout` | text |
| `GET /packages` | `thirdParty`, `filter`, `labels` | JSON list |
| `GET /appinfo` | `package` | JSON |
| `GET /logcat` | `package`, `level`, `grep`, `lines`, `since`, `tags` | the lines as text; `X-Otto-Result: {"fullAccess"}` |
| `GET /events` | | text: what brought the app up and down (updates, boot, the tunnel) |
| `POST /tap`, `/longpress` | `x`, `y` \| `text`, `id`, `desc`, `index`; `ms` | text |
| `POST /swipe` | `direction` \| `x1`, `y1`, `x2`, `y2`; `ms` | text |
| `POST /text` | `text`, `replace` | text |
| `POST /key` | `key` | text |
| `POST /launch` | `package`, `activity` \| `url` | text |
| `POST /forcestop`, `/uninstall` | `package` | text |
| `POST /install?name=…` | the APK as the body | text; for Otto itself the reply comes first, then the update |

Replies are `200` with `application/json` (the result), `text/plain` (a string result) or bytes of their own type with the result as JSON in `X-Otto-Result`; `404` for an unknown command; `500` with the reason as text when a command fails. The relay turns these into the local API's replies (`{result}`, or the bytes with `X-Otto-Result`).

Removed in the move, as Claude-specific or obsolete:
- finding a Claude chat from the MCP server's parent process and `~/.claude/sessions` (`chatOf`, `claudeOf`);
- titles read from Claude transcripts (`sessionTitle`);
- the `chat` command and the "go to chat" link on tab groups;
- the app-side workarounds that kept claude.ai's own WebView out of the DevTools socket: the Otto app has no claude.ai WebView.
- per-app group prefixes (`g` in cc-mobile, `sg` in second-screen): one app, one namespace.

### Session identity

The CLI and the eyes MCP server read the session from the environment: `OTTO_SESSION`, set by the adapter (Claude's adapter sets it from `CLAUDE_CODE_SESSION_ID`; Codex's from `CODEX_THREAD_ID`, which Codex gives every command), and `OTTO_SESSION_TITLE` for the group's name. Without one, commands use a shared default group. The proof of concept showed why: under Codex every session fell back to the same literal key, `pid`, and would have shared one tab group.

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
- Link capture: `*.ts.net` with path prefixes, keeping only the configured box and hub hosts at runtime and handing every other link to the browser. Android does let the user approve the wildcard host under Open by default (checked on Android 17). Hosts outside the tailnet (an artifact hub's public domain) go into the build: `otto.extraLinkHosts`.
- Pairing replaces the two tokens baked into today's APKs: `otto pair` builds an `otto://pair?…` link (printed, as a QR code, or opened on the phone with `--send`); the app shows it, asks, and stores the box, the tunnel's ports, its one token and the hub's and artifact hub's URLs.
- After an update or a restart, the app comes back on its own: `BootReceiver` (boot, `MY_PACKAGE_REPLACED`) restarts the handle, and the accessibility service, which Android binds again, restarts the tunnel too. Otto installs its own updates through the device API (`otto phone install otto.apk`), unattended: it is its own installer of record, and it answers before it commits the update.
- The browser's process is a foreground service of its own while the handle is on (one shared notification), so the browser link stays up with the screen closed.
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

Where it stands: Artifacts, Files and Box status (the Devbox section) are native (`android/…/ui/`, Compose and Material 3 in Otto's own colors: AMOLED black, steel greys and the icon's red; see `ui/Theme.kt`), and so is the viewer, opened from both and from captured artifact and `/files` links. Libraries: Coil (thumbnails, images, SVG), Telephoto (zoomable images), Media3, Markwon, OkHttp. PDFs and office files render from the artifact hub's `preview.pdf` with `PdfRenderer`: pages are laid out at once from the sizes the hub gives, rendered at screen width as they scroll in, and after a zoom the part on screen is rendered again at full resolution. Lists open from what they showed last and refresh behind it. Two things still go to the web viewer: HTML (artifacts and files), and office files in Files, because the box's files API serves those as page images rather than a PDF (`/api/fs/pages`); a `preview.pdf` there would bring them over too.

#### Dictation for the Kindle

The Kindle frontend records through the phone's microphone. For now the device agent keeps doing it (the relay passes `mic_start`, `mic_read`, `mic_stop` through as device commands). It belongs to the Claude frontend app, which will get a configuration tray, pulled down from its top bar, with the switch for it.

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

Setup is by hand at first and gets standardised later. Previews are made when an artifact is published, which happens from the box, so Gotenberg is up whenever it's needed.

## Network and config

- One config file on the box, `~/.config/otto/config.toml`: the box's and hub's Tailscale URLs, ports, the phone's token (`phone.token_file`). The phone gets what it needs at pairing.
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

- Per-service tokens: later; tailnet-only until then.
- A GitHub organisation: later.
