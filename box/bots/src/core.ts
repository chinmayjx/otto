// A bot: one agent conversation, reached through one or more channels (Telegram, Otto's voice). The core decides who
// may talk to the bot (on a channel anyone can find, pairing, then the owner only), handles its commands, and passes
// every other message to the agent, tagged with its channel: while a turn runs it steers that turn, while the agent
// is idle it starts one. The agent answers only through the bot's tools (tools.ts), naming the channel, which reach
// the core over a unix socket only this user can open. A turn that got a message on a channel and sent nothing back
// there is reminded once, then the user gets a notice on it. Channel- and agent-neutral: those are behind channel.ts
// and provider.ts.
import { randomBytes } from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import type { Channel, Id, Incoming } from './channel.js';
import type { BotConfig } from './config.js';
import { writePrivate } from './config.js';
import type { Agent, Provider } from './provider.js';

export interface BotOptions {
  name: string;
  config: BotConfig;
  /** The bot's channels, one of each kind. */
  channels: Channel[];
  provider: Provider;
  /** Where the bot keeps its socket, the files the user sends, and its status for the CLI. */
  stateDir: string;
  /** How to run the tools' MCP server (tools.ts). */
  tools: { command: string; args: string[] };
  /** Changes fields of the config file and returns the config as it now is. */
  save: (patch: Partial<BotConfig>) => BotConfig;
  log: (m: string) => void;
  /** How often the typing indicator is renewed. */
  typingEvery?: number;
  /** Notes each message on a channel in that channel's own log (channel-log.ts). */
  channelLog?: (kind: string, e: { dir: 'in' | 'out'; by: string; text?: string; file?: string }) => void;
}

const COMMANDS = [
  { command: 'new', description: 'Start a new conversation' },
  { command: 'stop', description: 'Stop the agent (the next message resumes)' },
  { command: 'status', description: 'Session and state' },
];

const reminder = (kinds: string[]) => `[From the bot, not the user: you ended your turn without replying on ${kinds.join(' and ')}, `
  + 'and the user sees only what you send with send_message. Reply to them there now with send_message.]';

/** One channel of the bot, and where its replies go. */
interface Link {
  channel: Channel;
  /** The chat the owner last wrote from (a private chat's id is the owner's own, so that's the default). */
  chat: Id | null;
  pairCode: string | null;
}

const wrap = (kind: string, text: string) => `<message channel="${kind}">\n${text}\n</message>`;

function instructions(links: Link[], tool: (t: string) => string): string {
  const kinds = links.map(l => l.channel.kind);
  return [
    `You are the user's assistant. They reach you from their phone, through ${links.length > 1 ? 'these channels' : 'this channel'}, all one conversation with you:`,
    ...links.map(l => `- ${l.channel.kind} (${l.channel.label}): ${l.channel.formatting}`),
    `Each message from the user comes wrapped as <message channel="${kinds[0]}">…</message>, naming its channel.`,
    `The user sees only what you send with the send_message and send_file tools (${tool('send_message')},`,
    `${tool('send_file')}), on the channel you name; your plain text is not shown. Every message from the user needs at`,
    'least one send_message back on the channel it came from. For work that takes more than a moment, send a short',
    'heads-up first, then the result. New messages can arrive while you work, from any channel: take them into account',
    'as you go. Attach files you make with send_file. Keep messages short and easy to read on a phone: plain',
    'sentences, short lists.',
    'For anything long or visual (a report, a table, a plan, a page), publish an artifact with `otto artifact publish`',
    'and send a short summary and its link.',
    'To do something later or on a schedule, make a schedule: `otto schedule add <name> --at HH:MM [--every ...] | --cron',
    '... | --once "YYYY-MM-DD HH:MM" --prompt "..."` (see `otto help`; times are the user\'s). Each run is a fresh session',
    'that sees only its prompt, none of this conversation: write the prompt so it stands on its own, with everything',
    'it needs, including whether and where to tell the user (`otto send telegram|otto_voice|notify "..."`). Runs and',
    'what they sent aren\'t in this conversation: when the user mentions one, or replies to a message you don\'t',
    'remember sending, look with `otto schedule runs` and `otto channel log <channel>`.',
  ].join('\n');
}

