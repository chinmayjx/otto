// Shared pieces of the app's UI: the native bridge, icons, toasts, bottom sheets, and the viewer's shell and
// zoomable pages (used for artifacts and files alike).

export const app = document.getElementById('app');
export const native = window.CCMNative; // a WebMessageListener only this origin gets (the app's AppWeb)

// Where the services are (config.js; in the Android app, from pairing). No host is written into this code.
const C = window.OTTO_CONFIG || {};
const trim = u => String(u || '').replace(/\/+$/, '');
const hubHost = (() => { try { return new URL(C.hub).hostname; } catch { return ''; } })();
export const config = {
  hub: trim(C.hub),
  artifacts: trim(C.artifacts),
  publicArtifacts: trim(C.publicArtifacts || C.artifacts),
  panel: trim(C.panel),
  desktop: C.desktop || (hubHost ? `wss://${hubHost}:8447/websockify` : ''),
  terminal: C.terminal || (hubHost ? `wss://${hubHost}:8451/` : ''),
};
export const send = (op, data = {}) => native?.postMessage(JSON.stringify({ op, ...data }));
export const esc = s => String(s ?? '').replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`);

const ICON = {
  back: '<path d="M15 18 9 12l6-6"/>',
  download: '<path d="M12 4v11m-5-5 5 5 5-5M5 20h14"/>',
  more: '<circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/>',
  latest: '<path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  share: '<circle cx="16.5" cy="6" r="2"/><circle cx="7.5" cy="12" r="2"/><circle cx="16.5" cy="18" r="2"/><path d="m9.2 11 5.6-3.9m-5.6 5.8 5.6 3.9"/>',
  link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
  open: '<path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/>',
  trash: '<path d="M4 7h16M9 7V4h6v3m-8 0 1 13h8l1-13"/>',
  lock: '<rect x="5" y="11" width="14" height="9" rx="1.5"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  folder: '<path d="M3.5 7A2 2 0 0 1 5.5 5h3.6l2 2h7.4a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"/>',
  file: '<path d="M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"/><path d="M14 3v5h5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  up: '<path d="M12 19V5m-6 6 6-6 6 6"/>',
  home: '<path d="M4 10.5 12 4l8 6.5V19a1 1 0 0 1-1 1h-4.5v-6h-5v6H5a1 1 0 0 1-1-1z"/>',
  eye: '<path d="M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="1.5"/><path d="M16 8V5.5A1.5 1.5 0 0 0 14.5 4h-9A1.5 1.5 0 0 0 4 5.5v9A1.5 1.5 0 0 0 5.5 16H8"/>',
};
export const icon = k => `<svg class="i" viewBox="0 0 24 24">${ICON[k]}</svg>`;

export function ago(t) {
  const m = (Date.now() / 1000 - t) / 60;
  if (m < 60) return `${Math.max(1, m | 0)}m ago`;
  if (m < 1440) return `${m / 60 | 0}h ago`;
  if (m < 1440 * 30) return `${m / 1440 | 0}d ago`;
  return new Date(t * 1000).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

export const size = n => n == null ? '' : n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(n < 10240 ? 1 : 0)} KB`
  : n < 1073741824 ? `${(n / 1048576).toFixed(1)} MB` : `${(n / 1073741824).toFixed(1)} GB`;

let toastTimer;
export function toast(text) {
  const t = document.getElementById('toast');
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), Math.max(2200, text.length * 60)); // long ones (errors) stay to be read
}

export async function copy(text, done) {
  try {
    await navigator.clipboard.writeText(text);
    toast(done);
  } catch {
    toast('Couldn\'t copy');
  }
}

/** A bottom sheet; onClick(act, button) for its [data-act] rows; onDismiss when it goes away without one. */
export function sheet(html, onClick, onDismiss) {
  const scrim = document.createElement('div');
  const box = document.createElement('div');
  scrim.className = 'scrim';
  box.className = 'sheet';
  box.innerHTML = '<div class="grab"></div>' + html;
  document.body.append(scrim, box);
  requestAnimationFrame(() => { scrim.classList.add('open'); box.classList.add('open'); });
  const dismiss = () => {
    scrim.classList.remove('open');
    box.classList.remove('open');
    setTimeout(() => { scrim.remove(); box.remove(); }, 250);
  };
  scrim.onclick = () => { dismiss(); onDismiss?.(); };
  box.onclick = e => { const b = e.target.closest('[data-act]'); if (b) { dismiss(); onClick(b.dataset.act, b); } };
}

/** A confirmation as a bottom sheet; resolves true if the action was chosen. */
export function ask(title, sub, action, cls = 'danger') {
  return new Promise(resolve => {
    let chosen = false;
    sheet(`<h4>${esc(title)}</h4><p class="ask">${esc(sub)}</p><div class="ask-btns">
      <button class="btn" data-act="cancel">Cancel</button><button class="btn ${cls}" data-act="ok">${esc(action)}</button></div>`,
    act => { chosen = act === 'ok'; resolve(chosen); }, () => { if (!chosen) resolve(false); });
  });
}

