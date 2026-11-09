// The scheduler, inside otto-bots: every few seconds it reads the schedules (store.ts) and starts a run for each one
// that has come due, a fresh headless agent session with the schedule's prompt (agents.ts). A run reaches the user, if
// it wants to, by `otto send`; nothing goes into any bot's conversation. Guards: a schedule whose last run is still
// going skips (recorded as skipped); a run past its time is stopped; a time missed by more than a few minutes (the
// box was off) is recorded as missed, not run late; three failed runs in a row bring one phone notification.
import { expand } from '../config.js';
import { AGENTS, type Launcher, type Running } from './agents.js';
import * as store from './store.js';
import type { Run, Schedule } from './store.js';
import { describe, next } from './when.js';

/** How late a run may still start (otto-bots restarting at the time it was due). */
const GRACE_MS = 5 * 60_000;
const DEFAULT_TIMEOUT_MIN = 30;

export interface SchedulerDeps {
  /** The user's time zone, for every schedule. */
  timezone: string;
  log(m: string): void;
  /** A phone notification, for a schedule that keeps failing. */
  notify(text: string): Promise<void>;
  agents?: Record<string, Launcher>;
  now?: () => number;
  /** How often to look (ms). */
  every?: number;
}

export interface ScheduleView extends Schedule {
  name: string;
  /** In words: "weekdays at 08:00". */
  description: string;
  next: string | null;
  running: boolean;
  last: Run | null;
}

export class Scheduler {
  private going = new Map<string, { run: Run; handle: Running }>();
  private timer: NodeJS.Timeout | null = null;
  private now: () => number;

  constructor(private d: SchedulerDeps) {
    this.now = d.now ?? Date.now;
  }

  get timezone() { return this.d.timezone; }

  start() {
    this.tick();
    this.timer = setInterval(() => this.tick(), this.d.every ?? 15_000);
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    for (const g of this.going.values()) g.handle.stop();
  }

  /** Starts what has come due. */
  tick() {
    const now = this.now();
    for (const name of store.names()) {
      let s: Schedule | null;
      try { s = store.load(name); } catch { continue; }
      if (!s || s.paused) continue;
      let due: number | null;
      try { due = next(s, Math.max(s.since, store.fired(name) ?? 0), this.d.timezone); } catch (e: any) {
        this.log(name, `can't work out when: ${e.message}`);
        continue;
      }
      if (due === null || due > now) continue;
      // The latest time it came due (a schedule that missed several, the box being off, runs once at most).
      for (let t = next(s, due, this.d.timezone); t !== null && t <= now; t = next(s, t, this.d.timezone)) due = t;
      store.setFired(name, due);
      if (now - due > GRACE_MS) {
        const at = new Date(due).toISOString();
        this.log(name, `missed the run due at ${at}`);
        store.record({ id: runId(due), schedule: name, trigger: 'time', started: at, ended: at, status: 'missed', result: 'the box was off, or otto-bots wasn\'t running, at that time' });
        continue;
      }
      this.fire(name, s, 'time');
    }
  }

  /** Runs a schedule now (otto schedule run, the app's Run now). */
  runNow(name: string): Run {
    const s = store.load(name);
    if (!s) throw new Error(`no schedule "${name}"`);
    return this.fire(name, s, 'now');
  }

