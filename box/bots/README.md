# bots

Chat bots on the box: each bot is one long-running agent conversation (Claude Code so far) that you write to from a
chat app on your phone (Telegram so far). The `otto-bots` service runs every bot configured in
`~/.config/otto/bots/<name>.json`; `otto bot add`, `pair`, `list`, `logs` and `rm` manage them.

Three layers, each behind an interface:

- **channel** (`channel.ts`; `channels/telegram.ts`): receives messages and attachments, sends text and files, shows
  typing, lists the bot's commands. Telegram's is long polling over IPv4 only, with Markdown turned into Telegram's
  HTML (`channels/telegram-html.ts`) and long text split at paragraph breaks.
- **core** (`core.ts`): pairing (an unowned bot makes a one-time code; whoever sends `/start <code>` becomes the
  owner, and everyone else is ignored), the commands (`/new`, `/stop`, `/status`), steering (a message while a turn
  runs goes into that turn), and the reply rule: the agent answers only through its tools, and a turn that sends
  nothing is reminded once, then the user is told. The tools reach the core over a unix socket in the bot's state
  folder (0600).
- **agent provider** (`provider.ts`; `providers/claude.ts`): one long-running `claude -p` with stream-json in and
  out, resumed by session id, with the bot's tools as an MCP server. A Codex provider would go next to it.

`tools.ts` is that MCP server (`send_message`, `send_file`), on stdio; it only talks to the core's socket.

Config, per bot: `{ channel, token, owner?, session?, cwd? (~/code), permissionMode? (auto), model? }`; the bot
writes `owner` and `session` itself. State, under `~/.local/state/otto/bots/<name>/`: `tools.sock`, `files/` (what
the user sent), `bot.log`, and `status.json` (the bot's name on the channel and, while unowned, its pairing code;
`otto bot pair` reads it). The service reloads on SIGHUP (new bots start, removed ones stop, changed ones restart);
the CLI sends it after each change. The unit is `install/otto-bots.service`.

`npm test` runs the core against a fake channel and agent, the tools server against the core's socket, and the
Claude provider against a fake `claude` (`CLAUDE_BIN`).
