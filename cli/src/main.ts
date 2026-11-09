#!/usr/bin/env node
// otto: the phone, its browser, the box's desktop and artifacts, for agents and people. Run `otto help`.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { bot } from './bots.js';
import { loadConfig, session, relay, browser, phone, saveShot, desktopShot, pairing, pairingLink, OttoError } from './lib.js';

const HELP = `otto: the phone, its browser and the box's desktop, for agents and people

Browser (the phone's browser; your session's own tabs):
  otto browser open <url> [--new]          open a page (--new: in a new tab)
  otto browser snapshot [--depth N]        the page as an accessibility tree, with [ref=eN] for each element
  otto browser click <ref> | --text T | --selector S
  otto browser type <ref> <text> [--submit] [--append]
  otto browser press <key>                 e.g. Enter, Escape, ArrowDown
  otto browser back
  otto browser wait [--text T] [--ms N]
  otto browser eval <js>                   an expression, or a body with return
  otto browser screenshot [--full]         saves a PNG, prints its path
  otto browser tabs [select N | close N | new]

Phone:
  otto phone screenshot [--scale 0.5]      saves a JPEG, prints its path and size
  otto phone ui [--all] [--xml]            what's on screen: one line per element, with bounds
  otto phone tap <x> <y> | --text T | --id I | --desc D [--index N]
  otto phone longpress <x> <y> | --text T ... [--ms N]
  otto phone swipe up|down|left|right | <x1> <y1> <x2> <y2> [--ms N]
  otto phone type <text> [--replace]       into the focused field
  otto phone paste <text>                  pasted at the cursor via the clipboard (for fields that misreport their text)
  otto phone clear                         empty the focused field
  otto phone key back|home|recents|enter|notifications|quicksettings|dismiss|power|lock|splitscreen
  otto phone wait --text T | --id I | --desc D [--gone] [--timeout MS]
  otto phone launch <package> [--activity A] [--url U] | --url U   (package + activity + url: that screen, with the link)
  otto phone current                       the app in front
  otto phone info                          model, Android, screen, battery, permissions
  otto phone packages [--filter F] [--third-party] [--labels]
  otto phone appinfo <package>
  otto phone install <file.apk>
  otto phone uninstall <package>
  otto phone forcestop <package>
  otto phone logcat [--package P] [--level W] [--grep RE] [--lines N] [--since CURSOR]

Desktop (the box's VNC desktop):
  otto desktop screenshot

The second screen and status:
  otto show <url>                          open a page in the phone's second screen
  otto close                               close your session's tabs
  otto status                              the phone's links, tabs and sessions
  otto pair [--send] [--box HOST] [--qr]   the Otto app's pairing link for this box (--send: open it on the
                                           phone through its tunnel; --qr: also as a QR code)
  otto artifact ...                        publish and list artifacts (the artifact CLI)

Clips (what the user collected on the phone for you: text, screenshots, notes on an artifact):
  otto clip <id>                           the clip: its text, its source, and the paths of its images
  otto clip list [--limit N]               the newest clips
  otto clip rm <id>

Bots (chat bots, each one long-running agent conversation on this box; the otto-bots service runs them):
  otto bot add <name> --channel telegram [--cwd DIR] [--model M] [--permission-mode MODE]
                                           the token from @BotFather is read from stdin or a prompt; then pair
  otto bot pair <name> [--reset]           the link (and code) that makes your account the bot's owner
  otto bot voice <name> [--provider openai-realtime|android] [--model M] [--voice V] [--key] | --off
                                          make the bot your assistant: the Otto app's voice calls reach it
                                          (openai-realtime asks for an API key on stdin, never as an argument)
  otto bot list
  otto bot logs <name> [--lines N] [--follow]
  otto bot rm <name>

Your session comes from OTTO_SESSION (set by your agent's adapter).`;

interface Args { _: string[]; [k: string]: any }

