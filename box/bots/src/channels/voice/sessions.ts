// The voice's way to the user's chats (their Claude Code sessions on the box): list them, and message one directly. Both
// run the CLI (`otto sessions list|send`, cli/src/sessions.ts), the one implementation, from this same checkout.
import { execFile } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const CLI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../../../cli/dist/main.js');

export const SESSION_TOOLS = [
  {
    type: 'function', name: 'list_chats',
    description: "The user's chats (their Claude Code sessions on their computer), most recently active first: id, name "
      + 'and title, folder, last active, busy/idle/not running, remote. Use it to find the chat the user names.',
    parameters: {
      type: 'object',
      properties: { all: { type: 'boolean', description: 'Include ones older than 3 days (default: running and recent only)' } },
    },
  },
  {
    type: 'function', name: 'message_chat',
    description: "Pass the user's message on to the chat they named. A running chat gets it at once; one that isn't "
      + "running is continued in the background. It's told the message is from the user, and replies to them through "
      + 'Otto on its own. Returns once delivered.',
    parameters: {
      type: 'object',
      properties: {
        chat: { type: 'string', description: "The chat's id from list_chats (or its exact name)" },
        text: { type: 'string', description: "The user's own words, as they said them, in the first person (\"I\", "
          + "\"my\"): no greeting, no \"the user wants…\", no summary. Leave out only the part that names the chat." },
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
  if (name === 'list_chats') return otto(['sessions', 'list', '--limit', '20', ...(args?.all ? ['--all'] : [])]);
  const chat = String(args?.chat ?? '').trim(), text = String(args?.text ?? '').trim();
  if (!chat || !text) return Promise.resolve('Failed: give the chat and the text');
  // A stopped session is continued in the background first (claude --bg --resume), which takes a few seconds.
  return otto(['sessions', 'send', chat], text, 60_000);
}

export const isSessionTool = (name: string) => SESSION_TOOLS.some(t => t.name === name);
