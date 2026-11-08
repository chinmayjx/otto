// The phone's browser, for agent sessions: each session drives tabs of its own, in a tab group of its own.
//
// The browser is reached through the phone's tunnel (tunnel.ts): its control lines go over the tunnel's control
// connection, and each DevTools connection is a "devtools" pipe, joined on the phone to the browser's DevTools socket.
//
// The app is a plain browser with tab groups; it knows nothing of agent sessions. The relay keeps which group is
// whose (Sessions), and the app tells it each live tab's target, tab id and group. A tab's id and group last;
// its target is new whenever the tab comes back to life (the app restarted, or the tab had gone dormant).
//
// A session's DevTools endpoint shows only the tabs in its group: the relay sits in the WebSocket, drops events
// and sessions of other tabs, and turns "new page" / "close page" into tab requests to the app. When a session
// connects, the relay first wakes its group (its tabs come back at their pages, with new targets), or opens its
// first tab in a new group; when it disconnects, the group may go dormant again.
//
// Control lines, app to relay: active | tab <target> <tab> <group|-> | gone <target> | made <req> <target> <group>
//   | woke <req> <tabs> | fail <req>
// Relay to app: newtab <req> <group|-> <title> | wake <req> <group> | idle <group> | close <target>
//   | focus <target> | closegroup <group> | show <url>
import { WebSocket, type RawData } from 'ws';
import crypto from 'node:crypto';
import type { Sessions } from './sessions.js';
import type { Phone, Tunnel } from './tunnel.js';

export const NO_PHONE = 'The phone browser is not connected: open the Otto app on the phone (it connects while its handle is on).';
const OURS = 1e9; // ids of the relay's own DevTools requests
const DEBUG = !!process.env.OTTO_RELAY_DEBUG;

type Waiting = { resolve: (v: any) => void; reject: (e: Error) => void; phone: Phone };

export class Browser {
  private asks = new Map<string, Waiting>();
  private users = new Map<string, number>(); // group -> connections working in it now

  constructor(private tunnel: Tunnel, private sessions: Sessions, private log = console.log) {
    tunnel.on('line', (p: Phone, w: string[]) => this.control(p, w));
    const drop = (p: Phone) => {
      for (const [req, a] of this.asks) if (a.phone === p) { this.asks.delete(req); a.reject(new Error('the phone browser went away')); }
    };
    tunnel.on('gone', drop);
    tunnel.on('line', (p: Phone, w: string[]) => { if (w[0] === 'browser' && w[1] === 'down') drop(p); });
  }

  /** The phone whose browser was used last, if any is connected. */
  phone(): Phone | null {
    return this.tunnel.phone(p => p.browser);
  }

  status() {
    const cur = this.phone();
    return {
      phones: [...this.tunnel.phones].filter(p => p.browser).map(p => ({ app: p.app, current: p === cur, tabs: Object.fromEntries(p.tabs) })),
      working: Object.fromEntries(this.users),
    };
  }

  show(url: string): boolean {
    const p = this.phone();
    if (!p) return false;
    this.tunnel.send(p, `show ${url.replace(/\s/g, '%20')}`);
    return true;
  }

  /** Closes a session's tabs and forgets its group; the group id, or null if it had none. */
  close(session: string): string | null {
    const g = this.sessions.group(session);
    if (!g) return null;
    const p = this.phone();
    if (p) this.tunnel.send(p, `closegroup ${g}`);
    this.sessions.delete(session);
    return g;
  }

  /** Targets of a session's live tabs (for /json/list). */
  targets(session: string): string[] {
    const p = this.phone(), g = this.sessions.group(session);
    if (!p || !g) return [];
    return [...p.tabs].filter(([, t]) => t.group === g).map(([id]) => id);
  }

  // --- The browser's control lines (the tunnel keeps "active" and "browser" itself).

  private control(p: Phone, w: string[]) {
    switch (w[0]) {
      case 'tab': p.tabs.set(w[1], { tab: w[2], group: w[3] === '-' ? null : w[3] }); break;
      case 'gone': p.tabs.delete(w[1]); break;
      case 'made': case 'woke': case 'fail': {
        const r = this.asks.get(w[1]);
        this.asks.delete(w[1]);
        if (!r) return;
        if (w[0] === 'made') r.resolve({ targetId: w[2], group: w[3] });
        else if (w[0] === 'woke') r.resolve(+w[2]);
        else r.reject(new Error('the phone could not open a tab'));
      }
    }
  }

