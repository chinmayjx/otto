// Files: the devbox's home directory, through devbox-hub's file API (the box's hub).
//
//   #files                               the home folder
//   #files?path=<folder>                 a folder; each one is a history entry, so Back walks up the way you came
//   #file?path=<file>                    a file in the viewer

import { downCard, orDown } from './devbox.js';
import { config, app, ask, close, copy, esc, icon, native, row, send, sheet, showMarkdown, showPages, showText, size, toast, viewerShell } from './ui.js';

const HUB = config.hub;
const fs = (what, path, extra = '') => `${HUB}/api/fs/${what}?path=${encodeURIComponent(path)}${extra}`;
const UNREACHABLE = 'Can\'t reach the devbox. Is Tailscale on?';

let home = null;
let showHidden = localStorage.getItem('files.hidden') === '1';
let folderNow = null; // the folder on screen

// The buttons are in the app's top bar (Screen.java): it is told which to show, and calls ccmFiles on a tap.
function syncBar() {
  if (native) send('filesBar', { up: !!folderNow?.parent, home: !!home && home !== folderNow?.path, hidden: showHidden });
}

window.ccmFiles = action => {
  if (action === 'home') return go(home || '');
  if (!folderNow) return;
  if (action === 'up') go(folderNow.parent);
  else if (action === 'hidden') {
    showHidden = !showHidden;
    localStorage.setItem('files.hidden', showHidden ? '1' : '0');
    draw(folderNow);
    syncBar();
  }
};

function when(t) {
  const d = new Date(t * 1000), now = new Date();
  return d.toDateString() === now.toDateString() ? d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString([], { day: 'numeric', month: 'short', ...(d.getFullYear() !== now.getFullYear() && { year: 'numeric' }) });
}

/**
 * A request to devbox-hub. While the devbox is off, a card in el says so (the panel answers at once; the request
 * itself would just hang); once it is back the request goes again, retried for a while as its services come up.
 */
async function hub(url, el, opts) {
  for (let waking = 0; ; ) {
    let r;
    try {
      r = await orDown(fetch(url, opts));
    } catch (e) {
      if (!waking || waking++ > 40) throw e;
      el.innerHTML = '<div class="stopped-card"><b>Starting up…</b>The devbox is almost ready.</div>';
      await new Promise(ok => setTimeout(ok, 3000));
      continue;
    }
    if (!r.down) return r.res;
    await downCard(el, r.down);
    waking = 1;
  }
}

const ext = name => (name.includes('.') ? name.split('.').pop() : '').slice(0, 4).toLowerCase();

// --- Folders ----------------------------------------------------------------------------------------------

export async function folder(params) {
  app.className = '';
  app.innerHTML = `<div class="pathbar"><div class="crumbs"></div></div><div class="scroll"><div class="rows"><p class="empty">Loading…</p></div></div>`;
  let d;
  try {
    const r = await hub(fs('list', params.path || ''), app.querySelector('.rows'));
    if (r.status === 409) return location.replace(`#file?path=${encodeURIComponent(params.path)}`); // a file after all
    if (!r.ok) throw new Error(r.status === 403 ? 'Outside the home folder' : 'Not found');
    d = await r.json();
  } catch (e) {
    app.querySelector('.rows').innerHTML = `<p class="empty">${esc(e.message === 'Failed to fetch' ? UNREACHABLE : e.message)}</p>`;
    folderNow = null; // nothing here to go up from; Home still leads back
    syncBar();
    return;
  }
  home = d.home;
  crumbs(d.path);
  folderNow = d;
  syncBar();
  app.querySelector('.pathbar').onclick = e => {
    const b = e.target.closest('[data-path]');
    if (b) go(b.dataset.path);
  };
  app.querySelector('.rows').onclick = e => {
    const r = e.target.closest('[data-path]');
    if (!r) return;
    if (r.dataset.dir) go(r.dataset.path);
    else if (native) send('open', { hash: `file?path=${encodeURIComponent(r.dataset.path)}` });
    else location.hash = `file?path=${encodeURIComponent(r.dataset.path)}`;
  };
  draw(d);
}

