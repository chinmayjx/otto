// Where the services this UI talks to are: no host is written into the code. Read by ui.js as `config`.
//
//   hub              the box's hub: files API, and the host the desktop and terminal websockets are on
//   artifacts        the artifact hub, over the tailnet
//   publicArtifacts  the artifact hub's public address, for links to share (default: artifacts)
//   panel            the box's control panel: status, start, stop (optional; the Devbox section needs it)
//   desktop          the desktop's websocket (default: wss://<hub host>:8447/websockify)
//   terminal         the terminal's websocket (default: wss://<hub host>:8451/)
//
// In the Otto Android app this file is never read from the assets: the app answers its URL itself with what it got
// at pairing. In a browser, the values come from the page's query (?hub=…&artifacts=…, remembered in localStorage),
// and otherwise the hub is the page's own origin (when the hub serves this UI).
(() => {
  if (window.OTTO_CONFIG) return;
  const KEYS = ['hub', 'artifacts', 'publicArtifacts', 'panel', 'desktop', 'terminal'];
  let saved = {};
  try { saved = JSON.parse(localStorage.getItem('otto.config') || '{}'); } catch {}
  const q = new URLSearchParams(location.search);
  for (const k of KEYS) if (q.has(k)) saved[k] = q.get(k);
  try { localStorage.setItem('otto.config', JSON.stringify(saved)); } catch {}
  if (!saved.hub && /^https?:$/.test(location.protocol)) saved.hub = location.origin;
  window.OTTO_CONFIG = saved;
})();
