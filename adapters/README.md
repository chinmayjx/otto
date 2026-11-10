# adapters

One per coding agent. An adapter sets the agent up to use Otto, and never touches the conversation:

1. **setup**: `otto` on the agent's PATH and allowed in its permissions; the eyes MCP server registered; the shared instructions installed (`CLAUDE.md`, `AGENTS.md`); `OTTO_SESSION` set to the agent's session id; hooks where the agent has them; a check that the agent is logged in (with its own login command: Otto never holds provider credentials);
2. **run headless**: one task without a frontend (`claude -p`, `codex exec`), for the scheduler;
3. **sessions** (optional): `<agent>/sessions.mjs` with `list()` and `send(id, text)`, for `otto sessions`, from what the
   agent itself keeps on disk and through its own ways in (Claude Code: its session records, transcripts and peer inbox).

`claude/` and `codex/` come first.
