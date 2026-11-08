#!/usr/bin/env node
// Prints this Codex thread's id and title, for otto (OTTO_SESSION_COMMAND): the id on the first line, the title
// on the second. Codex gives every command its thread id (CODEX_THREAD_ID); the title is the first line of the
// thread's first prompt, from its rollout file.
import fs from 'node:fs';
import path from 'node:path';
import { HOME, titleOf } from '../lib.mjs';

const id = process.env.CODEX_THREAD_ID;
if (!id) process.exit(1);

function rollout(dir) {
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return null; }
  for (const e of entries.sort((a, b) => b.name.localeCompare(a.name))) { // newest first
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { const f = rollout(p); if (f) return f; }
    else if (e.name.endsWith(`-${id}.jsonl`)) return p;
  }
  return null;
}

function title() {
  const file = rollout(path.join(process.env.CODEX_HOME ?? path.join(HOME, '.codex'), 'sessions'));
  if (!file) return 'Codex';
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    let e;
    try { e = JSON.parse(line); } catch { continue; }
    const p = e.payload;
    if (e.type !== 'response_item' || p?.type !== 'message' || p.role !== 'user') continue;
    const text = (p.content ?? []).map(c => c.text ?? '').join('\n');
    if (/^\s*(<|# AGENTS\.md)/.test(text)) continue; // environment context and instructions
    const t = titleOf(text);
    if (t) return t;
  }
  return 'Codex';
}

process.stdout.write(`${id}\n${title()}\n`);
