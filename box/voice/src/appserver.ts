// One `codex app-server`, spoken to over stdio: JSON-RPC 2.0, one message per line. Requests get their answers by
// id; notifications go to on('notification'); requests from the server (approvals, user input) get a refusal, as
// nobody is at a keyboard to answer them. Its stderr is dropped: it can hold what we never log.
import { spawn, type ChildProcess } from 'node:child_process';
import { EventEmitter } from 'node:events';
import readline from 'node:readline';

export interface Notification { method: string; params: any }

export class AppServer extends EventEmitter {
  private proc: ChildProcess;
  private next = 0;
  private waiting = new Map<number, { resolve: (r: any) => void; reject: (e: Error) => void }>();
  private exited = false;

  constructor(bin = process.env.CODEX_BIN || 'codex') {
    super();
    // Its own process group, so its children (MCP servers) go when it does (kill).
    this.proc = spawn(bin, ['app-server'], { stdio: ['pipe', 'pipe', 'ignore'], detached: true });
    this.proc.on('error', e => this.gone(`codex: ${e.message}`));
    this.proc.on('exit', (code, sig) => this.gone(`codex app-server exited (${sig ?? code})`));
    readline.createInterface({ input: this.proc.stdout! }).on('line', line => this.line(line));
  }

  get pid() { return this.proc.pid }

  private line(line: string) {
    let m: any;
    try { m = JSON.parse(line) } catch { return }
    if (m.id !== undefined && m.method) {
      // A request from the server: nothing here answers questions, so it's declined.
      return this.write({ id: m.id, error: { code: -32601, message: 'not supported by the Otto voice adapter' } });
    }
    if (m.id !== undefined) {
      const w = this.waiting.get(m.id);
      if (!w) return;
      this.waiting.delete(m.id);
      return m.error ? w.reject(new Error(m.error.message ?? JSON.stringify(m.error))) : w.resolve(m.result);
    }
    if (m.method) this.emit('notification', { method: m.method, params: m.params ?? {} } as Notification);
  }

  private write(m: object) {
    if (this.exited) return;
    this.proc.stdin!.write(JSON.stringify({ jsonrpc: '2.0', ...m }) + '\n');
  }

  request<T = any>(method: string, params: object, timeoutMs = 60_000): Promise<T> {
    if (this.exited) return Promise.reject(new Error('codex app-server is gone'));
    const id = ++this.next;
    return new Promise<T>((resolve, reject) => {
      const t = setTimeout(() => {
        this.waiting.delete(id);
        reject(new Error(`${method}: no answer in ${timeoutMs / 1000} s`));
      }, timeoutMs);
      this.waiting.set(id, {
        resolve: r => { clearTimeout(t); resolve(r) },
        reject: e => { clearTimeout(t); reject(e) },
      });
      this.write({ id, method, params });
    });
  }

  notify(method: string, params?: object) {
    this.write(params ? { method, params } : { method });
  }

  /** initialize, with the experimental API (realtime is behind it), then initialized. */
  async init() {
    await this.request('initialize', { clientInfo: { name: 'otto_voice', version: '0.1' }, capabilities: { experimentalApi: true } });
    this.notify('initialized');
  }

  private gone(why: string) {
    if (this.exited) return;
    this.exited = true;
    for (const w of this.waiting.values()) w.reject(new Error(why));
    this.waiting.clear();
    this.emit('exit', why);
  }

  /** Ends it and its process group: TERM, then KILL if it's still there a couple of seconds later. */
  kill(): Promise<void> {
    const pid = this.proc.pid;
    if (this.exited || !pid) return Promise.resolve();
    const signal = (s: NodeJS.Signals) => { try { process.kill(-pid, s) } catch {} };
    signal('SIGTERM');
    return new Promise(resolve => {
      const t = setTimeout(() => { signal('SIGKILL'); resolve() }, 2000);
      this.once('exit', () => { clearTimeout(t); signal('SIGKILL'); resolve() });
    });
  }
}
