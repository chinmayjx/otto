// Browser commands for the CLI: each session's own Playwright connection to its DevTools endpoint, kept open
// between commands so they answer at once, and closed after a few idle minutes (its tabs may then go dormant).
// The page a session works on is the tab it opened or picked last.
import { chromium, type Browser as PwBrowser, type Page } from 'playwright-core';

const IDLE_MS = 5 * 60_000;
const NAV_TIMEOUT = 30_000;
const ACTION_TIMEOUT = 10_000;

interface Conn { browser: PwBrowser; page: Page | null; idle: NodeJS.Timeout | null }

export interface Result { text?: string; image?: Buffer }

export class Driver {
  private conns = new Map<string, Promise<Conn>>();

  constructor(private endpoint: (session: string) => string) {}

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

  private async page(conn: Conn): Promise<Page> {
    const all = this.pages(conn);
    if (conn.page && all.includes(conn.page)) return conn.page;
    conn.page = all[all.length - 1] ?? await conn.browser.contexts()[0].newPage();
    return conn.page;
  }

  async run(session: string, cmd: string, a: Record<string, any>): Promise<Result> {
    const conn = await this.conn(session);
    this.keepAlive(session, conn);
    const page = cmd === 'tabs' ? null : await this.page(conn);
    switch (cmd) {
      case 'open': {
        let p = page!;
        if (a.new) { p = await conn.browser.contexts()[0].newPage(); conn.page = p; }
        await p.goto(a.url, { timeout: NAV_TIMEOUT, waitUntil: 'domcontentloaded' });
        return { text: await where(p) };
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
      case 'tabs': {
        const all = this.pages(conn);
        if (a.select !== undefined) {
          const p = all[+a.select];
          if (!p) throw new Error(`no tab ${a.select}`);
          conn.page = p;
          await p.bringToFront();
        } else if (a.close !== undefined) {
          const p = all[+a.close];
          if (!p) throw new Error(`no tab ${a.close}`);
          await p.close();
        } else if (a.new) {
          conn.page = await conn.browser.contexts()[0].newPage();
        }
        const now = this.pages(conn);
        const cur = conn.page && now.includes(conn.page) ? conn.page : now[now.length - 1];
        const lines = await Promise.all(now.map(async (p, i) => `${p === cur ? '*' : ' '} ${i}  ${await p.title().catch(() => '')}  ${p.url()}`));
        return { text: lines.join('\n') || 'no tabs' };
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