export const row = (act, ic, title, sub, cls = '') =>
  `<button class="row ${cls}" data-act="${esc(act)}"><span class="ic">${ic}</span><div><b>${esc(title)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}</div></button>`;

/** The viewer leaves (the overlay closes; in a plain browser, history goes back). */
export function close(fallback) {
  if (native) send('close');
  else if (history.length > 1) history.back();
  else location.hash = fallback;
}

/**
 * The viewer's frame: one bar (back, title, an optional label button, download, ⋮) over .wrap.
 * on: {close, label, download, more}; returns .wrap.
 */
export function viewerShell(title, label, on) {
  app.className = 'viewer';
  app.innerHTML = `<div class="bar">
      <button class="icon-btn" data-act="close" aria-label="Back">${icon('back')}</button>
      <div class="vt"><b></b><button data-act="label"></button></div>
      <button class="icon-btn" data-act="download" aria-label="Download" ${on.download ? '' : 'hidden'}>${icon('download')}</button>
      <button class="icon-btn" data-act="more" aria-label="More" ${on.more ? '' : 'hidden'}>${icon('more')}</button>
    </div><div class="wrap"><p class="empty">Loading…</p></div>`;
  app.querySelector('.vt b').textContent = title;
  app.querySelector('[data-act=label]').textContent = label || '';
  app.querySelector('.bar').onclick = e => {
    const act = e.target.closest('[data-act]')?.dataset.act;
    if (act && on[act]) on[act]();
  };
  return app.querySelector('.wrap');
}

/** Pages ([[w, h], ...] with urlOf(n)) or a single image, zoomable; returns the cleanup. */
export function showPages(wrap, sizes, urlOf) {
  const imgs = sizes.map(([w, h], i) => `<img src="${esc(urlOf(i + 1))}" ${w ? `style="aspect-ratio:${w}/${h}"` : ''} alt="" ${i > 2 ? 'loading="lazy"' : ''}>`).join('');
  wrap.innerHTML = `<div class="pages"><div class="pages-in">${imgs}</div></div><div class="pager"></div><div class="zoom" hidden></div>`;
  return zoomable(wrap);
}

// Pinch (and ctrl + wheel) zoom, double tap for 2.5x. The pages are SVGs, so after each gesture the browser
// draws them again at the new size, sharp. During the gesture only a GPU transform changes, once per frame.
function zoomable(wrap) {
  const scroller = wrap.querySelector('.pages'), inner = wrap.querySelector('.pages-in');
  const pager = wrap.querySelector('.pager'), label = wrap.querySelector('.zoom');
  const imgs = [...inner.querySelectorAll('img')];
  const MIN = 1, MAX = 5;
  const clamp = z => Math.min(MAX, Math.max(MIN, z));
  let zoom = 1, pinch = null, frame = 0, lastTap = 0;

  const local = (x, y) => { const r = scroller.getBoundingClientRect(); return [x - r.left, y - r.top]; };
  const showZoom = z => { label.hidden = z < 1.05; label.textContent = `${Math.round(z * 100)}%`; };

  // Keeps content point (ox, oy) (in px at the old zoom) at screen point (cx, cy).
  function commit(z, ox, oy, cx, cy) {
    const s = z / zoom;
    zoom = z;
    inner.style.transform = '';
    inner.style.width = `${z * 100}%`;
    scroller.scrollLeft = ox * s - cx;
    scroller.scrollTop = oy * s - cy;
    showZoom(z);
    page();
  }

  function onStart(e) {
    if (e.touches.length === 2) {
      const [a, b] = e.touches;
      const [cx, cy] = local((a.clientX + b.clientX) / 2, (a.clientY + b.clientY) / 2);
      pinch = { d: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), ox: scroller.scrollLeft + cx, oy: scroller.scrollTop + cy, cx, cy, s: 1 };
      lastTap = 0;
    } else if (e.touches.length === 1) {
      const now = Date.now();
      if (now - lastTap < 300) {
        const [cx, cy] = local(e.touches[0].clientX, e.touches[0].clientY);
        commit(zoom > 1.2 ? 1 : 2.5, scroller.scrollLeft + cx, scroller.scrollTop + cy, cx, cy);
        e.preventDefault();
        lastTap = 0;
      } else lastTap = now;
    }
  }
  function onMove(e) {
    if (!pinch || e.touches.length !== 2) return;
    e.preventDefault();
    const [a, b] = e.touches;
    pinch.s = clamp(zoom * Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY) / pinch.d) / zoom;
    [pinch.cx2, pinch.cy2] = local((a.clientX + b.clientX) / 2, (a.clientY + b.clientY) / 2);
    if (frame) return;
    frame = requestAnimationFrame(() => {
      frame = 0;
      if (!pinch) return;
      const { s, ox, oy, cx2, cy2 } = pinch;
      // Content point (ox, oy) follows the fingers: translate + scale about the layer's top left.
      inner.style.transform = `translate(${cx2 + scroller.scrollLeft - s * ox}px, ${cy2 + scroller.scrollTop - s * oy}px) scale(${s})`;
      showZoom(zoom * s);
    });
  }
  function onEnd(e) {
    if (!pinch || e.touches.length >= 2) return;
    const p = pinch;
    pinch = null;
    cancelAnimationFrame(frame);
    frame = 0;
    commit(zoom * p.s, p.ox, p.oy, p.cx2 ?? p.cx, p.cy2 ?? p.cy);
  }
  function onWheel(e) {
    if (!e.ctrlKey) return;
    e.preventDefault();
    const [cx, cy] = local(e.clientX, e.clientY);
    commit(clamp(zoom * Math.exp(-e.deltaY / 200)), scroller.scrollLeft + cx, scroller.scrollTop + cy, cx, cy);
  }

  // "3 / 12": the page at the top quarter of the view.
  let paged = 0;
  function page() {
    if (imgs.length < 2) { pager.hidden = true; return; }
    if (paged) return;
    paged = requestAnimationFrame(() => {
      paged = 0;
      const line = scroller.getBoundingClientRect().top + scroller.clientHeight / 4;
      const n = imgs.findIndex(i => i.getBoundingClientRect().bottom > line) + 1 || imgs.length;
      pager.textContent = `${n} / ${imgs.length}`;
    });
  }

  scroller.addEventListener('touchstart', onStart, { passive: false });
  scroller.addEventListener('touchmove', onMove, { passive: false });
  scroller.addEventListener('touchend', onEnd);
  scroller.addEventListener('touchcancel', onEnd);
  scroller.addEventListener('wheel', onWheel, { passive: false });
  scroller.addEventListener('scroll', page, { passive: true });
  page();
  return () => cancelAnimationFrame(frame);
}

