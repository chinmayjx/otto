// The app's own UI, plain web code so it can run anywhere (the Android app now, a desktop app later).
// Data comes from the services' APIs; none of this is served by them.
//
//   #artifacts                  the artifact list (the second screen's Artifacts section)
//   #artifact/<id>[/v<n>]       an artifact in the viewer: its latest version, or version n (the list, a chat card)
//   #view/<version id>          one version, from a card posted before artifact links
//   #files?session=|path=       the file manager (the second screen's Files section)
//   #file?path=                 one file in the viewer
//   #desktop                    the devbox's VNC desktop, for touch (the second screen's Desktop section)
//   #terminal                   the devbox's shell, one tmux session for every chat (the Terminal section)
//   #devbox                     the devbox's status, metrics, start and stop (the Devbox section)
//
// In the Android app the viewer is a full-screen overlay (PreviewOverlay) over the list it was opened from;
// CCMNative (ui.js) asks the app for what a page can't do. In a plain browser everything is one page.

import { artifactList, artifactViewer, refreshArtifacts } from './artifacts.js';
import { desktop } from './desktop.js';
import { devbox } from './devbox.js';
import { terminal } from './terminal.js';
import { fileViewer, folder } from './files.js';

let leave = () => {}; // the current screen's cleanup

async function route() {
  leave();
  leave = () => {};
  const hash = location.hash.slice(1);
  const [name, query = ''] = hash.split('?');
  const params = Object.fromEntries(new URLSearchParams(query));
  let cleanup;
  if (name.startsWith('artifact/')) {
    const [aid, v] = name.slice(9).split('/');
    cleanup = await artifactViewer({ aid, n: Number(/^v(\d+)$/.exec(v || '')?.[1]) || null });
  } else if (name.startsWith('view/')) cleanup = await artifactViewer({ vid: name.slice(5) });
  else if (name === 'files') cleanup = await folder(params);
  else if (name === 'file' && params.path) cleanup = await fileViewer(params.path);
  else if (name === 'desktop') cleanup = await desktop();
  else if (name === 'terminal') cleanup = await terminal();
  else if (name === 'devbox') cleanup = devbox();
  else artifactList();
  if (typeof cleanup === 'function') leave = cleanup;
}

// The app calls this when the Artifacts section comes into view: what was published (or deleted) since.
window.ccmRefresh = () => { if (location.hash.startsWith('#artifacts') || !location.hash) refreshArtifacts(); };

window.addEventListener('hashchange', route);
route();