/** What agents used to Playwright's tools tend to type. */
const ALIASES: Record<string, string> = { navigate: 'open', goto: 'open', go: 'open', fill: 'type', key: 'press', press_key: 'press', shot: 'screenshot', take_screenshot: 'screenshot', evaluate: 'eval', tab: 'tabs', navigate_back: 'back' };

const BOOLEAN = new Set(['send', 'qr', 'new', 'full', 'submit', 'append', 'all', 'xml', 'replace', 'gone', 'third-party', 'labels', 'json', 'reset', 'follow', 'off', 'key']);

function parseArgs(argv: string[]): Args {
  const a: Args = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const x = argv[i];
    if (x.startsWith('--') && x.length > 2) {
      const [k, v] = x.slice(2).split(/=(.*)/s);
      if (v !== undefined) a[k] = v;
      else if (BOOLEAN.has(k)) a[k] = true;
      else a[k] = argv[++i];
    } else a._.push(x);
  }
  return a;
}

const num = (v: any) => (v === undefined ? undefined : Number(v));
const selector = (a: Args) => ({ text: a.text, id: a.id, desc: a.desc, index: num(a.index) });

async function main(argv: string[]) {
  const config = loadConfig();
  let [group, cmd, ...rest] = argv;
  const a = parseArgs(rest);
  const out = (s: string) => process.stdout.write(s.endsWith('\n') ? s : s + '\n');

  switch (group) {
    case undefined: case 'help': case '--help': case '-h':
      return out(HELP);

    case 'browser': {
      const s = session(config);
      cmd = ALIASES[cmd] ?? cmd;
      const run = (c: string, args: Record<string, unknown> = {}) => browser(config, s, c, args);
      switch (cmd) {
        case 'open': return out(await (await run('open', { url: need(a._[0], 'a URL'), new: a.new })).text());
        case 'snapshot': return out(await (await run('snapshot', { depth: num(a.depth) })).text());
        case 'click': return out(await (await run('click', { ref: a._[0], text: a.text, selector: a.selector })).text());
        case 'type': return out(await (await run('type', { ref: need(a._[0], 'a ref'), text: need(a._.slice(1).join(' ') || undefined, 'the text'), submit: a.submit, append: a.append })).text());
        case 'press': return out(await (await run('press', { key: need(a._[0], 'a key') })).text());
        case 'back': return out(await (await run('back')).text());
        case 'wait': return out(await (await run('wait', { text: a.text, ms: num(a.ms) })).text());
        case 'eval': return out(await (await run('eval', { js: need(a._.join(' ') || undefined, 'JavaScript') })).text());
        case 'screenshot': {
          const png = Buffer.from(await (await run('screenshot', { full: a.full })).arrayBuffer());
          return out(saveShot(config, 'browser', png, 'png'));
        }
        case 'tabs': {
          const [verb, n] = a._;
          const args = verb === 'select' ? { select: need(n, 'a tab number') } : verb === 'close' ? { close: need(n, 'a tab number') } : verb === 'new' ? { new: true } : {};
          return out(await (await run('tabs', args)).text());
        }
      }
      break;
    }

    case 'phone': {
      const text = async (c: string, args: Record<string, unknown> = {}) => {
        const { result } = await phone(config, c, args);
        out(typeof result === 'string' ? result : JSON.stringify(result, null, 1));
      };
      switch (cmd) {
        case 'screenshot': {
          const scale = num(a.scale) ?? 1;
          const { result, body } = await phone(config, 'screenshot', { scale, format: 'jpg', quality: 80 });
          const file = saveShot(config, 'phone', body!, result.format === 'png' ? 'png' : 'jpg');
          return out(`${file}\n${result.width}x${result.height}${scale < 1 ? ` (scaled by ${scale}: divide image coordinates by ${scale} for taps)` : ''}`);
        }
        case 'ui': return text('ui', { all: a.all, xml: a.xml });
        case 'tap': return text('tap', { x: num(a._[0]), y: num(a._[1]), ...selector(a) });
        case 'longpress': return text('longpress', { x: num(a._[0]), y: num(a._[1]), ...selector(a), ms: num(a.ms) });
        case 'swipe': {
          const [d, ...xy] = a._;
          return text('swipe', /^\d/.test(d ?? '') ? { x1: num(d), y1: num(xy[0]), x2: num(xy[1]), y2: num(xy[2]), ms: num(a.ms) } : { direction: need(d, 'a direction or coordinates'), ms: num(a.ms) });
        }
        case 'type': return text('text', { text: need(a._.join(' ') || undefined, 'the text'), replace: a.replace });
        case 'paste': return text('paste', { text: need(a._.join(' ') || undefined, 'the text') });
        case 'clear': return text('clear');
        case 'key': return text('key', { key: need(a._[0], 'a key') });
        case 'wait': return text('wait', { ...selector(a), gone: a.gone, timeout: num(a.timeout) ?? 10_000 });
        case 'launch': return text('launch', { package: a._[0], activity: a.activity, url: a.url });
        case 'current': return text('current');
        case 'info': return text('info');
        case 'packages': {
          const { result } = await phone(config, 'packages', { thirdParty: a['third-party'], filter: a.filter, labels: a.labels });
          return out(result.map((p: any) => `${p.package} ${p.version ?? ''} (${p.versionCode})${p.label ? ` "${p.label}"` : ''}${p.system ? ' system' : ''}`).join('\n') || 'none');
        }
        case 'appinfo': return text('appinfo', { package: need(a._[0], 'a package') });
        case 'uninstall': return text('uninstall', { package: need(a._[0], 'a package') });
        case 'forcestop': return text('forcestop', { package: need(a._[0], 'a package') });
        case 'install': {
          const file = need(a._[0], 'an APK');
          const r = await relay(config, 'POST', `/phone/install?name=${encodeURIComponent(path.basename(file))}`, fs.readFileSync(file));
          const { result } = await r.json();
          return out(typeof result === 'string' ? result : JSON.stringify(result));
        }
        case 'logcat': {
          const { body } = await phone(config, 'logcat', { package: a.package, level: a.level, grep: a.grep, lines: num(a.lines), since: a.since, tags: a.tags?.split(',') });
          let log = body ? body.toString('utf8') : '';
          if (log.length > 80_000) log = '…(older lines cut; narrow the query)\n' + log.slice(-80_000);
          const last = log.split('\n').reverse().find(l => /^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}/.test(l));
          return out(`${log || '(no lines)'}\n\ncursor: ${last ? last.slice(0, 23) : a.since ?? ''}`);
        }
      }
      break;
    }

    case 'desktop':
      if (cmd === 'screenshot') return out(saveShot(config, 'desktop', desktopShot(config), 'png'));
      break;

    case 'show':
      await relay(config, 'POST', `/show?url=${encodeURIComponent(need(cmd, 'a URL'))}`);
      return out('ok');

    case 'close':
      return out(await (await relay(config, 'POST', `/close?session=${encodeURIComponent(session(config).id)}`)).text());

    case 'status': {
      const s = await (await relay(config, 'GET', '/status')).json();
      const me = session(config);
      const cur = s.browser.phones.find((p: any) => p.current);
      out([
        `browser: ${cur ? `connected (${cur.app}, ${Object.keys(cur.tabs).length} tabs)` : 'not connected'}`,
        `device:  ${s.device.connected ? `connected (${s.device.device}, Android ${s.device.android})` : 'not connected'}`,
        `session: ${me.id}${s.sessions[me.id] ? ` (group ${s.sessions[me.id].group}, "${s.sessions[me.id].title}")` : ' (no tabs yet)'}`,
        `sessions: ${Object.keys(s.sessions).length}`,
      ].join('\n'));
      return;
    }

    case 'pair': {
      const a2 = parseArgs(argv.slice(1));
      const link = pairingLink(pairing(a2.box));
      if (a2.send) {
        await phone(config, 'launch', { url: link });
        return out('sent: confirm the pairing on the phone');
      }
      out(link);
      if (a2.qr) {
        const r = spawnSync('qrencode', ['-t', 'ANSIUTF8', link], { stdio: ['ignore', 'inherit', 'inherit'] });
        if (r.error) throw new OttoError('qrencode is not installed (apt install qrencode)');
      }
      return;
    }

    case 'clip': {
      if (cmd === undefined) throw new OttoError('missing a clip id (see otto help)');
      if (cmd === 'list' || cmd === 'ls') {
        const list = await (await relay(config, 'GET', `/clips?limit=${num(a.limit) ?? 20}`)).json();
        return out(list.map((c: any) => `${c.id}  ${when(c.created)}  ${c.images.length} image${c.images.length === 1 ? '' : 's'}  ${from(c.source)}${c.text ? `  "${firstLine(c.text)}"` : ''}`).join('\n') || 'no clips');
      }
      if (cmd === 'rm' || cmd === 'delete') {
        await relay(config, 'DELETE', `/clips/${encodeURIComponent(need(a._[0], 'a clip id'))}`);
        return out('deleted');
      }
      return out(clipText(await (await relay(config, 'GET', `/clips/${encodeURIComponent(cmd)}`)).json()));
    }

    case 'bot': case 'bots':
      return bot(cmd, a, out);

    case 'artifact': {
      // The artifact hub's own CLI, until it moves into hub/artifacts.
      const r = spawnSync('artifact', argv.slice(1), { stdio: 'inherit' });
      if (r.error) throw new OttoError("the artifact command isn't installed on this box");
      process.exitCode = r.status ?? 1;
      return;
    }
  }
  throw new OttoError(`unknown command: otto ${[group, cmd].filter(Boolean).join(' ')} (see otto help)`);
}