export class Bot {
  readonly name: string;
  config: BotConfig;
  readonly cwd: string;
  private links = new Map<string, Link>();
  private agent: Agent;
  private dir: string;
  private socket: string;
  private server: http.Server | null = null;
  private log: (m: string) => void;
  private opts: BotOptions;
  private agentLabel: string;

  private busy = false; // a turn is in progress
  private asked = new Set<string>(); // the channels the turn in progress got messages from
  private answered = new Set<string>(); // the channels it has sent something on
  private reminded = false; // the turn in progress was reminded to reply
  private last: string; // the channel of the latest message
  private records: { text: string; delivered?: () => void }[] = []; // for the record, in front of the next message
  private typing: NodeJS.Timeout | null = null;

  constructor(o: BotOptions) {
    this.opts = o;
    this.name = o.name;
    this.config = o.config;
    for (const channel of o.channels) {
      const owner = o.config.channels[channel.kind]?.owner;
      this.links.set(channel.kind, { channel, chat: channel.owned ? owner ?? null : 'user', pairCode: null });
    }
    this.last = o.channels[0].kind;
    this.dir = o.stateDir;
    this.socket = path.join(o.stateDir, 'tools.sock');
    this.log = o.log;
    this.cwd = o.config.cwd || path.join(os.homedir(), 'code');
    this.agentLabel = o.provider.label;
    const links = [...this.links.values()];
    this.agent = o.provider.create({
      cwd: this.cwd,
      session: () => this.config.session,
      permissionMode: o.config.permissionMode || 'auto',
      model: o.config.model,
      instructions: instructions(links, t => o.provider.toolName(t)),
      tools: { ...o.tools, env: {
        OTTO_BOT_SOCKET: this.socket,
        OTTO_BOT_CHANNELS: JSON.stringify(links.map(l => ({ kind: l.channel.kind, label: l.channel.label }))),
      } },
      // The agent's `otto` commands (the phone's browser, …) get a session of their own.
      env: { OTTO_SESSION: `bot-${o.name}`, OTTO_SESSION_TITLE: `${o.name} bot` },
    }, {
      session: id => { this.config = this.opts.save({ session: id }); },
      activity: () => { if (!this.busy) this.begin(); }, // a turn the agent started on its own (a message it took late)
      result: r => this.ended(r),
      exit: (code, err) => {
        const was = this.busy;
        const kinds = [...this.asked];
        this.finish();
        this.log(`${this.agentLabel} exited (${code})${was ? ' mid-turn' : ''}`);
        if (was && code !== null && code !== 143) this.say(`⚠️ ${this.agentLabel} stopped (exit ${code}).\n${err.slice(-1500)}`, kinds);
      },
    });
  }

  /** The channels' kinds. */
  get kinds(): string[] { return [...this.links.keys()]; }

