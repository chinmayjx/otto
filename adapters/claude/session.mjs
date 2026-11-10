#!/usr/bin/env node
// Prints this Claude Code session's id and title, for otto (OTTO_SESSION_COMMAND): the id on the first line,
// the title on the second.
//
// The id must last as long as the conversation does. CLAUDE_CODE_SESSION_ID doesn't, for a Remote Control
// chat: it gets its claude.ai id after the process starts, and the local id is new each time the process is.
// The Claude process's ~/.claude/sessions/<pid>.json has both: the chat's claude.ai id (bridgeSessionId), which
// lasts, and its session id now, whose transcript gives the title.
import fs from 'node:fs';
import path from 'node:path';
import { HOME } from '../lib.mjs';
import { title } from './sessions.mjs';

const SESSIONS = path.join(HOME, '.claude/sessions');

/** The nearest ancestor process with a Claude session file. */
function claudeSession() {
  let pid = process.ppid;
  for (let i = 0; i < 12 && pid > 1; i++) {
    try { return JSON.parse(fs.readFileSync(path.join(SESSIONS, `${pid}.json`), 'utf8')); } catch {}
    try { pid = +fs.readFileSync(`/proc/${pid}/stat`, 'utf8').split(') ')[1].split(' ')[1]; } catch { break; }
  }
  return null;
}

const s = claudeSession();
const local = s?.sessionId ?? process.env.CLAUDE_CODE_SESSION_ID;
const id = s?.bridgeSessionId ?? local;
if (!id) process.exit(1);
process.stdout.write(`${id}\n${local ? title(local) : 'Claude'}\n`);
