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
}

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
  /** Text in the agent's Markdown; long text goes in parts. */
  send(chat: Id, text: string): Promise<void>;
  /** A file from the box, with an optional caption in Markdown. */
  sendFile(chat: Id, file: string, caption?: string): Promise<void>;
  /** Shows that the bot is working, for a few seconds. */
  typing(chat: Id): Promise<void>;
  setCommands(commands: Command[]): Promise<void>;
}
