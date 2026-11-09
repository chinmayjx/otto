# Bots

A bot is one long-running agent conversation on the box, which you reach through its channels: a Telegram chat, and
on one bot, your assistant, a voice call from the Otto app. Write to it as you would to a
person; the agent (Claude Code, in `~/code` by default, permission mode `auto`) works on the box and answers in the
chat, with files when it makes them and artifact links for anything long. A message sent while it works goes into
the turn in progress. Only one account, the owner, can use a bot; it ignores everyone else.

Every message reaches the agent tagged with its channel (`<message channel="telegram">…</message>`), and the agent
replies with `send_message(channel, text)`, following that channel's rules: Markdown for Telegram, plain speakable
sentences for the voice.

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

## Messages from anywhere on the box: `otto send`, and channels' logs

Any session or script on the box can message you on a bot's channel, not only the bot itself:

```sh
otto send telegram "The build is green."           # as the bot that has Telegram (the assistant's, if several)
otto send telegram --file report.pdf --caption "Weekly review"
otto send otto_voice "Your 6 o'clock is in ten minutes."   # spoken on a call, else a phone notification
otto send notify "Done."                           # a phone notification
otto send telegram --bot other "…"                 # when several bots have Telegram
```

It goes through otto-bots (`POST /send` on its local server), which holds the tokens. Nothing goes into the bot's
conversation: each channel keeps its own log of everything sent and received on it, and who did it (`bot otto`,
`schedule morning (run r…)`, `session …`, `user`), in `~/.local/state/otto/channels/<channel>.jsonl`.
`otto channel log telegram [--since 2h]` reads it; the bots are told to look there when you reply to a message they
don't remember sending.

## Schedules

A schedule is a prompt run at a time: when it comes due, otto-bots starts a fresh headless session (Claude Code,
`claude -p` in permission mode `auto`; or Codex, `codex exec`) with that prompt, in `~/code` or its own folder. The
run sees nothing but its prompt; it reaches you, if it wants to, with `otto send`, and saying nothing is fine. Times
are in `[schedules] timezone` (default Asia/Kolkata).

```sh
otto schedule add morning --at 08:00 --every weekday --prompt "Look at my calendar for today and ..."
otto schedule add rent --cron "0 10 1 * *" --prompt-file rent.md
otto schedule add mom --once "2026-11-10 18:00" --prompt 'Remind the user on Telegram to call Mom: otto send telegram "..."'
otto schedule list | show <name> | edit <name> --at 07:30 | pause <name> | resume <name> | rm <name>
otto schedule run <name>                 # now
otto schedule runs [<name>]              # recent runs: status, transcript
otto schedule runs <name> <run>          # what that run did
```

Usually you ask your assistant ("every weekday at 8, tell me what's on my calendar") and it runs these; the Otto
app's Schedules section lists them, with their runs, Run now and Pause. Each schedule is
`~/.config/otto/schedules/<name>.json`; its runs are in `~/.local/state/otto/schedules/<name>/` (`runs.jsonl`, and
each run's output as `<run>.log`), and each run's session is an ordinary one (its transcript path is in the run),
which can be read or resumed. A run whose schedule's last run is still going is skipped; one past its timeout (30
min by default) is stopped; a time missed by more than 5 minutes (the box was off) is recorded as missed, not run
late; three failed runs in a row bring one phone notification.

## The assistant: Otto's voice

One bot can have the `otto_voice` channel; that bot is your assistant. On the phone, long-press the handle (or swipe it
in and pick Assistant): a call starts, with a pill over every app (tap to mute, × to end; a long press on the handle
hangs up too). Muting mid-sentence counts what you said so far as said; a call left muted for 10 minutes is hung up
(the next call carries on from the saved transcript). With OpenAI's voice, a horn or a cough doesn't cut it off: what
the microphone takes for speech while it talks is transcribed (silence never is), and it stops as soon as that's clear words (two
real words or more, heard with confidence, not its own voice coming back), or a stop word ("stop",
"wait", "no no", "hold on", "ruko", "bas", "ek minute"). Nothing else does (`--barge-in 0` instead lets any sound stop it). What stopped it is answered once it's done; noise is dropped. The box's log has a line for
each thing it heard over the voice and what it decided. What
the assistant says when no call is on comes as a phone notification. The Otto app's main screen shows which bot it is, and lets you pick another.

```sh
otto bot voice mybot                              # OpenAI's realtime voice: asks for an API key on stdin
otto bot voice mybot --auth codex                 # the same voice, with the Codex ChatGPT sign-in (codex login)
otto bot voice mybot --provider android           # the phone's own speech recognizer and text-to-speech (free)
otto bot voice mybot --model gpt-realtime-2.1 --voice marin
otto bot voice mybot --off
```

- **openai-realtime**: a realtime voice model is on the call (audio goes straight between the phone and OpenAI; the
  box creates the call with a key and joins it on a side connection). With an API key, that key is the one in the
  config. With `--auth codex`, each call mints a short-lived realtime key from the Codex sign-in in
  `~/.codex/auth.json` and opens the call with it; Otto only reads the sign-in; Codex renews it whenever it runs (if it has expired, run any `codex` command). The voice handles small talk itself and hands
  everything else to the bot with its `ask_brain` tool, fire and forget: the bot gets everything said on the call
  since the last hand-off, and its replies are spoken word for word at the next gap. A new call isn't a blank slate: the voice starts
  with what was said on earlier calls (the saved transcript below) as the conversation so far, for follow-ups;
  anything that needs facts still goes to the bot. The default model is `gpt-realtime-2.1` (the voice is `marin`). On an API key that is about $0.07 a minute; `gpt-realtime-2.1-mini` is the cheaper one, about $0.02–0.04.
- **android**: no voice model in between; every sentence the phone hears goes to the bot, and its replies are read
  out by the phone.

What's said on calls is kept in the bot's state folder (`voice-transcript.jsonl`, the last 50 lines, each marked once
the bot has it). A call's last lines wait, for the record, in front of the bot's next message; if otto-bots stops
before then, they go to the bot when it starts again.

**Dictation.** The collector (the lens's panel, and the one under the artifact viewer) has a mic: what you say is
written into its note as you say it, a phrase at a time. The phone streams the audio to the voice server
(`/voice/transcribe`), which runs an OpenAI realtime transcription session (`gpt-4o-transcribe`) with the assistant's
key or Codex sign-in (the Codex sign-in when there's no assistant). If that can't start and `[voice]
deepgram_key_file` names a Deepgram key, Deepgram's live Nova-3 does it instead.

The voice server's port is `[voice] port` in `~/.config/otto/config.toml` (9336); the relay passes the app's
`/voice/…` requests to it. Notifications go through the relay's local API (`[relay] local_port`).
