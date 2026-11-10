// The voice's way to the user's Claude Code sessions on the box: list them, and say something to one directly. Both
// run the CLI (`otto sessions list|send`, cli/src/sessions.ts), the one implementation, from this same checkout.
import { execFile } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const CLI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../../../cli/dist/main.js');

export const SESSION_TOOLS = [
  {
    type: 'function', name: 'list_sessions',
    description: "The user's Claude Code sessions on their computer, most recently active first: id, name and title, "
      + 'folder, last active, busy/idle/not running, remote. Use it to find the session the user means.',
    parameters: {
      type: 'object',
      properties: { all: { type: 'boolean', description: 'Include ones older than 3 days (default: running and recent only)' } },
    },
  },
  {
    type: 'function', name: 'send_to_session',
    description: "Pass a message on to one of the user's Claude Code sessions. A running one gets it at once; one that "
      + "isn't running is continued in the background. It's told who it's from and replies to the user through Otto on "
      + 'its own. Returns once delivered.',
    parameters: {
      type: 'object',
      properties: {
        session: { type: 'string', description: "The session's id from list_sessions (or its exact name)" },
        text: { type: 'string', description: 'The message, in the words the user wants passed on' },
      },
      required: ['session', 'text'],
    },
  },
];

/** Runs `otto <args>` with `input` on stdin; its output, or why it failed. */
function otto(args: string[], input = '', timeoutMs = 20_000): Promise<string> {
  return new Promise(resolve => {
    const p = execFile(process.execPath, [CLI, ...args], {
      timeout: timeoutMs, maxBuffer: 1 << 20,
      env: { ...process.env, OTTO_SENDER: 'the user (on their Otto voice call)' },
    }, (err, stdout, stderr) => {
      const out = String(stdout).trim(), why = String(stderr).replace(/^otto: /, '').trim();
      resolve(err ? `Failed: ${why || err.message}` : out || 'done');
    });
    p.stdin?.on('error', () => {});
    p.stdin?.end(input);
  });
}

/** One of the voice's session tools, run: what to tell the voice. */
export function sessionTool(name: string, args: any): Promise<string> {
  if (name === 'list_sessions') return otto(['sessions', 'list', '--limit', '20', ...(args?.all ? ['--all'] : [])]);
  const session = String(args?.session ?? '').trim(), text = String(args?.text ?? '').trim();
  if (!session || !text) return Promise.resolve('Failed: give the session and the text');
  // A stopped session is continued in the background first (claude --bg --resume), which takes a few seconds.
  return otto(['sessions', 'send', session], text, 60_000);
}

export const isSessionTool = (name: string) => SESSION_TOOLS.some(t => t.name === name);
