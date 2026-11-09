# Bots

A bot is a chat on your phone that is one long-running agent conversation on the box. Write to it as you would to a
person; the agent (Claude Code, in `~/code` by default, permission mode `auto`) works on the box and answers in the
chat, with files when it makes them and artifact links for anything long. A message sent while it works goes into
the turn in progress. Only one account, the owner, can use a bot; it ignores everyone else.

Telegram is the only channel so far.

## Setup

1. Run the service once per box: copy `install/otto-bots.service` to `/etc/systemd/system/`, set `User` and the
   paths in it, then `sudo systemctl daemon-reload && sudo systemctl enable --now otto-bots`. The agent's own login
   (`claude login`) is the user's, as for any Claude Code on the box.
2. Make a bot with [@BotFather](https://t.me/BotFather) in Telegram (`/newbot`) and copy its token.
3. Add it to the box, giving the token on stdin or at the prompt (never as an argument, which other users can see):

   ```sh
   otto bot add mybot --channel telegram          # options: --cwd DIR, --model M, --permission-mode MODE
   ```

   The token is checked with Telegram and kept in `~/.config/otto/bots/mybot.json`, readable only by you.
4. Pair it: `otto bot add` prints a link (`https://t.me/<bot>?start=<code>`). Open it on your phone and press
   Start; your Telegram account becomes the bot's owner. `otto bot pair mybot` prints the link again, and
   `--reset` hands the bot to another account.

## In the chat

- `/new`: a new conversation.
- `/stop`: stop the agent; the next message resumes the same conversation.
- `/status`: the session, the folder, and whether it's working.

Photos and files you send are saved on the box (`~/.local/state/otto/bots/<name>/files`) and handed to the agent by
path.

## On the box

`otto bot list` shows the bots, whether each is running and paired; `otto bot logs <name> [--follow]` its log;
`otto bot rm <name>` removes one. The service also logs to the journal (`journalctl -u otto-bots`).