export const post = (path, body = {}) => fetch(HUB + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

const go = path => { location.hash = `files?path=${encodeURIComponent(path)}`; };

function crumbs(path) {
  const inHome = path === home || path.startsWith(home + '/');
  const parts = (inHome ? path.slice(home.length) : path).split('/').filter(Boolean);
  let acc = inHome ? home : '';
  const links = inHome ? [`<button data-path="${esc(home)}">~</button>`] : [];
  for (const p of parts) { acc += '/' + p; links.push(`<button data-path="${esc(acc)}">${esc(p)}</button>`); }
  const c = app.querySelector('.crumbs');
  c.innerHTML = links.join('<span>/</span>');
  c.scrollLeft = 1e6;
}

function draw(d) {
  const entries = d.entries.filter(e => showHidden || !e.name.startsWith('.'));
  const rows = app.querySelector('.rows');
  if (!entries.length) { rows.innerHTML = `<p class="empty">${d.entries.length ? 'Only hidden files here' : 'Empty folder'}</p>`; return; }
  rows.innerHTML = entries.map(e => {
    const p = (d.path === '/' ? '' : d.path) + '/' + e.name;
    const ic = e.dir ? `<span class="ic folder">${icon('folder')}</span>`
      : `<span class="ic"><span class="ext k-${esc(e.kind)}">${esc(ext(e.name)) || '·'}</span></span>`;
    return `<button class="row" data-path="${esc(p)}" ${e.dir ? 'data-dir="1"' : ''}>${ic}
      <div><b>${esc(e.name)}</b><small>${e.dir ? when(e.mtime) : `${size(e.size)} · ${when(e.mtime)}`}</small></div></button>`;
  }).join('');
}

// --- One file ---------------------------------------------------------------------------------------------

export async function fileViewer(path) {
  const name = path.split('/').pop();
  const download = () => {
    const url = fs('raw', path, '&download=1');
    if (native) send('download', { url, name });
    else location.href = url;
  };
  const wrap = viewerShell(name, '', {
    close: () => close(`files?path=${encodeURIComponent(path.slice(0, path.lastIndexOf('/')))}`),
    download,
    more: () => sheet(`<h4>${esc(path)}</h4>` + row('copy', icon('copy'), 'Copy path', 'For a chat with Claude'),
      () => copy(path, 'Path copied')),
  });
  let f;
  try {
    const r = await hub(fs('info', path), wrap);
    if (!r.ok) throw new Error(r.status === 403 ? 'Outside the home folder' : 'Not found');
    f = await r.json();
  } catch (e) {
    wrap.innerHTML = `<p class="empty">${esc(e.message === 'Failed to fetch' ? UNREACHABLE : e.message)}</p>`;
    return;
  }
  const raw = fs('raw', path);
  switch (f.kind) {
    case 'pdf':
    case 'office': {
      if (f.kind === 'office') wrap.innerHTML = '<p class="empty">Making a preview…</p>';
      const r = await fetch(fs('pages', path)).catch(() => null);
      if (!r?.ok) { wrap.innerHTML = `<p class="empty">${f.kind === 'office' ? 'Couldn\'t make a preview (is the rendering service up?)' : 'Couldn\'t open this PDF'}</p>`; return; }
      return showPages(wrap, (await r.json()).pages, n => fs('page', path, `&n=${n}`));
    }
    case 'image':
      return showPages(wrap, [[0, 0]], () => raw);
    case 'video':
      wrap.innerHTML = `<video class="media" src="${esc(raw)}" controls playsinline></video>`;
      return;
    case 'audio':
      wrap.innerHTML = `<audio class="media" src="${esc(raw)}" controls></audio>`;
      return;
    case 'html':
      wrap.innerHTML = `<iframe class="live" sandbox="allow-scripts allow-popups allow-forms allow-modals" src="${esc(raw)}"></iframe>`;
      return;
    case 'markdown': {
      showMarkdown(wrap, await (await fetch(raw)).text());
      return;
    }
    case 'text': {
      const cut = f.size > 2_000_000;
      const text = await (await fetch(raw, cut ? { headers: { Range: 'bytes=0-1999999' } } : {})).text();
      showText(wrap, text, f.name, cut ? `The first 2 MB of ${size(f.size)}` : '');
      return;
    }
    default:
      wrap.innerHTML = `<p class="empty">No preview for this file (${size(f.size)}).<br>Download it to open it.</p>`;
  }
}
