// Desktop: the devbox's VNC desktop (:1) made for a phone held upright (the second screen's Desktop section).
//
// The desktop is fitted to the screen's height and cropped in width. Below it, a minimap of the whole desktop
// marks the slice in view; tap or drag on it to pan. While the phone's keyboard is up the zoom stays and the
// view scrolls instead: to the spot last tapped, or with the scroll pad in the corner.
// The mode button sets the desktop's own resolution (TigerVNC resizes live): Mobile (the default) is portrait,
// twice this screen's free area, shown whole; Desktop is 1600×900, panned with the minimap, or zoomed out to show
// it whole with the zoom button.
// Gestures, as on a phone:
//   tap                      left click
//   one-finger drag          scroll (mouse wheel, both directions), with momentum after a flick
//   long-press, then drag    hold the left button (select text, drag windows)
//   long-press, release      right click
// ⌨ opens the phone's keyboard, with Esc, Tab, Ctrl and arrows above it.
//
// noVNC (vendor/novnc: Ubuntu's novnc 1.3.0, core/ and vendor/ as installed, MPL-2.0) only connects and draws;
// input is ours, on a layer over its canvas. Its websocket is websockify behind `tailscale serve --https=8447`.

import { devboxStatus, downCard } from './devbox.js';
import { app, config } from './ui.js';

const NOVNC = './vendor/novnc';

