// How a scheduled run starts its agent: a fresh headless session with the prompt, its output saved to the run's log,
// stopped when its time is up. Claude Code runs `claude -p` (stream-json); Codex runs `codex exec --json`. Neither
// resumes anything: each run is a session of its own, which the run's record names so it can be read or resumed.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export interface Launch {
  prompt: string;
  cwd: string;
  model?: string;
  /** Where the agent's output goes, line by line. */
  log: string;
  env: Record<string, string>;
  timeoutMs: number;
}

export interface Outcome {
  status: 'ok' | 'failed' | 'timeout';
  session?: string;
  transcript?: string;
  /** The agent's last words, or what went wrong. */
  result?: string;
}

export interface Running {
  done: Promise<Outcome>;
  stop(): void;
}

export type Launcher = (l: Launch) => Running;

const home = os.homedir();
const claudeBin = () => process.env.CLAUDE_BIN || path.join(home, '.local/bin/claude');
const codexBin = () => process.env.CODEX_BIN || 'codex';

/** Where Claude Code keeps a session's transcript: its folder, with every other character a "-". */
export const claudeTranscript = (cwd: string, session: string) =>
  path.join(home, '.claude', 'projects', cwd.replace(/[^a-zA-Z0-9]/g, '-'), `${session}.jsonl`);

/** Codex's rollout file for a thread, under ~/.codex/sessions/<y>/<m>/<d>/. */
function codexTranscript(thread: string): string | undefined {
  const root = path.join(home, '.codex', 'sessions');
  const today = new Date();
  for (let i = 0; i < 2; i++) { // the run may cross midnight
    const d = new Date(today.getTime() - i * 86400_000);
    const dir = path.join(root, String(d.getFullYear()), String(d.getMonth() + 1).padStart(2, '0'), String(d.getDate()).padStart(2, '0'));
    try {
      const f = fs.readdirSync(dir).find(n => n.includes(thread));
      if (f) return path.join(dir, f);
    } catch {}
  }
  return undefined;
}

/** Starts a process, sends `input` to it, keeps its output in the log and hands each line to `line`. */
function run(bin: string, args: string[], l: Launch, input: string, line: (e: any) => void, finish: (code: number | null, err: string, timedOut: boolean) => Outcome): Running {
  const env: Record<string, string | undefined> = { ...process.env, ...l.env };
  // Its own process, not a child session of whatever Claude started the service.
  for (const k of Object.keys(env)) if (k === 'CLAUDECODE' || k.startsWith('CLAUDE_CODE_')) delete env[k];
  const out = fs.openSync(l.log, 'a', 0o600);
  const p = spawn(bin, args, { cwd: l.cwd, env, stdio: ['pipe', 'pipe', 'pipe'] });
  let err = '', buf = '', timedOut = false;
  p.stdout!.setEncoding('utf8');
  p.stdout!.on('data', (d: string) => {
    fs.writeSync(out, d);
    buf += d;
    let n;
    while ((n = buf.indexOf('\n')) >= 0) {
      const s = buf.slice(0, n);
      buf = buf.slice(n + 1);
      try { line(JSON.parse(s)); } catch {}
    }
  });
  p.stderr!.on('data', d => { err = (err + d).slice(-3000); });
  p.stdin!.on('error', () => {});
  p.stdin!.end(input);
  const kill = () => {
    p.kill('SIGTERM');
    setTimeout(() => { if (p.exitCode === null && p.signalCode === null) p.kill('SIGKILL'); }, 10_000).unref();
  };
  const timer = setTimeout(() => { timedOut = true; kill(); }, l.timeoutMs);
  const done = new Promise<Outcome>(resolve => {
    p.on('error', e => { err += `\n${e.message}`; });
    p.on('close', code => {
      clearTimeout(timer);
      fs.closeSync(out);
      resolve(finish(code, err.trim(), timedOut));
    });
  });
  return { done, stop: kill };
}

export const claudeRun: Launcher = l => {
  let session: string | undefined, result: string | undefined, error = false, ended = false;
  const args = ['-p', '--output-format', 'stream-json', '--verbose', '--permission-mode', 'auto'];
  if (l.model) args.push('--model', l.model);
  return run(claudeBin(), args, l, l.prompt, e => {
    if (e.session_id) session = e.session_id;
    if (e.type === 'result') { ended = true; error = !!e.is_error; result = String(e.result ?? ''); }
  }, (code, err, timedOut) => ({
    status: timedOut ? 'timeout' : ended && !error && code === 0 ? 'ok' : 'failed',
    session,
    transcript: session ? claudeTranscript(l.cwd, session) : undefined,
    result: timedOut ? `stopped after ${Math.round(l.timeoutMs / 60_000)} min` : (result || (ended ? '' : err.slice(-1500) || `exit ${code}`)),
  }));
};

export const codexRun: Launcher = l => {
  let thread: string | undefined, last: string | undefined, failure: string | undefined, ended = false;
  // Non-interactive, so nothing is asked; full access so it can use the network (otto send) and the box like Claude does.
  const args = ['exec', '--json', '--skip-git-repo-check', '-s', 'danger-full-access', '-C', l.cwd];
  if (l.model) args.push('-m', l.model);
  args.push('-');
  return run(codexBin(), args, l, l.prompt, e => {
    if (e.type === 'thread.started') thread = e.thread_id;
    if (e.type === 'item.completed' && e.item?.type === 'agent_message') last = String(e.item.text ?? '');
    if (e.type === 'turn.completed') ended = true;
    if (e.type === 'turn.failed' || e.type === 'error') failure = String(e.error?.message ?? e.message ?? 'failed');
  }, (code, err, timedOut) => ({
    status: timedOut ? 'timeout' : ended && !failure && code === 0 ? 'ok' : 'failed',
    session: thread,
    transcript: thread ? codexTranscript(thread) : undefined,
    result: timedOut ? `stopped after ${Math.round(l.timeoutMs / 60_000)} min` : (failure ?? last ?? (err.slice(-1500) || `exit ${code}`)),
  }));
};

export const AGENTS: Record<string, Launcher> = { claude: claudeRun, codex: codexRun };