// --- Text and Markdown, in the file viewer and the artifact viewer ---------------------------------------------

/** Markdown, rendered here (vendor/marked.esm.js). It may hold any HTML: shown in a frame with no scripts and no access to this page. */
export async function showMarkdown(wrap, text) {
  const { marked } = await import('./vendor/marked.esm.js');
  const frame = document.createElement('iframe');
  frame.className = 'live doc';
  frame.sandbox = 'allow-popups';
  frame.srcdoc = `<!doctype html><meta name="viewport" content="width=device-width"><base target="_blank"><style>${DOC_CSS}</style><article>${marked.parse(text)}</article>`;
  wrap.replaceChildren(frame);
}

/** Plain text or code, highlighted by the file name's extension when it's a language highlight.js knows. */
export function showText(wrap, text, name, note = '') {
  wrap.innerHTML = `<div class="scroll">${note ? `<p class="note">${esc(note)}</p>` : ''}<pre class="code"><code></code></pre></div>`;
  const code = wrap.querySelector('code');
  code.textContent = text;
  const e = name.includes('.') ? name.split('.').pop().toLowerCase() : '';
  if (text.length < 400_000) highlight(code, e);
}

const LANG = { py: 'python', js: 'javascript', mjs: 'javascript', ts: 'typescript', tsx: 'typescript', jsx: 'javascript', sh: 'bash',
  yml: 'yaml', rs: 'rust', kt: 'kotlin', rb: 'ruby', h: 'c', hpp: 'cpp', cc: 'cpp', md: 'markdown' };

async function highlight(node, e) {
  try {
    const { default: hljs } = await import('./vendor/highlight.min.js');
    const lang = LANG[e] || e;
    if (!lang || !hljs.getLanguage(lang)) return; // unknown: plain text rather than a wrong guess
    node.className = `language-${lang}`;
    hljs.highlightElement(node);
  } catch { /* plain text */ }
}

const DOC_CSS = `
  :root { color-scheme: dark; }
  body { margin: 0; background: #000; color: #edeef0; font: 15px/1.6 system-ui, Roboto, sans-serif; }
  article { padding: 4px 16px 40px; overflow-wrap: anywhere; }
  a { color: #ff6a5f; }
  h1, h2, h3 { line-height: 1.3; margin: 1.2em 0 .5em; }
  h1 { font-size: 1.5em; } h2 { font-size: 1.25em; } h3 { font-size: 1.1em; }
  code { font: 13px/1.5 ui-monospace, Menlo, monospace; background: #17171a; padding: 1px 4px; border-radius: 3px; }
  pre { background: #17171a; padding: 10px 12px; border-radius: 4px; overflow-x: auto; }
  pre code { padding: 0; background: none; }
  table { border-collapse: collapse; display: block; overflow-x: auto; }
  th, td { border: 1px solid #2e2e33; padding: 4px 8px; }
  blockquote { margin: 0; padding-left: 12px; border-left: 3px solid #2e2e33; color: #8e949c; }
  img { max-width: 100%; }
`;
