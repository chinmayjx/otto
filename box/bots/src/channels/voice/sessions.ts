// The voice's way to the user's other agent sessions on the box: list them, and say something to one directly. Both
// run the CLI (`otto sessions list|send`, cli/src/sessions.ts), the one implementation, from this same checkout.
import { execFile } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const CLI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../../../cli/dist/main.js');

export const SESSION_TOOLS = [
  {
    type: 'function', name: 'list_sessions',
    description: "The user's agent chats on their computer (Claude Code and Codex), most recently active first: id, agent, "
      + 'live/idle/busy, last active, title, folder. Use it to find the chat the user means.',
    parameters: {
      type: 'object',
      properties: {
        search: { type: 'string', description: 'Only chats whose title or folder contains this (optional)' },
        limit: { type: 'number', description: 'At most this many (default 15)' },
      },
    },
  },
  {
    type: 'function', name: 'send_to_session',
    description: "Put a message into one of the user's chats as if the user typed it there (a stopped chat is started "
      + "first, which can take a while). The chat is told it's from the user via Otto and replies on this call or as a "
      + 'notification. Returns once delivered.',
    parameters: {
      type: 'object',
      properties: {
        session: { type: 'string', description: "The chat's id from list_sessions (or its exact title)" },
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
  if (name === 'list_sessions') {
    const limit = Math.min(Math.max(Number(args?.limit) || 15, 1), 50);
    const search = typeof args?.search === 'string' && args.search.trim() ? ['--search', args.search.trim()] : [];
    return otto(['sessions', 'list', '--limit', String(limit), ...search]);
  }
  const session = String(args?.session ?? '').trim(), text = String(args?.text ?? '').trim();
  if (!session || !text) return Promise.resolve('Failed: give the session and the text');
  // A stopped chat has to start first (devbox-hub waits for it, up to about a minute).
  return otto(['sessions', 'send', session], text, 120_000);
}

export const isSessionTool = (name: string) => SESSION_TOOLS.some(t => t.name === name);
