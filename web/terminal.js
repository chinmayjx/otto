// Terminal: the devbox's tmux windows (the second screen's Terminal section).
//
// xterm.js (vendor/xterm: @xterm/xterm 6.0.0 and @xterm/addon-fit 0.11.0) draws it and takes the phone keyboard.
// The row of keys adds Esc, Tab, Ctrl and the arrows, which that keyboard has no keys for. The websocket is
// hub/terminal.py behind `tailscale serve --https=8451`: binary frames are terminal bytes, a text frame
// {"cols","rows"} sets the size; list/select text frames drive the native window picker. FitAddon refits
// to the width; the keyboard shrinks the page and it refits again.

import { devboxStatus, downCard } from './devbox.js';
import { app, config, send as nativeSend } from './ui.js';

const WS_URL = config.terminal;

export async function terminal() {
  const [{ Terminal }, { FitAddon }] = await Promise.all([
    import('./vendor/xterm/xterm.js'), import('./vendor/xterm/addon-fit.js')]);
  const root = app;
  root.className = 'terminal';
  root.innerHTML = `
    <div class="screen">
      <div class="status">Connecting…</div>
      <div class="down"></div>
    </div>
    <div class="keys">
      <button class="kb" aria-label="Keyboard"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><rect x="2.5" y="6" width="19" height="12" rx="2"/><path d="M6 10h.01M9.5 10h.01M13 10h.01M16.5 10h.01M7.5 14h9"/></svg></button>
      <button data-key="Escape">Esc</button><button data-key="Tab">Tab</button><button class="ctrl" aria-pressed="false">Ctrl</button>
      <button data-key="Left">←</button><button data-key="Up">↑</button><button data-key="Down">↓</button><button data-key="Right">→</button>
    </div>`;
  const $ = sel => root.querySelector(sel);
  const screen = $('.screen'), status = $('.status'), down = $('.down');
  const term = new Terminal({
    fontFamily: 'monospace', fontSize: 15, cursorBlink: true, scrollback: 2000,
    overviewRuler: { width: 1 }, // FitAddon keeps this much free on the right for a scrollbar (14px unset); touch scrolls
    theme: { background: '#000000', foreground: '#edeef0', cursor: '#ff3b30', selectionBackground: '#ff3b3055' },
  });
  const fit = new FitAddon();
  term.loadAddon(fit);
  term.open(screen);

  let selected = '';
  try { selected = localStorage.getItem('terminal-window') || ''; } catch {}
  const control = msg => { if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg)); };
  window.ccmTerminal = target => control(target ? { op: 'select', target } : { op: 'list' });
  const poll = setInterval(() => control({ op: 'list' }), 3000);
  const enc = new TextEncoder();
  let ws, ctrl = false, closed = false, retry = 0, fitTimer = 0;
  const send = data => { if (ws?.readyState === WebSocket.OPEN) ws.send(enc.encode(data)); };
  const sendSize = () => {
    if (ws?.readyState === WebSocket.OPEN && term.cols >= 2 && term.rows >= 2)
      ws.send(JSON.stringify({ cols: term.cols, rows: term.rows }));
  };
  // Hidden (another section is in front) the page has no size: fitting then would shrink the shared session.
  // Only whole columns fit: what's left of the width is split evenly on both sides.
  const refit = () => {
    if (screen.clientWidth < 40 || screen.clientHeight < 40) return;
    fit.fit();
    const cells = screen.querySelector('.xterm-screen');
    cells.style.marginLeft = `${Math.floor((screen.clientWidth - cells.offsetWidth) / 2)}px`;
  };
  const scheduleFit = () => { clearTimeout(fitTimer); fitTimer = setTimeout(refit, 50); };
  term.onResize(sendSize);
  requestAnimationFrame(refit);

  const setCtrl = on => { ctrl = on; $('.ctrl').setAttribute('aria-pressed', on); };
  // One character, turned into its control key: c → ^C. A composed word is typed as itself.
  const ctrlOf = data => {
    if ([...data].length !== 1) return data;
    const c = data.codePointAt(0);
    if (c >= 64 && c <= 95) return String.fromCharCode(c & 0x1f);
    if (c >= 97 && c <= 122) return String.fromCharCode(c - 96);
    return data;
  };
  term.onData(data => { if (ctrl) { setCtrl(false); data = ctrlOf(data); } send(data); });

  // tmux owns the scrollback. Feed swipes through xterm's wheel handler so mouse-aware TUIs get them too.
  let touch = null;
  screen.addEventListener('touchstart', e => {
    touch = e.touches.length === 1 ? { x: e.touches[0].clientX, y: e.touches[0].clientY, rest: 0, moved: false } : null;
  }, { passive: true });
  screen.addEventListener('touchmove', e => {
    if (!touch || e.touches.length !== 1) return;
    const t = e.touches[0], dy = touch.y - t.clientY;
    if (!touch.moved && Math.abs(dy) < 8) return;
    touch.moved = true;
    touch.y = t.clientY;
    touch.rest += dy;
    e.preventDefault();
    e.stopImmediatePropagation();
    const cells = screen.querySelector('.xterm-screen');
    const rect = cells.getBoundingClientRect(), line = rect.height / term.rows;
    const steps = Math.trunc(touch.rest / line);
    touch.rest -= steps * line;
    for (let i = 0; i < Math.abs(steps); i++) cells.dispatchEvent(new WheelEvent('wheel', {
      bubbles: true, cancelable: true,
      clientX: Math.max(rect.left, Math.min(rect.right - 1, touch.x)),
      clientY: Math.max(rect.top, Math.min(rect.bottom - 1, t.clientY)),
      deltaY: Math.sign(steps), deltaMode: 1,
    }));
  }, { passive: false, capture: true });
  screen.addEventListener('touchend', e => {
    if (touch?.moved) { e.preventDefault(); e.stopImmediatePropagation(); }
    else if (touch) term.focus();
    touch = null;
  }, { passive: false, capture: true });
  screen.addEventListener('touchcancel', () => { touch = null; });

  const arrow = code => send(term.modes.applicationCursorKeysMode ? `\x1bO${code}` : `\x1b[${code}`);
  const KEY = { Escape: () => send('\x1b'), Tab: () => send('\t'), Left: () => arrow('D'), Up: () => arrow('A'), Down: () => arrow('B'), Right: () => arrow('C') };
  for (const b of root.querySelectorAll('.keys button')) b.addEventListener('pointerdown', e => e.preventDefault()); // keep the keyboard up
  for (const b of root.querySelectorAll('.keys button[data-key]')) b.addEventListener('click', () => KEY[b.dataset.key]());
  $('.ctrl').addEventListener('click', () => setCtrl(!ctrl));
  $('.kb').addEventListener('click', () => term.focus());

  // The keyboard hidden by Android (its own button, Back) only shows as the page growing back: the field keeps
  // its focus, so drop it then. The page shrinks and grows with the keyboard (the activity pads for it).
  let kbUp = false, fullH = innerHeight;
  const watchKeyboard = () => {
    scheduleFit();
    const h = innerHeight;
    if (!root.classList.contains('typing')) { fullH = h; kbUp = false; return; }
    if (h > fullH) fullH = h;
    if (h < fullH - 150) kbUp = true;
    else if (kbUp && h > fullH - 60) term.blur();
  };
  addEventListener('resize', watchKeyboard);
  visualViewport?.addEventListener('resize', watchKeyboard);
  const ro = new ResizeObserver(scheduleFit);
  ro.observe(screen);
  screen.addEventListener('focusin', () => root.classList.add('typing'));
  screen.addEventListener('focusout', () => root.classList.remove('typing'));

  // Reconnects by itself. While the devbox is off the websocket hangs (Tailscale gives no quick error) and the
  // panel answers at once, so the stopped card replaces the spinner.
  function connect() {
    if (ws && ws.readyState !== WebSocket.CLOSED) { ws.dropped = true; ws.close(); }
    status.textContent = 'Connecting…';
    status.hidden = false;
    const socket = ws = new WebSocket(WS_URL);
    socket.binaryType = 'arraybuffer';
    socket.addEventListener('open', () => {
      status.hidden = true; term.reset(); sendSize();
      control(selected ? { op: 'select', target: selected } : { op: 'list' });
    });
    socket.addEventListener('message', e => {
      if (socket !== ws) return;
      if (e.data instanceof ArrayBuffer) { term.write(new Uint8Array(e.data)); return; }
      try {
        const state = JSON.parse(e.data);
        if (state.op !== 'windows') return;
        if (state.current) {
          selected = state.current;
          try { localStorage.setItem('terminal-window', selected); } catch {}
        }
        nativeSend('terminalBar', { state });
      } catch {}
    });
    socket.addEventListener('close', () => {
      if (closed || socket.dropped || socket !== ws) return;
      status.hidden = false;
      status.textContent = 'Reconnecting…';
      retry = setTimeout(connect, 1500);
    });
    devboxStatus().then(async st => {
      if (st.state === 'running' || socket !== ws || socket.readyState === WebSocket.OPEN) return;
      socket.dropped = true;
      socket.close();
      status.hidden = true;
      await downCard(down, st);
      down.innerHTML = '';
      if (!closed) connect();
    }, () => {});
  }
  connect();

  return () => {
    closed = true;
    clearInterval(poll);
    delete window.ccmTerminal;
    clearTimeout(retry);
    clearTimeout(fitTimer);
    ro.disconnect();
    removeEventListener('resize', watchKeyboard);
    visualViewport?.removeEventListener('resize', watchKeyboard);
    if (ws) { ws.dropped = true; ws.close(); }
    term.dispose();
  };
}
