// Browser commands for the CLI: each session's own Playwright connection to its DevTools endpoint, kept open
// between commands so they answer at once, and closed after a few idle minutes (its tabs may then go dormant).
// The page a session works on is the tab it opened or picked last (any tab, the user's too), else its group's
// newest; it never lands in someone else's tab unasked.
import { chromium, type Browser as PwBrowser, type Page } from 'playwright-core';
import type { Browser, Tab } from './browser.js';

const IDLE_MS = 5 * 60_000;
const NAV_TIMEOUT = 30_000;
const ACTION_TIMEOUT = 10_000;

interface Conn { browser: PwBrowser; page: Page | null; idle: NodeJS.Timeout | null }

export interface Result { text?: string; image?: Buffer }

export class Driver {
  private conns = new Map<string, Promise<Conn>>();

  private targetIds = new WeakMap<Page, string>();

  constructor(private endpoint: (session: string) => string, private phone: Browser) {}

  /** A page's DevTools target id. */
  private async targetOf(page: Page): Promise<string> {
    let id = this.targetIds.get(page);
    if (!id) {
      const cdp = await page.context().newCDPSession(page);
      id = (await cdp.send('Target.getTargetInfo')).targetInfo.targetId as string;
      cdp.detach().catch(() => {});
      this.targetIds.set(page, id);
    }
    return id;
  }

  /** The page for a target, once Playwright has it (a tab just woken takes a moment). */
  private async pageFor(conn: Conn, targetId: string): Promise<Page> {
    for (let i = 0; i < 100; i++) {
      for (const p of this.pages(conn)) if (await this.targetOf(p).catch(() => '') === targetId) return p;
      await new Promise(r => setTimeout(r, 100));
    }
    throw new Error('the tab did not come up');
  }

  private conn(session: string): Promise<Conn> {
    let c = this.conns.get(session);
    if (!c) {
      c = chromium.connectOverCDP(this.endpoint(session)).then(browser => {
        const conn: Conn = { browser, page: null, idle: null };
        browser.on('disconnected', () => this.conns.delete(session));
        return conn;
      });
      c.catch(() => this.conns.delete(session));
      this.conns.set(session, c);
    }
    return c;
  }

  private keepAlive(session: string, conn: Conn) {
    if (conn.idle) clearTimeout(conn.idle);
    conn.idle = setTimeout(() => { this.conns.delete(session); conn.browser.close().catch(() => {}); }, IDLE_MS);
    conn.idle.unref();
  }

  private pages(conn: Conn): Page[] {
    return conn.browser.contexts()[0]?.pages() ?? [];
  }

  private async page(session: string, conn: Conn): Promise<Page> {
    const all = this.pages(conn);
    if (conn.page && all.includes(conn.page)) return conn.page;
    let mine: Page | null = null;
    for (const p of all) if (this.phone.mine(session, await this.targetOf(p).catch(() => ''))) mine = p;
    conn.page = mine ?? await conn.browser.contexts()[0].newPage();
    return conn.page;
  }

  /** Every tab, by group: this session's first, then the user's, then other sessions'. */
  private async listing(session: string, conn: Conn): Promise<{ tabs: Tab[]; text: string }> {
    const tabs = await this.phone.list();
    const cur = conn.page && this.pages(conn).includes(conn.page) ? await this.targetOf(conn.page).catch(() => '') : '';
    const myGroup = tabs.find(t => t.target && this.phone.mine(session, t.target))?.group;
    const order = (t: Tab) => t.group === myGroup ? 0 : t.group === '' ? 1 : 2;
    const sorted = [...tabs].sort((a, b) => order(a) - order(b)); // stable: the app's order within each
    const lines: string[] = [];
    let last: string | null = null;
    sorted.forEach((t, i) => {
      if (t.group !== last) {
        last = t.group;
        lines.push(t.group === myGroup ? `${t.groupTitle || '(not named)'}: your session's group` : t.group === '' ? "The user's own tabs" : `${t.groupTitle}: another session's`);
      }
      const marks = [t.target && t.target === cur ? 'working here' : '', t.current ? 'on screen' : '', t.target ? '' : 'asleep'].filter(Boolean);
      lines.push(`${t.target && t.target === cur ? '*' : ' '} ${i}  ${t.title || '(no title)'}  ${t.url}${marks.length ? `  (${marks.join(', ')})` : ''}`);
    });
    return { tabs: sorted, text: lines.join('\n') || 'no tabs' };
  }

