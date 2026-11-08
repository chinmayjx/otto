// Artifacts, from the artifact hub (otto/hub/artifacts): the list, and one artifact in the viewer. The viewer
// shows the latest version unless a version was named (#artifact/<id>/v<n>: a chat card, or picked here). Links
// it hands out (share, copy) are the artifact's own, which always open its latest version.

import { config, ago, app, ask, close, copy, esc, icon, native, row, send, sheet, showMarkdown, showPages, showText, toast, viewerShell } from './ui.js';

const HUB = config.artifacts; // over Tailscale: no login of its own
const PUBLIC = config.publicArtifacts; // the links handed out (share, copy)

async function api(path, opts = {}) {
  const r = await fetch(HUB + path, opts.body ? { ...opts, headers: { 'content-type': 'application/json' } } : opts);
  if (!r.ok) throw new Error(`${r.status}`);
  return r.json();
}

const UNREACHABLE = 'Can\'t reach the artifact hub. Is Tailscale on?';

// --- The list ---------------------------------------------------------------------------------------------

let list = [];
let query = '';

export function artifactList() {
  app.className = '';
  app.innerHTML = `<div class="scroll">
    <label class="search">${icon('search')}<input type="search" placeholder="Search artifacts" value="${esc(query)}"></label>
    <div class="grid"></div></div>`;
  const input = app.querySelector('input');
  input.oninput = () => { query = input.value; draw(); };
  app.querySelector('.grid').onclick = e => {
    const card = e.target.closest('[data-aid]');
    if (!card) return;
    if (native) send('open', { hash: `artifact/${card.dataset.aid}` });
    else location.hash = `artifact/${card.dataset.aid}`;
  };
  draw();
  refreshArtifacts();
}

export async function refreshArtifacts() {
  try {
    list = await api('/api/artifacts?limit=500');
    draw();
  } catch {
    const grid = app.querySelector('.grid');
    if (!list.length && grid) { grid.className = ''; grid.innerHTML = `<p class="empty">${UNREACHABLE}</p>`; }
  }
}

function draw() {
  const grid = app.querySelector('.grid');
  if (!grid) return;
  const q = query.trim().toLowerCase();
  const shown = list.filter(a => !q || a.title.toLowerCase().includes(q));
  grid.className = 'grid';
  grid.innerHTML = shown.map(a => `<button class="card" data-aid="${esc(a.artifact_id)}">
      <div class="th" style="background-image:url('${HUB}/v/${esc(a.latest_version)}/thumb')"></div>
      <b>${esc(a.title)}</b><small>${esc(a.label || a.type)} · v${a.versions} · ${ago(a.updated)}</small></button>`).join('')
    || (list.length ? '<p class="empty">Nothing matches.</p>' : '');
}

// --- One artifact -----------------------------------------------------------------------------------------

/** {aid, n}: the artifact's version n, or its latest (n null). {vid}: one version, from a card posted before
 * artifact links. v.pinned: a version was named, so the viewer stays on it. */
async function load(ref) {
  const enc = encodeURIComponent;
  if (ref.vid) return { ...await api(`/api/versions/${enc(ref.vid)}`), pinned: true };
  try {
    return { ...await api(`/api/artifacts/${enc(ref.aid)}/versions/${ref.n || 'latest'}`), pinned: !!ref.n };
  } catch (e) {
    if (e.message !== '404' || ref.n) throw e;
    return { ...await api(`/api/versions/${enc(ref.aid)}`), pinned: true }; // a share link from before: /p/<version id>
  }
}

/** The link to what's on screen: the artifact (its latest) or, when pinned, this version. */
const link = (host, scope, v) => `${host}/${scope}/${v.artifact_id}${v.pinned ? `/v${v.version}` : ''}`;
/** The artifact's own link, for sharing: it follows the latest version. */
const stable = (host, scope, v) => `${host}/${scope}/${v.artifact_id}`;
const files = v => `${HUB}/a/${v.artifact_id}/v${v.version}`; // this version's files, whichever link it came from

