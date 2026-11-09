# Otto

Your phone and a Linux box, wired together for any coding agent.

Doc Ock had four arms. Your agent gets a phone, a browser and a desktop.

Coding agents (Claude Code, Codex, …) run their sessions on a box you own, with its real shell and files. You talk to them from your phone through whatever frontend you like: Claude's remote sessions, the ChatGPT app, Happy, a terminal. Otto gives the agents, and you, what a phone otherwise lacks:

- **a second screen**: an overlay on your phone, one tap on an edge handle away, with the box's files, terminal and desktop, and the agent's artifacts;
- **a browser the agent drives**, on your phone, signed in to your accounts, where you can watch and take over;
- **control of the phone itself**: screenshots, taps, typing, installing the app it just built, reading the logs;
- **an artifact hub**: reports, decks and pages an agent publishes, opened over your chat with one tap.

Everything stays on your [Tailscale](https://tailscale.com) network. Otto never sits between you and the agent.

> Status: early. The pieces run daily on the author's setup and are being moved into this repo one at a time; see [docs/architecture.md](docs/architecture.md) for the design and the order of work.

## How it fits together

```
 phone                                   box (Linux, may sleep)                 hub (always on)
┌──────────────────────┐   tailnet    ┌──────────────────────────────┐        ┌──────────────────┐
│ Otto app             │─────────────▶│ relay  ◀── otto CLI ◀── agent │──────▶│ artifact hub     │
│  second screen       │  dials out   │ files · terminal · desktop    │        │ box start / stop │
│  browser · device    │              │ adapters (Claude, Codex, …)   │        └──────────────────┘
└──────────────────────┘              └──────────────────────────────┘
        ▲
        └── your agent's frontend (Claude app, ChatGPT, Happy, …): links open in the second screen
```

- The **phone** dials out to the box; the box serves the phone's tools on localhost.
- Agents use one command, `otto` (`otto browser open`, `otto phone screenshot`, `otto artifact publish`, …), plus a three-tool MCP server that returns screenshots inline.
- **Adapters** set each agent up: the CLI on its PATH, the instructions file, the MCP server, its session id. Otto never holds provider credentials.

## Layout

| Directory | What |
|---|---|
| `box/relay` | the phone tunnel, the browser per agent session, the device API |
| `box/bots` | chat bots (Telegram), each one long-running agent conversation: see [docs/bots.md](docs/bots.md) |
| `box/services` | files, terminal and desktop on the box |
| `cli` | the `otto` command |
| `mcp` | the eyes MCP server (screenshots) |
| `hub/artifacts` | the artifact hub |
| `hub/box-control` | starting and stopping the box |
| `android` | the Otto app |
| `web` | the web UI for desktop browsers |
| `adapters` | per-agent setup |
| `install` | setup and service units |

## License

MIT