  private ask<T>(p: Phone, line: string, what: string): Promise<T> {
    const req = crypto.randomBytes(6).toString('hex');
    this.tunnel.send(p, line.replace('<req>', req));
    return new Promise((resolve, reject) => {
      this.asks.set(req, { resolve, reject, phone: p });
      setTimeout(() => { if (this.asks.delete(req)) reject(new Error(`the phone did not ${what}`)); }, 15_000);
    });
  }

  /** A new tab for a session, in its group (a new group, titled for the session, if it has none or the app no longer has it). */
  private async newTab(p: Phone, session: string, title: string): Promise<string> {
    const r = await this.ask<{ targetId: string; group: string }>(p, `newtab <req> ${this.sessions.group(session) ?? '-'} ${oneLine(title)}`, 'open a tab');
    this.sessions.set(session, r.group, title);
    return r.targetId;
  }

  /** Brings a group's tabs back to life (they reload at their pages); how many tabs it has (0: none left). */
  private wake(p: Phone, group: string | undefined): Promise<number> {
    return group ? this.ask<number>(p, `wake <req> ${group}`, 'wake the tabs') : Promise.resolve(0);
  }

  // --- A session's DevTools connection: its tabs only.

  async session(session: string, title: string, client: WebSocket) {
    const early: RawData[] = [];
    const onEarly = (d: RawData) => early.push(d);
    client.on('message', onEarly);
    const current = this.phone();
    if (!current) throw new Error(NO_PHONE);
    const p: Phone = current, sessions = this.sessions;
    title = title || sessions.get(session)?.title || 'Agent';
    const mine = (id: string) => { const g = p.tabs.get(id)?.group; return !!g && g === sessions.group(session); };
    if (!(await this.wake(p, sessions.group(session)))) await this.newTab(p, session, title); // its tabs back to life, or its first tab
    sessions.touch(session);
    const group = sessions.group(session)!;
    this.users.set(group, (this.users.get(group) ?? 0) + 1);
    let left = false;
    const leave = () => {
      if (left) return;
      left = true;
      const n = (this.users.get(group) ?? 1) - 1;
      if (n > 0) return void this.users.set(group, n);
      this.users.delete(group);
      if (this.tunnel.phones.has(p)) this.tunnel.send(p, `idle ${group}`); // its tabs may go dormant
    };

    let up: WebSocket;
    try {
      const sock = await this.tunnel.openPipe(p, 'devtools');
      up = new WebSocket('ws://localhost/devtools/browser', { createConnection: () => sock.resume(), perMessageDeflate: false, maxPayload: 1 << 30 });
      await new Promise((resolve, reject) => { up.once('open', resolve); up.once('error', reject); });
    } catch (e) { leave(); throw e; }
    this.log(`session ${session}: connected (${p.app})`);

    const allowed = new Set<string>(); // DevTools sessions (attachments) of this session's targets
    const seen = new Set<string>(); // targets shown to the client
    const attached = new Set<string>(); // targets the client has been attached to
    const methods = new Map<number, string>(); // client request id -> method
    let ourId = OURS;
    const toClient = (msg: any) => { if (client.readyState === WebSocket.OPEN) client.send(JSON.stringify(msg)); };
    const toUp = (msg: any) => { if (up.readyState === WebSocket.OPEN) up.send(JSON.stringify(msg)); };
    const reply = (id: number, result: any) => toClient({ id, result });
    const fail = (id: number, message: string) => toClient({ id, error: { code: -32000, message } });

    // Is this target the session's? A tab that just opened may not be announced yet: wait for the app a moment.
    async function owned(info: any): Promise<boolean> {
      if (info.type === 'browser') return true;
      if (info.type !== 'page') return false;
      for (let i = 0; i < 60; i++) {
        const t = p.tabs.get(info.targetId);
        // Its group may be one being made for this session right now (the app's "made" reply still on its way).
        if (t && (mine(info.targetId) || !t.group || sessions.claimed(t.group))) break;
        await sleep(50);
      }
      return mine(info.targetId);
    }

    const fromUp = async (msg: any) => {
      if (DEBUG) this.log(`${session} <- ${msg.method ?? 'reply ' + msg.id} ${msg.method ? JSON.stringify(msg.params).slice(0, 160) : ''}`);
      if (msg.id !== undefined) {
        if (msg.id >= OURS) return;
        const method = methods.get(msg.id);
        methods.delete(msg.id);
        if (method === 'Target.getTargets' && msg.result) msg.result.targetInfos = msg.result.targetInfos.filter((t: any) => t.type === 'browser' || mine(t.targetId));
        return toClient(msg);
      }
      if (msg.sessionId && !allowed.has(msg.sessionId)) return;
      const prm = msg.params ?? {};
      switch (msg.method) {
        case 'Target.attachedToTarget':
          if (msg.sessionId || await owned(prm.targetInfo)) {
            allowed.add(prm.sessionId);
            seen.add(prm.targetInfo.targetId);
            attached.add(prm.targetInfo.targetId);
            return toClient(msg);
          }
          // Another session's tab, auto-attached here too: let it run and leave it to its own session.
          if (prm.waitingForDebugger) toUp({ id: ourId++, method: 'Runtime.runIfWaitingForDebugger', sessionId: prm.sessionId });
          toUp({ id: ourId++, method: 'Target.detachFromTarget', params: { sessionId: prm.sessionId } });
          return;
        case 'Target.detachedFromTarget':
          if (!msg.sessionId && !allowed.has(prm.sessionId)) return;
          allowed.delete(prm.sessionId);
          return toClient(msg);
        case 'Target.targetCreated':
        case 'Target.targetInfoChanged':
          if (!msg.sessionId) {
            if (!(await owned(prm.targetInfo))) return;
            seen.add(prm.targetInfo.targetId);
          }
          return toClient(msg);
        case 'Target.targetDestroyed':
        case 'Target.targetCrashed':
          if (!msg.sessionId && !seen.has(prm.targetId)) return;
          return toClient(msg);
      }
      toClient(msg);
    };

    const fromClient = async (msg: any) => {
      if (DEBUG) this.log(`${session} -> ${msg.id} ${msg.method} ${JSON.stringify(msg.params ?? {}).slice(0, 120)}`);
      const prm = msg.params ?? {};
      if (msg.sessionId && !allowed.has(msg.sessionId)) return fail(msg.id, 'No such session');
      if (!msg.sessionId) {
        switch (msg.method) {
          case 'Browser.close': // the browser is the phone's; a session only lets go of it
            reply(msg.id, {});
            return client.close();
          case 'Target.createTarget':
            try {
              // Chrome reports the new page (attachedToTarget) before answering, and Playwright counts on it.
              const targetId = await this.newTab(p, session, title);
              for (let i = 0; i < 100 && !attached.has(targetId); i++) await sleep(50);
              return reply(msg.id, { targetId });
            } catch (e: any) { return fail(msg.id, e.message); }
          case 'Target.closeTarget':
            if (!mine(prm.targetId)) return fail(msg.id, 'No such target');
            this.tunnel.send(p, `close ${prm.targetId}`);
            return reply(msg.id, { success: true });
          case 'Target.activateTarget':
            if (!mine(prm.targetId)) return fail(msg.id, 'No such target');
            this.tunnel.send(p, `focus ${prm.targetId}`);
            return reply(msg.id, {});
          case 'Target.attachToTarget':
            if (!mine(prm.targetId)) return fail(msg.id, 'No such target');
            break;
        }
      }
      methods.set(msg.id, msg.method);
      toUp(msg);
    };

    // Messages each way are handled in order, though deciding ownership may wait.
    let upChain = Promise.resolve(), clientChain = Promise.resolve();
    up.on('message', d => { upChain = upChain.then(() => fromUp(JSON.parse(String(d)))).catch(e => this.log(`session ${session}: ${e.message}`)); });
    const onClient = (d: RawData) => { clientChain = clientChain.then(() => fromClient(JSON.parse(String(d)))).catch(e => this.log(`session ${session}: ${e.message}`)); };
    client.off('message', onEarly);
    client.on('message', onClient);
    early.forEach(onClient);
    const done = () => { up.close(); client.close(); leave(); };
    client.on('close', done);
    up.on('close', done);
    client.on('error', () => {});
    up.on('error', () => {});
  }
}

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));
const oneLine = (s: string) => s.replace(/\s+/g, ' ').trim() || 'Agent';