  async start(): Promise<void> {
    fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 });
    await this.listen();
    for (const [kind, l] of this.links) {
      await l.channel.start(m => this.onMessage(kind, m).catch(e => this.log(`message: ${e.stack ?? e}`)));
      if (!l.channel.owned) continue;
      await l.channel.setCommands(COMMANDS).catch(e => this.log(`commands: ${e.message}`));
      if (!this.config.channels[kind]?.owner) {
        l.pairCode = randomBytes(4).toString('hex');
        this.log(`${kind} unowned: send "/start ${l.pairCode}" to ${l.channel.handle ?? 'the bot'} to claim it`);
      }
    }
    this.writeStatus();
    const on = [...this.links.values()].map(l => `${l.channel.label}${l.channel.handle ? ` ${l.channel.handle}` : ''}`).join(', ');
    this.log(`${on}: up; folder ${this.cwd}; session ${this.config.session || '(new)'}`);
  }

  stop(): void {
    for (const l of this.links.values()) l.channel.stop();
    this.agent.stop();
    this.finish();
    this.server?.close();
    this.server?.closeAllConnections();
    this.server = null;
    fs.rmSync(this.socket, { force: true });
    fs.rmSync(path.join(this.dir, 'status.json'), { force: true });
  }

  /** What the CLI shows: each channel's name for the bot, and while one is unowned, its pairing code and link. */
  private writeStatus() {
    const channels = [...this.links].map(([kind, l]) => ({
      kind, handle: l.channel.handle, owned: !l.channel.owned || !!this.config.channels[kind]?.owner,
      pair: l.pairCode ? { code: l.pairCode, link: l.channel.pairLink(l.pairCode) } : null,
    }));
    const first = channels.find(c => c.pair) ?? channels[0];
    writePrivate(path.join(this.dir, 'status.json'), JSON.stringify({
      handle: first.handle, pid: process.pid, owned: channels.every(c => c.owned), pair: first.pair, channels,
    }, null, 2));
  }

  // --- Messages ----------------------------------------------------------------------------------------------------

  async onMessage(kind: string, m: Incoming): Promise<void> {
    const l = this.links.get(kind);
    if (!l) return;
    const ch = l.channel;
    const text = m.text.replace(/^(\/\w+)@\w+/, '$1'); // "/new@some_bot", as group chats send commands
    if (ch.owned) {
      const owner = this.config.channels[kind]?.owner;
      if (!owner) {
        if (l.pairCode && text === `/start ${l.pairCode}`) {
          this.config = this.opts.save({ channels: { [kind]: { owner: m.from } } });
          l.pairCode = null;
          this.writeStatus();
          this.log(`${kind} paired with ${m.from}`);
          await ch.send(m.chat, `Paired. This bot is a ${this.agentLabel} conversation on your box: just write.`);
        }
        return;
      }
      if (String(m.from) !== String(owner)) return; // anyone else: silence

      if (text === '/new' || text === '/stop') {
        const was = this.agent.running;
        this.agent.stop();
        this.finish();
        if (text === '/new') {
          this.config = this.opts.save({ session: null });
          for (const r of this.records.splice(0)) r.delivered?.(); // dropped with the conversation, not kept for later
          return ch.send(m.chat, '🆕 New conversation.');
        }
        return ch.send(m.chat, was ? '⏹ Stopped.' : 'Nothing running.');
      }
      if (text === '/status') {
        const state = this.busy ? 'Working' : this.agent.running ? 'Idle' : 'Not running';
        return ch.send(m.chat, `Session: ${this.config.session || '(new)'}\nFolder: ${this.cwd}\n${state}`);
      }
    }

    let prompt = m.text;
    for (const a of m.attachments) {
      const saved = await a.download(path.join(this.dir, 'files'));
      prompt += a.kind === 'photo'
        ? `\n\n[The user sent a photo, saved at ${saved}. Look at it with the Read tool.]`
        : `\n\n[The user sent a file, saved at ${saved}.]`;
    }
    if (!prompt.trim()) return;
    l.chat = m.chat;
    this.opts.channelLog?.(kind, { dir: 'in', by: m.record ? 'user (for the record)' : 'user', text: prompt });
    if (m.record) return this.remember(kind, prompt, m.delivered);
    this.last = kind;
    if (!this.busy) this.begin(); // otherwise it steers the turn in progress
    this.asked.add(kind);
    this.ping();
    const records = this.records.splice(0);
    this.agent.write([...records.map(r => r.text), wrap(kind, prompt)].join('\n\n'));
    for (const r of records) r.delivered?.();
    m.delivered?.();
  }

  /** Something for the record, from a channel (or one the bot had: a voice call's lines left from before a restart):
   * it starts no turn, and goes in front of the agent's next message. */
  remember(kind: string, text: string, delivered?: () => void) {
    this.records.push({ text: wrap(kind, text), delivered });
  }

  // --- Turns -------------------------------------------------------------------------------------------------------

  private begin() {
    this.busy = true;
    this.asked.clear();
    this.answered.clear();
    this.reminded = false;
    if (this.typing) clearInterval(this.typing);
    this.typing = setInterval(() => this.ping(), this.opts.typingEvery ?? 4500);
  }

  /** The typing indicator, on each channel the turn has a message from (or the latest, for a turn of its own). */
  private ping() {
    for (const kind of this.asked.size ? this.asked : [this.last]) {
      const l = this.links.get(kind);
      if (l?.chat != null) l.channel.typing(l.chat).catch(() => {});
    }
  }

  private finish() {
    this.busy = false;
    if (this.typing) clearInterval(this.typing);
    this.typing = null;
  }

  /** The end of a turn. One that left a channel unanswered is reminded once, then the user gets a notice there. */
  private ended(r: { error: boolean; text: string }) {
    const asked = this.asked.size ? [...this.asked] : [this.last];
    if (r.error) {
      this.finish();
      return this.say(`⚠️ ${(r.text || `${this.agentLabel} failed`).slice(-1500)}`, asked);
    }
    // A turn of its own (no message started it) only has to have said something somewhere.
    const missing = this.asked.size ? asked.filter(k => !this.answered.has(k)) : this.answered.size ? [] : asked;
    if (!missing.length) return this.finish();
    if (!this.reminded) {
      this.reminded = true;
      return this.agent.write(reminder(missing));
    }
    this.finish();
    const last = r.text.trim();
    this.say(`⚠️ Otto didn't reply.${last ? `\nIts last words: ${last.slice(0, 1500)}` : ''}`, missing);
  }

  private say(text: string, kinds: string[] = [this.last]) {
    for (const kind of kinds) {
      const l = this.links.get(kind);
      if (l?.chat == null) continue;
      this.opts.channelLog?.(kind, { dir: 'out', by: `bot ${this.name}`, text });
      l.channel.send(l.chat, text).catch(e => this.log(`send on ${kind}: ${e.message}`));
    }
  }

  /** A message or file on one of the bot's channels from someone else on the box (`otto send`: a scheduled run, a
   * session), to the owner's chat there. It's not part of the bot's conversation, only of the channel's log. */
  async deliver(kind: string, m: { text?: string; file?: string; caption?: string }, by: string): Promise<void> {
    const l = this.links.get(kind);
    if (!l) throw new Error(`${this.name} has no ${kind} channel`);
    if (l.chat === null) throw new Error(`${kind} has no owner yet (otto bot pair ${this.name})`);
    if (m.file) await l.channel.sendFile(l.chat, m.file, m.caption);
    else await l.channel.send(l.chat, m.text ?? '');
    this.opts.channelLog?.(kind, { dir: 'out', by, text: m.file ? m.caption : m.text, file: m.file });
  }

  // --- The tools' socket -------------------------------------------------------------------------------------------

  /** The channel a tool call names, or the one it means when it names none. */
  private target(kind: unknown): Link {
    if (typeof kind === 'string' && kind) {
      const l = this.links.get(kind);
      if (!l) throw new Error(`no channel "${kind}" (this bot has ${this.kinds.join(', ')})`);
      return l;
    }
    if (this.links.size === 1) return [...this.links.values()][0];
    if (this.asked.size === 1) return this.links.get([...this.asked][0])!;
    throw new Error(`name the channel: ${this.kinds.join(' or ')}`);
  }

  // The tools' requests (tools.ts): {kind: "message", channel, text} or {kind: "file", channel, path, caption?}, sent
  // to the owner's chat on that channel. The socket is this user's alone (0600, in a 0700 folder).
  private listen(): Promise<void> {
    fs.rmSync(this.socket, { force: true });
    const server = http.createServer((req, res) => {
      let body = '';
      req.on('data', d => { body += d; });
      req.on('end', async () => {
        try {
          const m = JSON.parse(body);
          const l = this.target(m.channel);
          if (l.chat === null) throw new Error('no chat yet');
          if (m.kind === 'message') await l.channel.send(l.chat, String(m.text ?? ''));
          else if (m.kind === 'file') await l.channel.sendFile(l.chat, String(m.path ?? ''), m.caption);
          else throw new Error('unknown kind');
          this.opts.channelLog?.(l.channel.kind, { dir: 'out', by: `bot ${this.name}`,
            ...(m.kind === 'file' ? { file: String(m.path ?? ''), text: m.caption } : { text: String(m.text ?? '') }) });
          this.answered.add(l.channel.kind);
          res.writeHead(200).end('sent');
        } catch (e: any) {
          res.writeHead(400).end(e.message);
        }
      });
    });
    this.server = server;
    return new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(this.socket, () => { fs.chmodSync(this.socket, 0o600); resolve(); });
    });
  }
}
