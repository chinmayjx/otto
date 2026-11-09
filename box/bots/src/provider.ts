// An agent provider: the coding agent a bot is a conversation with (Claude Code so far; Codex would be another
// provider behind the same interface). A provider keeps one long-running agent process for the conversation, takes
// the user's messages, and reports what the core needs to track a turn. It knows nothing of channels or owners.

export interface AgentOptions {
  /** The folder the agent works in. */
  cwd: string;
  /** The conversation to resume when the process starts, read at each start (the core clears it for /new). */
  session: () => string | null | undefined;
  permissionMode: string;
  model?: string;
  /** Added to the agent's own system prompt. */
  instructions: string;
  /** The MCP server with the bot's tools (send_message, send_file), as a stdio command. */
  tools: { command: string; args: string[]; env: Record<string, string> };
  /** More environment for the agent process. */
  env: Record<string, string>;
}

export interface AgentEvents {
  /** The conversation's id, whenever it's new or changes. */
  session(id: string): void;
  /** The agent is producing output: a turn is under way (possibly one it started on its own). */
  activity(): void;
  /** A turn ended. `text` is the agent's final text (not shown to the user), or the error. */
  result(r: { error: boolean; text: string }): void;
  /** The process ended without being stopped; `stderr` is its last output. */
  exit(code: number | null, stderr: string): void;
}

export interface Agent {
  /** Whether the process is running. */
  readonly running: boolean;
  /** A message from the user (or the bot): starts the process if needed. While a turn runs, it steers that turn. */
  write(text: string): void;
  /** Ends the process; the next write starts a new one, resuming the session. */
  stop(): void;
}

export interface Provider {
  /** The agent's name for people, e.g. "Claude Code". */
  readonly label: string;
  /** How the agent names one of the bot's tools (for its instructions). */
  toolName(tool: string): string;
  create(options: AgentOptions, events: AgentEvents): Agent;
}
