// A bot: one channel, one agent conversation, one owner. The core decides who may talk to the bot (pairing, then
// the owner only), handles its commands, and passes every other message to the agent: while a turn runs it steers
// that turn, while the agent is idle it starts one. The agent answers only through the bot's tools (tools.ts), which
// reach the core over a unix socket only this user can open; a turn that ends without sending the user anything is
// reminded once, then the user gets a notice. Channel- and agent-neutral: those are behind channel.ts and
// provider.ts.
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
  channel: Channel;
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
}

const COMMANDS = [
  { command: 'new', description: 'Start a new conversation' },
  { command: 'stop', description: 'Stop the agent (the next message resumes)' },
  { command: 'status', description: 'Session and state' },
];

const REMINDER = '[From the bot, not the user: you ended your turn without sending the user anything, and they see '
  + 'only what you send with send_message. Reply to them now with send_message.]';

function instructions(channel: Channel, tool: (t: string) => string): string {
  return [
    `You are talking with the user through a ${channel.label} bot on their phone.`,
    `The user sees only what you send with the send_message and send_file tools (${tool('send_message')},`,
    `${tool('send_file')}); your plain text is not shown. Every message from the user needs at least one send_message`,
    'back. For work that takes more than a moment, send a short heads-up first, then the result. New messages from',
    'the user can arrive while you work: take them into account as you go. Attach files you make with send_file.',
    'Keep messages short and easy to read on a phone: plain sentences, short lists.',
    channel.formatting,
    'For anything long or visual (a report, a table, a plan, a page), publish an artifact with `otto artifact publish`',
    'and send a short summary and its link.',
  ].join(' ');
}

export class Bot {
  readonly name: string;
  config: BotConfig;
  readonly cwd: string;
  private channel: Channel;
  private agent: Agent;
  private dir: string;
  private socket: string;
  private server: http.Server | null = null;
  private log: (m: string) => void;
  private opts: BotOptions;
  private agentLabel: string;

  private pairCode: string | null = null;
  private chat: Id | null = null; // where the agent's messages go: the owner's chat
  private busy = false; // a turn is in progress
  private sent = false; // the turn in progress has sent the user something
  private reminded = false; // the turn in progress was reminded to reply
  private typing: NodeJS.Timeout | null = null;

  constructor(o: BotOptions) {
    this.opts = o;
    this.name = o.name;
    this.config = o.config;
    this.channel = o.channel;
    this.dir = o.stateDir;
    this.socket = path.join(o.stateDir, 'tools.sock');
    this.log = o.log;
    this.cwd = o.config.cwd || path.join(os.homedir(), 'code');
    this.agentLabel = o.provider.label;
    this.agent = o.provider.create({
      cwd: this.cwd,
      session: () => this.config.session,
      permissionMode: o.config.permissionMode || 'auto',
      model: o.config.model,
      instructions: instructions(o.channel, t => o.provider.toolName(t)),
      tools: { ...o.tools, env: { OTTO_BOT_SOCKET: this.socket, OTTO_BOT_CHANNEL: o.channel.label } },
      // The agent's `otto` commands (the phone's browser, …) get a session of their own.
      env: { OTTO_SESSION: `bot-${o.name}`, OTTO_SESSION_TITLE: `${o.name} bot` },
    }, {
      session: id => { this.config = this.opts.save({ session: id }); },
      activity: () => { if (!this.busy) this.begin(); }, // a turn the agent started on its own (a message it took late)
      result: r => this.ended(r),
      exit: (code, err) => {
        const was = this.busy;
        this.finish();
        this.log(`${this.agentLabel} exited (${code})${was ? ' mid-turn' : ''}`);
        if (was && code !== null && code !== 143) this.say(`⚠️ ${this.agentLabel} stopped (exit ${code}).\n${err.slice(-1500)}`);
      },
    });
  }