  /** After a session's first pages, a reminder to name its group, while it hasn't. */
  private nameHint(session: string): string {
    return this.phone.named(session) ? '' : '\n(name your tab group so the user can find it: `otto browser name "<2-3 words about the task>"`)';
  }

  async run(session: string, cmd: string, a: Record<string, any>): Promise<Result> {
    const conn = await this.conn(session);
    this.keepAlive(session, conn);
    const page = cmd === 'tabs' || cmd === 'name' ? null : await this.page(session, conn);
    if (a.name) this.phone.name(session, a.name);
    switch (cmd) {
      case 'open': {
        let p = page!;
        if (a.new) { p = await conn.browser.contexts()[0].newPage(); conn.page = p; }
        await p.goto(a.url, { timeout: NAV_TIMEOUT, waitUntil: 'domcontentloaded' });
        return { text: await where(p) + this.nameHint(session) };
      }
      case 'snapshot':
        return { text: `${await where(page!)}\n${await page!.ariaSnapshot({ mode: 'ai', depth: a.depth })}` };
      case 'click':
        await target(page!, a).click({ timeout: ACTION_TIMEOUT });
        await settle(page!);
        return { text: `clicked; ${await where(page!)}` };
      case 'type': {
        const t = target(page!, a);
        if (a.append) await t.pressSequentially(a.text, { timeout: ACTION_TIMEOUT });
        else await t.fill(a.text, { timeout: ACTION_TIMEOUT });
        if (a.submit) { await t.press('Enter'); await settle(page!); }
        return { text: a.submit ? `typed and submitted; ${await where(page!)}` : 'typed' };
      }
      case 'press':
        await page!.keyboard.press(a.key);
        await settle(page!);
        return { text: `pressed ${a.key}; ${await where(page!)}` };
      case 'back':
        await page!.goBack({ timeout: NAV_TIMEOUT });
        return { text: await where(page!) };
      case 'wait':
        if (a.text) await page!.getByText(a.text).first().waitFor({ timeout: a.timeout ?? NAV_TIMEOUT });
        else await page!.waitForTimeout(a.ms ?? 1000);
        return { text: `ok; ${await where(page!)}` };
      case 'eval': {
        const v = await page!.evaluate(a.js.includes('return') || a.js.trim().startsWith('(') ? `(async () => { ${a.js} })()` : a.js);
        return { text: typeof v === 'string' ? v : JSON.stringify(v, null, 1) ?? 'undefined' };
      }
      case 'screenshot':
        return { image: await page!.screenshot({ fullPage: !!a.full, scale: 'css', type: 'png' }) };
      case 'name':
        if (!a.name) throw new Error('give the group a name');
        await this.page(session, conn); // it has a group once it has a tab
        this.phone.name(session, a.name);
        return { text: `your tabs' group is now "${a.name.trim()}"` };
      case 'tabs': {
        const n = a.select ?? a.close;
        if (n !== undefined) {
          const t = (await this.listing(session, conn)).tabs[+n];
          if (!t) throw new Error(`no tab ${n}: \`otto browser tabs\` lists them`);
          if (a.close !== undefined) this.phone.closeTab(t.id);
          else conn.page = await this.pageFor(conn, await this.phone.wakeTab(t.id)); // works there; doesn't change what the user sees
          if (a.close !== undefined) await new Promise(r => setTimeout(r, 300));
        } else if (a.new) {
          conn.page = await conn.browser.contexts()[0].newPage();
        }
        return { text: (await this.listing(session, conn)).text };
      }
    }
    throw new Error(`unknown browser command: ${cmd}`);
  }
}

/** An element by its snapshot ref (e12), its visible text, or a CSS/Playwright selector. */
function target(page: Page, a: Record<string, any>) {
  if (a.ref) return page.locator(`aria-ref=${a.ref}`);
  if (a.text) return page.getByText(a.text, { exact: false }).first();
  if (a.selector) return page.locator(a.selector).first();
  throw new Error('say which element: a ref from the snapshot, its text, or a selector');
}

async function where(page: Page): Promise<string> {
  return `${page.url()}  "${await page.title().catch(() => '')}"`;
}

/** After an action, a moment for a navigation it started to commit. */
async function settle(page: Page) {
  await page.waitForLoadState('domcontentloaded', { timeout: 5_000 }).catch(() => {});
}