export async function desktop() {
  const root = app;
  root.className = 'desktop';
  root.innerHTML = `
    <div id="view">
      <div id="target"></div>
      <div id="touch"></div>
      <div id="status">Connecting…</div>
      <div id="down"></div>
      <div id="ptr"></div>
      <div id="pad"><div id="padthumb"></div></div>
    </div>
    <div id="mini"><canvas id="minicv"></canvas><div id="win"></div></div>
    <div id="keys">
      <button data-key="Escape">Esc</button><button data-key="Tab">Tab</button><button id="ctrl" aria-pressed="false">Ctrl</button>
      <button data-key="Left">←</button><button data-key="Up">↑</button><button data-key="Down">↓</button><button data-key="Right">→</button>
      <button data-key="Home">Home</button><button data-key="End">End</button>
    </div>
    <div id="bar">
      <button id="kb" aria-label="Keyboard"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><rect x="2.5" y="6" width="19" height="12" rx="2"/><path d="M6 10h.01M9.5 10h.01M13 10h.01M16.5 10h.01M7.5 14h9"/></svg></button>
      <div id="spacer" style="flex:1"></div>
      <button id="zoom" aria-label="Zoom out: show the whole desktop" aria-pressed="false"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.3 15.3 20.5 20.5M7.5 10.5h6"/><path class="plus" d="M10.5 7.5v6"/></svg></button>
      <button id="hover" aria-label="Hover: a tap only moves the pointer; long-press clicks" aria-pressed="false"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"><path d="M5.5 3.5v15l4-3.8 2.7 5.8 2.6-1.2-2.7-5.7h5.4Z"/></svg></button>
      <button id="mode" aria-label="Switch between mobile and desktop"><svg class="phone" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="6.5" y="2.5" width="11" height="19" rx="2.2"/><path d="M11 18.5h2"/></svg><svg class="monitor" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="4" width="19" height="12.5" rx="1.8"/><path d="M9 20.5h6M12 16.5v4"/></svg><span id="modelabel">Mobile</span></button>
    </div>
    <textarea id="ime" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false"></textarea>`;
  const [{ default: RFB }, { default: KeyTable }, { default: keysyms }] = await Promise.all(
    [`${NOVNC}/core/rfb.js`, `${NOVNC}/core/input/keysym.js`, `${NOVNC}/core/input/keysymdef.js`].map(u => import(u)));

  const $ = id => document.getElementById(id);
  const view = $('view'), touch = $('touch'), ime = $('ime');
  const URL_WS = config.desktop;
  const BTN = { left: 1, right: 4, wheelUp: 8, wheelDown: 16, wheelLeft: 32, wheelRight: 64 };
  const LONG_PRESS = 450, SLOP = 8, STEP = 28; // ms; px before a touch is a drag; px of finger per wheel notch

  let rfb, canvas, fbW = 0, fbH = 0, scale = 1, ox = +(localStorage.getItem('desktop.ox') || 0), oy = 0;
  let x0 = 0, y0 = 0, fitH = 0, lastTapY = 0; // the view's height without the keyboard (the zoom); where the last tap was (desktop px)
  let whole = localStorage.getItem('desktop.whole') === '1'; // Desktop mode zoomed out: the whole desktop, no panning
  let follow = true; // keep the last tap in view while typing; off once the scroll pad has moved the view, on again at the next tap
  root.classList.add('mini');
  const typing = () => root.classList.contains('typing');

  // Connection (reconnects by itself). The panel's status goes out with each attempt: when the devbox is off the
  // websocket just hangs (Tailscale gives no quick error), the panel answers at once.
  function connect() {
    if (rfb && rfb._rfbConnectionState !== 'disconnected') { rfb.dropped = true; rfb.disconnect(); }
    $('target').replaceChildren(); // one canvas only: a leftover one would show unscaled over the live one
    $('status').textContent = 'Connecting…';
    $('status').hidden = false;
    const r = rfb = new RFB($('target'), URL_WS, { shared: true });
    r.scaleViewport = false;
    r.clipViewport = false;
    r.resizeSession = false;
    r.focusOnClick = false;
    r.addEventListener('connect', () => { $('status').hidden = true; sizeTries = 0; setTimeout(requestSize, 300); });
    r.addEventListener('disconnect', () => {
      if (r.dropped || r !== rfb) return;
      $('status').hidden = false;
      $('status').textContent = 'Reconnecting…';
      setTimeout(connect, 1500);
    });
    devboxStatus().then(async st => {
      if (st.state === 'running' || r !== rfb || r._rfbConnectionState === 'connected') return;
      r.dropped = true;
      r.disconnect();
      $('status').hidden = true;
      await downCard($('down'), st); // until it's running again
      $('down').innerHTML = '';
      connect();
    }, () => {});
  }
  connect();

  // Fit the height, crop the width; ox = how far the view is panned, in screen pixels.
  function layout() {
    const c = $('target').querySelector('canvas'), vw = view.clientWidth, vh = view.clientHeight;
    // Not yet (no desktop, or hidden behind another tab): taken as laid out only once it is, so the check below
    // comes back until then.
    if (!c || !c.width || !vw || !vh) return;
    canvas = c; fbW = canvas.width; fbH = canvas.height;
    if (!typing() || !fitH) fitH = vh; // the keyboard doesn't change the zoom
    // Wider than the screen (Desktop): fit the height and pan. About the screen's shape or narrower: show it all.
    // Zoomed out (Desktop mode): show it all whatever its shape.
    const out = whole && root.classList.contains('desk');
    scale = out || fbW * fitH / fbH <= vw * 1.15 ? Math.min(fitH / fbH, vw / fbW) : fitH / fbH;
    const maxOx = Math.max(0, fbW * scale - vw), maxOy = Math.max(0, fbH * scale - vh);
    if (typing() && maxOy && follow) { // keep the spot last tapped (the field being typed into) above the keyboard
      const y = lastTapY * scale;
      if (y - oy > vh - 48 || y < oy) oy = y - vh * 0.6;
    }
    ox = Math.min(Math.max(0, ox), maxOx);
    oy = Math.min(Math.max(0, oy), maxOy);
    const fits = fbW * scale <= vw + 1;
    root.classList.toggle('fits', fits);
    x0 = fits ? (vw - fbW * scale) / 2 : -ox; // where the desktop's left edge is on screen
    y0 = out && !maxOy ? (vh - fbH * scale) / 2 : -oy; // and its top: zoomed out, centred
    canvas.style.transform = `translate(${x0}px, ${y0}px) scale(${scale})`;
    padLayout(vw, vh, maxOx, maxOy);
    miniWindow();
  }
  let shown = false, resizeSoon = 0;
  // The keyboard hidden by Android (its own button, Back) only shows as the page growing back to full height (the
  // hidden field keeps its focus): leave typing then. The page's height, not the view's: the minimap and the keys
  // row come and go with typing too.
  let kbUp = false, fullH = innerHeight;
  const watchKeyboard = () => {
    const h = innerHeight;
    if (!typing()) { fullH = h; kbUp = false; return; }
    if (h > fullH) fullH = h;
    if (h < fullH - 150) kbUp = true;
    else if (kbUp && h > fullH - 60) { ime.blur(); stopTyping(); }
  };
  addEventListener('resize', watchKeyboard);
  visualViewport?.addEventListener('resize', watchKeyboard);
  new ResizeObserver(() => {
    layout();
    const now = view.clientWidth > 0 && view.clientHeight > 0;
    if (now && !shown) setTimeout(requestSize, 300); // back from another tab: the desktop may have been resized meanwhile
    // The view resized (not by the keyboard): ask for a desktop that fits it, once it has settled.
    else if (now && !typing()) { clearTimeout(resizeSoon); resizeSoon = setTimeout(requestSize, 400); }
    shown = now;
  }).observe(view);
  setInterval(() => { const c = $('target').querySelector('canvas'); if (c && (c !== canvas || c.width !== fbW || c.height !== fbH || !c.style.transform)) layout(); }, 300);
  // Back from another app: lay out again and ask for the size that fits now (the page may have been frozen).
  document.addEventListener('visibilitychange', () => { if (!document.hidden) setTimeout(() => { layout(); requestSize(); }, 300); });

  // The scroll pad (keyboard up): drag the thumb, or touch anywhere on the pad to bring the thumb there.
  const pad = $('pad'), padThumb = $('padthumb'), PAD_H = 104;
  function padLayout(vw, vh, maxOx, maxOy) {
    const on = typing() && (maxOx > 0 || maxOy > 0);
    pad.classList.toggle('scrolls', on);
    if (!on) return;
    const fullW = fbW * scale, fullH = fbH * scale;
    const pw = Math.round(Math.min(160, Math.max(44, PAD_H * fullW / fullH)));
    pad.style.width = pw + 'px'; pad.style.height = PAD_H + 'px';
    const tw = Math.max(18, pw * Math.min(1, vw / fullW)), th = Math.max(18, PAD_H * Math.min(1, vh / fullH));
    padThumb.style.width = tw + 'px'; padThumb.style.height = th + 'px';
    padThumb.style.left = (maxOx ? (pw - tw) * ox / maxOx : (pw - tw) / 2) + 'px';
    padThumb.style.top = (maxOy ? (PAD_H - th) * oy / maxOy : 0) + 'px';
  }
  pad.addEventListener('pointerdown', e => {
    e.preventDefault(); // keep the keyboard up
    pad.setPointerCapture(e.pointerId);
    const t = padThumb.getBoundingClientRect();
    // On the thumb: carry it from where it was grabbed. Elsewhere: centre it on the finger.
    const on = e.clientX >= t.left && e.clientX <= t.right && e.clientY >= t.top && e.clientY <= t.bottom;
    const gx = on ? e.clientX - (t.left + t.width / 2) : 0, gy = on ? e.clientY - (t.top + t.height / 2) : 0;
    follow = false; // the pad decides now, not the last tap
    const move = ev => {
      const r = pad.getBoundingClientRect(), tw = padThumb.offsetWidth, th = padThumb.offsetHeight;
      const fx = Math.min(1, Math.max(0, (ev.clientX - gx - r.left - tw / 2) / Math.max(1, r.width - tw)));
      const fy = Math.min(1, Math.max(0, (ev.clientY - gy - r.top - th / 2) / Math.max(1, r.height - th)));
      ox = fx * Math.max(0, fbW * scale - view.clientWidth);
      oy = fy * Math.max(0, fbH * scale - view.clientHeight);
      layout();
    };
    move(e);
    pad.onpointermove = move;
    pad.onpointerup = pad.onpointercancel = () => { pad.onpointermove = null; localStorage.setItem('desktop.ox', String(Math.round(ox))); };
  });

  // Minimap: the whole desktop, redrawn a few times a second, with the slice in view marked.
  const minicv = $('minicv'), win = $('win');
  function miniWindow() {
    if (!fbW || !minicv.clientWidth) return;
    const k = minicv.clientWidth / fbW;
    win.style.left = `${minicv.offsetLeft + ox / scale * k}px`;
    win.style.top = `${minicv.offsetTop + oy / scale * k}px`;
    win.style.width = `${Math.min(fbW, view.clientWidth / scale) * k}px`;
    win.style.height = `${Math.min(fbH, view.clientHeight / scale) * k}px`;
  }
  setInterval(() => {
    if (!canvas || !fbW || !root.classList.contains('mini') || typing() || document.hidden) return;
    const w = minicv.clientWidth * devicePixelRatio, h = Math.round(w * fbH / fbW);
    if (minicv.width !== w || minicv.height !== h) { minicv.width = w; minicv.height = h; minicv.style.aspectRatio = `${fbW} / ${fbH}`; }
    minicv.getContext('2d').drawImage(canvas, 0, 0, w, h);
    miniWindow();
  }, 400);
  $('mini').addEventListener('pointerdown', e => {
    $('mini').setPointerCapture(e.pointerId);
    const move = ev => {
      const r = minicv.getBoundingClientRect(), k = r.width / fbW;
      const cx = (ev.clientX - r.left) / k, cy = (ev.clientY - r.top) / k; // desktop px under the finger
      ox = cx * scale - view.clientWidth / 2;
      oy = cy * scale - view.clientHeight / 2;
      layout();
      localStorage.setItem('desktop.ox', String(Math.round(ox)));
    };
    move(e);
    $('mini').onpointermove = move;
    $('mini').onpointerup = $('mini').onpointercancel = () => { $('mini').onpointermove = null; };
  });

  // Mode. Mobile: portrait, twice the free area here (the view with the keyboard down), so it fits whole.
  // Desktop: 1600×900, panned with the minimap.
  let mode = localStorage.getItem('desktop.mode') || 'mobile', sizeTries = 0;
  function wanted() {
    if (mode === 'desk') return { w: 1600, h: 900 };
    if (!view.clientWidth || !view.clientHeight) return null; // hidden (another tab is showing): ask again when it's back
    // The whole free area: the view plus the minimap, which hides when the desktop fits.
    const mini = $('mini'), h = (fitH || view.clientHeight) + (mini.offsetParent ? mini.offsetHeight : 0);
    return { w: Math.round(view.clientWidth) * 2, h: Math.round(h) * 2 };
  }
  function requestSize() {
    if (rfb?._rfbConnectionState !== 'connected') return;
    // The server says it can resize in its first frame, which may come just after 'connect'.
    if (!rfb._supportsSetDesktopSize) { if (sizeTries++ < 20) setTimeout(requestSize, 250); return; }
    sizeTries = 0;
    const want = wanted();
    if (!want || (fbW === want.w && fbH === want.h)) return;
    RFB.messages.setDesktopSize(rfb._sock, want.w, want.h, rfb._screenID, rfb._screenFlags);
  }
  function showMode() {
    root.classList.toggle('desk', mode === 'desk');
    root.classList.toggle('whole', whole);
    $('zoom').setAttribute('aria-pressed', whole);
    $('zoom').setAttribute('aria-label', whole ? 'Zoom in: fit the height and pan' : 'Zoom out: show the whole desktop');
    $('modelabel').textContent = mode === 'desk' ? 'Desktop' : 'Mobile';
  }
  showMode();
  $('mode').addEventListener('click', () => {
    mode = mode === 'desk' ? 'mobile' : 'desk';
    localStorage.setItem('desktop.mode', mode);
    showMode();
    ox = oy = 0;
    layout(); // the view's new height first (the minimap comes and goes with the mode), then ask for that size
    requestSize();
  });

  $('zoom').addEventListener('click', () => {
    whole = !whole;
    localStorage.setItem('desktop.whole', whole ? '1' : '');
    showMode();
    layout();
  });

  // Pointer messages to the desktop.
  const fb = (cx, cy) => {
    const r = view.getBoundingClientRect();
    return [Math.round((cx - r.left - x0) / scale), Math.round((cy - r.top - y0) / scale)];
  };
  const send = (x, y, mask) => { if (rfb?._rfbConnectionState === 'connected') RFB.messages.pointerEvent(rfb._sock, x, y, mask); };
  const click = (x, y, button) => { send(x, y, 0); send(x, y, button); send(x, y, 0); };
  const notch = (x, y, button, n) => { for (let i = 0; i < n; i++) { send(x, y, button); send(x, y, 0); } };

  // Hover mode, for menus that open on hover: a tap only moves the pointer there (a ring marks it), so nothing is
  // clicked; a long-press clicks there and ends hover mode.
  let hovering = false;
  const setHover = on => { hovering = on; root.classList.toggle('hovering', on); $('hover').setAttribute('aria-pressed', on); };
  $('hover').addEventListener('click', () => setHover(!hovering));
  const ring = (cx, cy) => {
    const r = view.getBoundingClientRect(), p = $('ptr');
    p.style.left = (cx - r.left) + 'px'; p.style.top = (cy - r.top) + 'px';
    p.classList.add('on');
    requestAnimationFrame(() => requestAnimationFrame(() => p.classList.remove('on')));
  };

  // Gestures.
  let g = null, fling = 0;
  touch.addEventListener('pointerdown', e => {
    if (g) return; // one finger at a time
    cancelAnimationFrame(fling);
    touch.setPointerCapture(e.pointerId);
    g = { id: e.pointerId, x0: e.clientX, y0: e.clientY, x: e.clientX, y: e.clientY, mode: 'pending', ax: 0, ay: 0, samples: [] };
    g.timer = setTimeout(() => { if (g?.mode === 'pending') { g.mode = 'armed'; navigator.vibrate?.(12); } }, LONG_PRESS);
  });
  touch.addEventListener('pointermove', e => {
    if (!g || e.pointerId !== g.id) return;
    const dx = e.clientX - g.x, dy = e.clientY - g.y, far = Math.hypot(e.clientX - g.x0, e.clientY - g.y0) > SLOP;
    if (g.mode === 'pending' && far) { clearTimeout(g.timer); g.mode = 'scroll'; }
    if (g.mode === 'armed' && far) { // long-press then drag: hold the left button
      g.mode = 'drag';
      const [x, y] = fb(g.x0, g.y0);
      send(x, y, 0); send(x, y, BTN.left);
    }
    if (g.mode === 'drag') send(...fb(e.clientX, e.clientY), BTN.left);
    if (g.mode === 'scroll') {
      scrollBy(g, dx, dy, e.clientX, e.clientY);
      g.samples.push({ t: e.timeStamp, x: e.clientX, y: e.clientY });
      if (g.samples.length > 6) g.samples.shift();
    }
    g.x = e.clientX; g.y = e.clientY;
  });
  const end = e => {
    if (!g || e.pointerId !== g.id) return;
    clearTimeout(g.timer);
    const [x, y] = fb(g.x, g.y);
    if (g.mode === 'pending' && hovering) { send(...fb(g.x0, g.y0), 0); ring(g.x0, g.y0); }
    else if (g.mode === 'pending') { const p = fb(g.x0, g.y0); lastTapY = p[1]; follow = true; click(...p, BTN.left); }
    else if (g.mode === 'armed' && hovering) { const p = fb(g.x0, g.y0); lastTapY = p[1]; follow = true; click(...p, BTN.left); setHover(false); }
    else if (g.mode === 'armed') click(...fb(g.x0, g.y0), BTN.right);
    else if (g.mode === 'drag') send(x, y, 0);
    else if (g.mode === 'scroll' && e.type === 'pointerup') momentum(g);
    g = null;
  };
  touch.addEventListener('pointerup', end);
  touch.addEventListener('pointercancel', end);

  // Finger down = content down = wheel up; finger right = wheel left. One notch per STEP px of finger.
  function scrollBy(st, dx, dy, cx, cy) {
    st.ax += dx; st.ay += dy;
    const [x, y] = fb(cx, cy);
    const ny = Math.trunc(st.ay / STEP), nx = Math.trunc(st.ax / STEP);
    if (ny) { notch(x, y, ny > 0 ? BTN.wheelUp : BTN.wheelDown, Math.abs(ny)); st.ay -= ny * STEP; }
    if (nx) { notch(x, y, nx > 0 ? BTN.wheelLeft : BTN.wheelRight, Math.abs(nx)); st.ax -= nx * STEP; }
  }
  function momentum(s) {
    const a = s.samples[0], b = s.samples.at(-1);
    if (!a || b.t - a.t < 10 || s.samples.length < 3) return;
    let vx = (b.x - a.x) / (b.t - a.t) * 16, vy = (b.y - a.y) / (b.t - a.t) * 16; // px per frame
    if (Math.hypot(vx, vy) < 6) return;
    const st = { ax: 0, ay: 0 }, at = [b.x, b.y];
    const step = () => {
      vx *= 0.94; vy *= 0.94;
      if (Math.hypot(vx, vy) < 1) return;
      scrollBy(st, vx, vy, ...at);
      fling = requestAnimationFrame(step);
    };
    fling = requestAnimationFrame(step);
  }

  // Keyboard: a hidden field takes the phone keyboard's text; special keys come from the row above it.
  const key = (sym, down) => rfb?.sendKey(sym, null, down);
  const SENTINEL = '  ';
  let ctrl = false;
  const tap = sym => {
    if (ctrl) key(KeyTable.XK_Control_L, true);
    key(sym);
    if (ctrl) { key(KeyTable.XK_Control_L, false); setCtrl(false); }
  };
  const setCtrl = on => { ctrl = on; $('ctrl').setAttribute('aria-pressed', on); };
  const reset = () => { ime.value = SENTINEL; ime.setSelectionRange(SENTINEL.length, SENTINEL.length); };
  const typeText = text => { for (const ch of text) tap(ch === '\n' ? KeyTable.XK_Return : keysyms.lookup(ch.codePointAt(0))); };
  let composing = false;
  ime.addEventListener('compositionstart', () => { composing = true; });
  ime.addEventListener('compositionend', e => { composing = false; typeText(e.data || ''); reset(); });
  ime.addEventListener('input', e => {
    if (composing || e.isComposing) return;
    if (e.inputType === 'deleteContentBackward') tap(KeyTable.XK_BackSpace);
    else if (e.inputType === 'insertLineBreak' || e.inputType === 'insertParagraph') tap(KeyTable.XK_Return);
    else if (e.data) typeText(e.data);
    reset();
  });
  ime.addEventListener('keydown', e => {
    if (e.key === 'Enter') { e.preventDefault(); tap(KeyTable.XK_Return); }
    else if (e.key === 'Backspace' && ime.value === SENTINEL) { e.preventDefault(); tap(KeyTable.XK_BackSpace); }
  });
  const stopTyping = () => { root.classList.remove('typing'); kbUp = false; oy = 0; layout(); };
  $('kb').addEventListener('click', () => {
    if (root.classList.toggle('typing')) { reset(); follow = true; ime.focus(); layout(); } else { ime.blur(); stopTyping(); }
  });
  ime.addEventListener('blur', () => setTimeout(() => { if (document.activeElement !== ime && typing()) stopTyping(); }, 150));
  for (const b of document.querySelectorAll('#keys button[data-key]')) {
    b.addEventListener('pointerdown', e => e.preventDefault()); // keep the keyboard up
    b.addEventListener('click', () => tap(KeyTable['XK_' + b.dataset.key]));
  }
  $('ctrl').addEventListener('pointerdown', e => e.preventDefault());
  $('ctrl').addEventListener('click', () => setCtrl(!ctrl));
}