  async start(): Promise<void> {
    fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 });
    await this.listen();
    await this.channel.start(m => this.onMessage(m).catch(e => this.log(`message: ${e.stack ?? e}`)));
    await this.channel.setCommands(COMMANDS).catch(e => this.log(`commands: ${e.message}`));
    if (!this.config.owner) {
      this.pairCode = randomBytes(4).toString('hex');
      this.log(`unowned: send "/start ${this.pairCode}" to ${this.channel.handle ?? 'the bot'} to claim it`);
    }
    this.writeStatus();
    this.log(`${this.channel.label} ${this.channel.handle ?? ''}: polling; folder ${this.cwd}; session ${this.config.session || '(new)'}`);
  }

  stop(): void {
    this.channel.stop();
    this.agent.stop();
    this.finish();
    this.server?.close();
    this.server?.closeAllConnections();
    this.server = null;
    fs.rmSync(this.socket, { force: true });
    fs.rmSync(path.join(this.dir, 'status.json'), { force: true });
  }

  /** What the CLI shows: the bot's name on the channel, and while unowned, its pairing code and link. */
  private writeStatus() {
    const pair = this.pairCode ? { code: this.pairCode, link: this.channel.pairLink(this.pairCode) } : null;
    writePrivate(path.join(this.dir, 'status.json'), JSON.stringify({ handle: this.channel.handle, pid: process.pid, owned: !!this.config.owner, pair }, null, 2));
  }

  // --- Messages ----------------------------------------------------------------------------------------------------

  async onMessage(m: Incoming): Promise<void> {
    const text = m.text.replace(/^(\/\w+)@\w+/, '$1'); // "/new@some_bot", as group chats send commands
    if (!this.config.owner) {
      if (this.pairCode && text === `/start ${this.pairCode}`) {
        this.config = this.opts.save({ owner: m.from });
        this.pairCode = null;
        this.writeStatus();
        this.log(`paired with ${m.from}`);
        await this.channel.send(m.chat, `Paired. This bot is a ${this.agentLabel} conversation on your box: just write.`);
      }
      return;
    }
    if (String(m.from) !== String(this.config.owner)) return; // anyone else: silence

    if (text === '/new' || text === '/stop') {
      const was = this.agent.running;
      this.agent.stop();
      this.finish();
      if (text === '/new') {
        this.config = this.opts.save({ session: null });
        return this.channel.send(m.chat, '🆕 New conversation.');
      }
      return this.channel.send(m.chat, was ? '⏹ Stopped.' : 'Nothing running.');
    }
    if (text === '/status') {
      const state = this.busy ? 'Working' : this.agent.running ? 'Idle' : 'Not running';
      return this.channel.send(m.chat, `Session: ${this.config.session || '(new)'}\nFolder: ${this.cwd}\n${state}`);
    }

    let prompt = m.text;
    for (const a of m.attachments) {
      const saved = await a.download(path.join(this.dir, 'files'));
      prompt += a.kind === 'photo'
        ? `\n\n[The user sent a photo, saved at ${saved}. Look at it with the Read tool.]`
        : `\n\n[The user sent a file, saved at ${saved}.]`;
    }
    if (!prompt.trim()) return;
    this.chat = m.chat;
    if (!this.busy) this.begin(); // otherwise it steers the turn in progress
    this.agent.write(prompt);
  }

  // --- Turns -------------------------------------------------------------------------------------------------------

  private begin() {
    this.busy = true;
    this.sent = false;
    this.reminded = false;
    if (this.typing) clearInterval(this.typing);
    const chat = this.chat;
    if (chat === null) return;
    const ping = () => { this.channel.typing(chat).catch(() => {}); };
    ping();
    this.typing = setInterval(ping, this.opts.typingEvery ?? 4500);
  }

  private finish() {
    this.busy = false;
    if (this.typing) clearInterval(this.typing);
    this.typing = null;
  }

  /** The end of a turn. One that sent the user nothing is reminded once, then the user gets a notice. */
  private ended(r: { error: boolean; text: string }) {
    if (r.error) {
      this.finish();
      return this.say(`⚠️ ${(r.text || `${this.agentLabel} failed`).slice(-1500)}`);
    }
    if (this.sent) return this.finish();
    if (!this.reminded) {
      this.reminded = true;
      return this.agent.write(REMINDER);
    }
    this.finish();
    const last = r.text.trim();
    this.say(`⚠️ Otto didn't reply.${last ? `\nIts last words: ${last.slice(0, 1500)}` : ''}`);
  }

  private say(text: string) {
    if (this.chat !== null) this.channel.send(this.chat, text).catch(e => this.log(`send: ${e.message}`));
  }

  // --- The tools' socket -------------------------------------------------------------------------------------------

  // The tools' requests (tools.ts): {kind: "message", text} or {kind: "file", path, caption?}, sent to the owner's
  // chat. The socket is this user's alone (0600, in a 0700 folder).
  private listen(): Promise<void> {
    fs.rmSync(this.socket, { force: true });
    const server = http.createServer((req, res) => {
      let body = '';
      req.on('data', d => { body += d; });
      req.on('end', async () => {
        try {
          const m = JSON.parse(body);
          if (this.chat === null) throw new Error('no chat yet');
          if (m.kind === 'message') await this.channel.send(this.chat, String(m.text ?? ''));
          else if (m.kind === 'file') await this.channel.sendFile(this.chat, String(m.path ?? ''), m.caption);
          else throw new Error('unknown kind');
          this.sent = true;
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
