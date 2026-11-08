# Otto on Telegram (proof of concept)

A Telegram bot that is one long-running Claude Code conversation on the box. Write to it from the phone; it
answers from Claude Code running in `~/code` (permission mode `auto`), and shows the tools it's running while it
works. Long or visual answers come back as artifact links, which open in Otto.

## Set up

1. Make a bot with [@BotFather](https://t.me/BotFather) (`/newbot`) and copy its token.
2. Save it, readable only by you:

   ```sh
   echo '{"token": "<token>"}' > ~/.config/otto/telegram.json && chmod 600 ~/.config/otto/telegram.json
   ```

3. Run it: `node telegram/bot.mjs`. It prints a pairing code; send `/start <code>` to the bot. That Telegram account
   becomes the owner; the bot ignores everyone else.

## Commands

- `/new`: start a new conversation (the next message starts a new Claude session).
- `/stop`: stop the running turn.
- `/status`: the session, the folder, and whether it's working.

Photos and files sent to the bot are saved under `~/.local/state/otto/telegram/files` and handed to Claude by path.

## Not yet

Context management (compaction thresholds), wake-ups, approvals, voice notes, streaming the reply as it's written.
