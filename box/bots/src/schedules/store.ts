// The schedules on disk. Each one is a file, ~/.config/otto/schedules/<name>.json (0600), next to the bots' folder;
// what its runs did is kept in ~/.local/state/otto/schedules/<name>/: runs.jsonl (a line when a run starts and one
// when it ends; the last line for a run is what it is), each run's output as <run>.log, and fired.json, the time it
// last came due.
import fs from 'node:fs';
import path from 'node:path';
import { NAME, ottoState, schedulesDir, writePrivate } from '../config.js';
import type { When } from './when.js';

export interface Schedule extends When {
  /** What the run is told to do. It sees nothing else: it has to stand on its own. */
  prompt: string;
  /** claude (default) or codex. */
  agent?: string;
  /** The folder the run works in (default ~/code). */
  cwd?: string;
  model?: string;
  /** Minutes a run may take (default 30). */
  timeout?: number;
  paused?: boolean;
  /** From when it counts: when it was made, or its timing last changed (ms). */
  since: number;
}

export type RunStatus = 'running' | 'ok' | 'failed' | 'timeout' | 'skipped' | 'missed';

export interface Run {
  id: string;
  schedule: string;
  /** "time" (it came due), or "now" (otto schedule run, the app's Run now). */
  trigger: 'time' | 'now';
  started: string;
  ended?: string;
  status: RunStatus;
  /** The agent's session id, and its transcript on disk when there is one. */
  session?: string;
  transcript?: string;
  /** The agent's last words, or why it failed or didn't run. */
  result?: string;
}

export const stateOf = (name: string) => path.join(ottoState(), 'schedules', name);
const fileOf = (name: string) => path.join(schedulesDir(), `${name}.json`);

export function checkName(name: string) {
  if (!NAME.test(name)) throw new Error(`a schedule's name is letters, digits, - and _ (got "${name}")`);
}

export function names(): string[] {
  try {
    return fs.readdirSync(schedulesDir()).filter(f => f.endsWith('.json')).map(f => f.slice(0, -5)).filter(n => NAME.test(n)).sort();
  } catch { return []; }
}

export function load(name: string): Schedule | null {
  checkName(name);
  try { return JSON.parse(fs.readFileSync(fileOf(name), 'utf8')); } catch { return null; }
}

export function save(name: string, s: Schedule) {
  checkName(name);
  fs.mkdirSync(schedulesDir(), { recursive: true, mode: 0o700 });
  writePrivate(fileOf(name), JSON.stringify(s, null, 2) + '\n');
}

/** Deletes the schedule; its runs stay, for looking up. */
export function remove(name: string) {
  checkName(name);
  fs.rmSync(fileOf(name), { force: true });
}

/** When the schedule last came due (ms), if it ever has. */
export function fired(name: string): number | null {
  try { return JSON.parse(fs.readFileSync(path.join(stateOf(name), 'fired.json'), 'utf8')).at ?? null; } catch { return null; }
}

export function setFired(name: string, at: number) {
  fs.mkdirSync(stateOf(name), { recursive: true, mode: 0o700 });
  writePrivate(path.join(stateOf(name), 'fired.json'), JSON.stringify({ at }) + '\n');
}

const MAX_RUNS_FILE = 512 * 1024;

/** Records a run as it now is. */
export function record(r: Run) {
  const dir = stateOf(r.schedule);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const file = path.join(dir, 'runs.jsonl');
  // A long history keeps its last runs only.
  if ((fs.statSync(file, { throwIfNoEntry: false })?.size ?? 0) > MAX_RUNS_FILE) {
    writePrivate(file, runs(r.schedule).slice(-200).map(x => JSON.stringify(x)).join('\n') + '\n');
  }
  fs.appendFileSync(file, JSON.stringify(r) + '\n', { mode: 0o600 });
}

/** A schedule's runs, oldest first, each as last recorded. */
export function runs(name: string): Run[] {
  let text = '';
  try { text = fs.readFileSync(path.join(stateOf(name), 'runs.jsonl'), 'utf8'); } catch { return []; }
  const byId = new Map<string, Run>();
  for (const line of text.split('\n')) {
    if (!line) continue;
    try { const r = JSON.parse(line) as Run; byId.delete(r.id); byId.set(r.id, r); } catch {}
  }
  return [...byId.values()].sort((a, b) => a.started.localeCompare(b.started));
}

/** Every schedule that has runs, including deleted ones. */
export function withRuns(): string[] {
  try { return fs.readdirSync(path.join(ottoState(), 'schedules')).filter(n => NAME.test(n)).sort(); } catch { return []; }
}

export const logOf = (name: string, run: string) => path.join(stateOf(name), `${run}.log`);
