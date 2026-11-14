// A chat channel: where a bot meets its user (Telegram, Otto's voice). A channel only moves messages: it receives
// them, sends text and files, shows that the bot is working, and lists the bot's commands. Who may talk to the bot,
// and what happens to a message, is the core's business (core.ts). A bot can have several channels, all of them one
// conversation: each message reaches the agent tagged with its channel's kind, and the agent names the channel it
// replies on.

/** A chat or account id, as the channel gives it. */
export type Id = string | number;

export interface Attachment {
  kind: 'photo' | 'file';
  /** The file's own name, when the channel has one. */
  name?: string;
  /** Saves it into the folder and returns its path. Called only for the owner's messages. */
  download(dir: string): Promise<string>;
}

export interface Incoming {
  /** Where replies go. */
  chat: Id;
  /** Who sent it. */
  from: Id;
  /** The text, or a photo's or file's caption. */
  text: string;
  attachments: Attachment[];
  /** For the record only: no turn of its own; it goes in front of the agent's next message, from any channel. */
  record?: boolean;
  /** Only for a turn in progress: what's happening on the channel meanwhile (a call's latest lines), written into the
   * turn as context that needs no reply of its own. With the agent idle it's dropped, undelivered, and the channel
   * keeps it for its next message: a turn per aside would cost more than it's worth. */
  steer?: boolean;
  /** Runs once the message has been written to the agent (or dropped by /new). */
  delivered?: () => void;
}

/** What a channel throws for a reply it didn't send because the conversation there has moved on since the agent
 * wrote it (a voice call it hasn't heard all of). The message tells the agent what's new; the reply rule counts the
 * channel as answered, since the agent may rightly send nothing more. */
export class Stale extends Error {}

export interface Command { command: string; description: string }

export interface Channel {
  /** The channel's kind, as the config and the agent name it: "telegram", "otto_voice". */
  readonly kind: string;
  /** The channel's name for people, e.g. "Telegram". */
  readonly label: string;
  /** How the agent's text shows (or sounds) on this channel, for the agent's instructions. */
  readonly formatting: string;
  /** Whether anyone who finds the channel can write to it, so the bot pairs with one owner (Telegram). A channel
   * only its user can reach (the paired phone's voice) has no owner, and no commands. */
  readonly owned: boolean;
  /** Checks the token and starts receiving; resolves once it's receiving. */
  start(onMessage: (m: Incoming) => void): Promise<void>;
  stop(): void;
  /** The bot's own name on the channel (e.g. its @username), once started. */
  readonly handle: string | null;
  /** A link that pairs whoever opens it, when the channel has such links (Telegram's t.me/<bot>?start=<code>). */
  pairLink(code: string): string | null;
  /** Text in the agent's Markdown; long text goes in parts. `reply` when it's the agent's own reply in the
   * conversation (send_message), rather than a notice from the bot or a message from elsewhere on the box. */
  send(chat: Id, text: string, o?: { reply?: boolean }): Promise<void>;
  /** A file from the box, with an optional caption in Markdown. */
  sendFile(chat: Id, file: string, caption?: string): Promise<void>;
  /** Shows that the bot is working, for a few seconds. */
  typing(chat: Id): Promise<void>;
  setCommands(commands: Command[]): Promise<void>;
}