export async function artifactViewer(ref) {
  let v;
  const wrap = viewerShell('', '', { close: () => close('artifacts') });
  try {
    v = await load(ref);
  } catch (e) {
    wrap.innerHTML = `<p class="empty">${e.message === '404' ? 'This artifact was deleted.' : UNREACHABLE}</p>`;
    return;
  }
  const behind = v.pinned && v.version < v.versions.length;
  viewerShell(v.title, v.versions.length > 1 ? `v${v.version}${behind ? ' (not latest)' : ''} ▾` : '', {
    close: () => close('artifacts'),
    label: () => versionsSheet(v),
    download: () => {
      const url = `${files(v)}/download`;
      if (native) send('download', { url, name: v.filename });
      else location.href = url;
    },
    more: () => moreSheet(v),
  });
  const body = app.querySelector('.wrap');
  const file = `${files(v)}/file`;
  if (v.kind === 'html') {
    // The hub's own page in embed mode: the artifact plus the bridge its data needs (window.hub). Framing the file
    // alone would leave the artifact's data unreachable. Not sandboxed here: it's the hub's page, and it runs the
    // artifact in its own sandboxed frame.
    body.innerHTML = `<iframe class="live" allow="midi; microphone; autoplay; fullscreen" src="${files(v)}?embed=1"></iframe>`;
  } else if (v.kind === 'markdown') {
    showMarkdown(body, await (await fetch(file)).text());
  } else if (v.kind === 'text') {
    showText(body, await (await fetch(file)).text(), v.filename);
  } else if (v.kind === 'image') {
    return showPages(body, [[0, 0]], () => file);
  } else if (v.pages?.length) {
    return showPages(body, v.pages, n => `${files(v)}/page/${n}.svg`);
  } else if (v.kind === 'audio' || v.kind === 'video') {
    // the hub's player (embed view): audio and video play in place
    body.innerHTML = `<iframe class="live" allow="autoplay; fullscreen" src="${files(v)}?embed=1"></iframe>`;
  } else {
    body.innerHTML = `<p class="empty">${v.preview === 'failed' ? 'The preview couldn\'t be made.' : 'No preview for this file.'}<br>Download it to open it.</p>`;
  }
}

function versionsSheet(v) {
  const last = v.versions.at(-1);
  sheet('<h4>Versions</h4>'
    + row('latest', icon('latest'), 'Latest', `v${last.version}, and newer ones as they come`, v.pinned ? '' : 'on')
    + [...v.versions].reverse().map(s =>
      row(`v${s.version}`, `v${s.version}`, s.note || `Version ${s.version}`, ago(s.created),
        v.pinned && s.version === v.version ? 'on' : '')).join(''),
  act => {
    const to = act === 'latest' ? `#artifact/${v.artifact_id}` : `#artifact/${v.artifact_id}/${act}`;
    if (to !== `#${link('', 'artifact', v).slice(1)}`) location.replace(to);
  });
}

function moreSheet(v) {
  const n = v.versions.length;
  sheet(`<h4>${esc(v.type)} · v${v.version}${v.public ? ' · shared' : ''}</h4>`
    + row('share', icon('share'), 'Share', v.public ? 'Its public link' : 'Makes a public link anyone can open')
    + (v.public ? row('unshare', icon('lock'), 'Stop sharing', 'The public link stops working') : '')
    + row('copy', icon('link'), 'Copy link', 'Always opens the latest version')
    + row('browser', icon('open'), 'Open in Browser', 'The hub\'s own page')
    + row('delete', icon('trash'), 'Delete', n > 1 ? `All ${n} versions` : 'For good', 'danger'),
  async act => {
    try {
      if (act === 'share') {
        if (!v.public) await api(`/api/artifacts/${v.artifact_id}`, { method: 'PATCH', body: JSON.stringify({ public: true }) });
        v.public = true;
        const url = stable(PUBLIC, 'p', v);
        if (native) send('share', { text: url, title: v.title });
        else copy(url, 'Public link copied');
      } else if (act === 'unshare') {
        await api(`/api/artifacts/${v.artifact_id}`, { method: 'PATCH', body: JSON.stringify({ public: false }) });
        v.public = false;
        toast('No longer shared');
      } else if (act === 'copy') {
        copy(stable(PUBLIC, 'a', v), 'Link copied');
      } else if (act === 'browser') {
        if (native) send('openInBrowser', { url: link(HUB, 'a', v) });
        else window.open(link(PUBLIC, 'a', v));
      } else if (act === 'delete') {
        if (!await ask(`Delete "${v.title}"?`, n > 1 ? `All ${n} versions go, for good.` : 'It goes for good.', 'Delete')) return;
        await api(`/api/artifacts/${v.artifact_id}`, { method: 'DELETE' });
        toast('Deleted');
        close('artifacts');
      }
    } catch {
      toast('That didn\'t work. Is Tailscale on?');
    }
  });
}
