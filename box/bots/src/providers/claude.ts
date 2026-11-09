// Claude Code as a bot's agent: one long-running `claude -p` with stream-json in and out, resumed by session id.
// Every message is written to its stdin as a user message: while a turn runs, Claude takes it into that turn; while
// it's idle, it starts one. The bot's tools come in through --mcp-config as the "bot" server, and are the only
// tools allowed beyond what the permission mode allows.
import { spawn, type ChildProcess } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import type { Agent, AgentEvents, AgentOptions, Provider } from '../provider.js';

const SERVER = 'bot';
const bin = () => process.env.CLAUDE_BIN || path.join(os.homedir(), '.local/bin/claude');

const toolName = (tool: string) => `mcp__${SERVER}__${tool}`;

export const claude: Provider = { label: 'Claude Code', toolName, create };

function create(o: AgentOptions, events: AgentEvents): Agent {
  let proc: ChildProcess | null = null;
  let session = o.session() ?? null;

  function start(): ChildProcess {
    const mcp = { mcpServers: { [SERVER]: { command: o.tools.command, args: o.tools.args, env: o.tools.env } } };
    const args = ['-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose',
      '--permission-mode', o.permissionMode, '--append-system-prompt', o.instructions, '--mcp-config', JSON.stringify(mcp),
      '--allowedTools', toolName('send_message'), toolName('send_file')];
    if (o.model) args.push('--model', o.model);
    session = o.session() ?? null;
    if (session) args.push('--resume', session);
    // Its own process, not a child session of whatever Claude started the service (as when testing from one).
    const env: Record<string, string | undefined> = { ...process.env, ...o.env };
    for (const k of Object.keys(env)) if (k === 'CLAUDECODE' || k.startsWith('CLAUDE_CODE_')) delete env[k];
    const p = spawn(bin(), args, { cwd: o.cwd, env, stdio: ['pipe', 'pipe', 'pipe'] });
    let err = '';
    let buf = '';
    p.stdout!.setEncoding('utf8');
    p.stdout!.on('data', (d: string) => {
      buf += d;
      let n;
      while ((n = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, n);
        buf = buf.slice(n + 1);
        let e: any;
        try { e = JSON.parse(line); } catch { continue; }
        if (proc !== p) return; // stopped: what's left is no longer this conversation's
        if (e.session_id && e.session_id !== session) { session = e.session_id; events.session(e.session_id); }
        if (e.type === 'assistant') events.activity();
        if (e.type === 'result') events.result({ error: !!e.is_error, text: String(e.result ?? '') });
      }
    });
    p.stderr!.on('data', d => { err = (err + d).slice(-3000); });
    p.stdin!.on('error', () => {}); // a write after it died: the close below reports it
    p.on('error', e => { err += `\n${e.message}`; });
    p.on('close', code => {
      if (proc !== p) return;
      proc = null;
      events.exit(code, err);
    });
    return p;
  }

  return {
    get running() { return proc !== null; },
    write(text: string) {
      proc ??= start();
      proc.stdin!.write(JSON.stringify({ type: 'user', message: { role: 'user', content: text } }) + '\n');
    },
    stop() {
      const p = proc;
      proc = null;
      p?.kill('SIGTERM');
    },
  };
}