/** A clip, as an agent reads it: the text, the artifact and its notes, the images' paths. */
function clipText(c: any): string {
  const lines = [`Otto clip ${c.id}, ${when(c.created)}, from ${from(c.source)}`];
  if (c.text) lines.push('', c.text.trimEnd());
  const s = c.source ?? {};
  if (s.kind === 'artifact') {
    lines.push('', `Artifact: ${s.title || s.id}${s.version ? ` (v${s.version})` : ''}`);
    if (s.url) lines.push(`Link: ${s.url}`);
    for (const n of s.notes ?? []) lines.push(`- ${n.where ? `${n.where}: ` : ''}${n.text}`);
  }
  if (c.images.length) {
    lines.push('', `${c.images.length === 1 ? 'One image' : `${c.images.length} images`}: open ${c.images.length === 1 ? 'it' : 'each one'} with your image or file tool to see what the user means.`);
    for (const i of c.images) lines.push(`${path.join(c.dir, i.file)}  (${i.width}x${i.height})`);
  }
  return lines.join('\n');
}

function from(s: any): string {
  if (s?.kind === 'artifact') return `the artifact "${s.title || s.id}"`;
  if (s?.kind === 'screen') return `the phone's screen${s.label || s.app ? ` (${s.label ? `${s.label}, ` : ''}${s.app ?? ''})`.replace(', )', ')') : ''}`;
  return 'the phone';
}

const when = (iso: string) => new Date(iso).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' });
const firstLine = (t: string) => { const l = t.trim().split('\n')[0]; return l.length > 60 ? l.slice(0, 59) + '…' : l; };

function need<T>(v: T | undefined, what: string): T {
  if (v === undefined || v === '') throw new OttoError(`missing ${what} (see otto help)`);
  return v;
}

main(process.argv.slice(2)).catch(e => {
  process.stderr.write(`otto: ${e instanceof OttoError ? e.message : e.stack ?? e}\n`);
  process.exitCode = 1;
});