  private fire(name: string, s: Schedule, trigger: Run['trigger']): Run {
    const started = this.now();
    const run: Run = { id: runId(started), schedule: name, trigger, started: new Date(started).toISOString(), status: 'running' };
    if (this.going.has(name)) {
      this.log(name, 'skipped: the last run is still going');
      const skipped: Run = { ...run, status: 'skipped', ended: run.started, result: `run ${this.going.get(name)!.run.id} was still going` };
      store.record(skipped);
      return skipped;
    }
    const launch = (this.d.agents ?? AGENTS)[s.agent ?? 'claude'];
    if (!launch) {
      const failed: Run = { ...run, status: 'failed', ended: run.started, result: `unknown agent "${s.agent}" (known: ${Object.keys(this.d.agents ?? AGENTS).join(', ')})` };
      store.record(failed);
      return failed;
    }
    store.record(run);
    const minutes = s.timeout ?? DEFAULT_TIMEOUT_MIN;
    const handle = launch({
      prompt: `${this.preamble(name, run.id, started)}\n\n${s.prompt}`,
      cwd: expand(s.cwd || '~/code'),
      model: s.model,
      log: store.logOf(name, run.id),
      timeoutMs: minutes * 60_000,
      env: {
        OTTO_SESSION: `schedule-${name}`, OTTO_SESSION_TITLE: `schedule ${name}`,
        OTTO_SCHEDULE: name, OTTO_SCHEDULE_RUN: run.id, OTTO_SENDER: `schedule ${name} (run ${run.id})`,
      },
    });
    this.going.set(name, { run, handle });
    this.log(name, `run ${run.id} started (${trigger}, ${s.agent ?? 'claude'})`);
    handle.done.then(o => {
      this.going.delete(name);
      const done: Run = { ...run, ended: new Date(this.now()).toISOString(), status: o.status, session: o.session, transcript: o.transcript,
        result: o.result?.slice(0, 2000) };
      store.record(done);
      this.log(name, `run ${run.id} ${o.status} after ${Math.round((this.now() - started) / 1000)} s`);
      if (o.status !== 'ok') this.warn(name);
    });
    return run;
  }

  /** What the run is told before the schedule's prompt. */
  private preamble(name: string, id: string, at: number): string {
    const when = new Intl.DateTimeFormat('en-GB', { timeZone: this.d.timezone, dateStyle: 'full', timeStyle: 'short' }).format(at);
    return `[Scheduled run of "${name}" (run ${id}), ${when} (${this.d.timezone}). Nobody is watching this session. `
      + 'To reach the user, use `otto send` (otto send telegram|otto_voice|notify "…", see `otto help`), on whichever '
      + 'channel the task calls for. If nothing is worth telling them, say nothing and end.]';
  }

  /** One notification when a schedule has just failed three times in a row. */
  private warn(name: string) {
    const done = store.runs(name).filter(r => r.status !== 'running' && r.status !== 'skipped' && r.status !== 'missed');
    const bad = (r?: Run) => !!r && (r.status === 'failed' || r.status === 'timeout');
    const n = done.length;
    if (!(bad(done[n - 1]) && bad(done[n - 2]) && bad(done[n - 3]) && !bad(done[n - 4]))) return;
    const last = (done[n - 1].result ?? '').split('\n')[0].slice(0, 200);
    this.d.notify(`Schedule "${name}" failed 3 times in a row${last ? `: ${last}` : ''}. See otto schedule runs ${name}.`)
      .catch(e => this.log(name, `couldn't notify: ${e.message}`));
  }

  view(name: string): ScheduleView | null {
    const s = store.load(name);
    if (!s) return null;
    let nextAt: number | null = null, description = '';
    try {
      description = describe(s);
      nextAt = s.paused ? null : next(s, Math.max(s.since, store.fired(name) ?? 0, this.now() - GRACE_MS), this.d.timezone);
    } catch (e: any) { description = `invalid: ${e.message}`; }
    const runs = store.runs(name);
    return { name, ...s, description, next: nextAt === null ? null : new Date(nextAt).toISOString(), running: this.going.has(name), last: runs[runs.length - 1] ?? null };
  }

  views(): ScheduleView[] {
    return store.names().map(n => this.view(n)).filter((v): v is ScheduleView => v !== null);
  }

  private log(name: string, m: string) { this.d.log(`schedule ${name}: ${m}`); }
}

/** A run's id: when it started, short, and two random letters (a skipped run starts at the same moment). */
const runId = (t: number) => `r${t.toString(36)}${Math.random().toString(36).slice(2, 4)}`;
