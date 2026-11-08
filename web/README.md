# web

The web UI: files, artifacts, the desktop (noVNC), the terminal (xterm.js) and the box's status, as plain web
code with no build step. Moved in from the second-screen app's `assets/ide/`.

- **In the Otto Android app** it is copied into the APK's assets at build time (`android/app/build.gradle.kts`,
  task `syncWeb`) and served from `https://appassets.androidplatform.net/assets/ide/`. This directory is its only
  source.
- **In a browser** on the tailnet, open `index.html` from wherever it's served, with the services in the query
  the first time (they are remembered): `index.html?hub=https://<box>:8446&artifacts=https://<hub>:8444#files`.

No host is written into the code. `config.js` sets `window.OTTO_CONFIG`; the app answers that file's URL itself with
what it got at pairing. `ui.js` reads it as `config`:

| key | what | default |
|---|---|---|
| `hub` | the box's hub: files API; its host also carries the desktop and terminal websockets | the page's origin |
| `artifacts` | the artifact hub, over the tailnet | |
| `publicArtifacts` | the artifact hub's public address, for links you share | `artifacts` |
| `panel` | the box's control panel (status, start, stop), for the Devbox section | none |
| `desktop` | the desktop's websocket | `wss://<hub host>:8447/websockify` |
| `terminal` | the terminal's websocket | `wss://<hub host>:8451/` |

Routes (the part after `#`): `artifacts`, `artifact/<id>[/v<n>]`, `view/<version id>`, `files[?path=]`,
`file?path=`, `desktop`, `terminal`, `devbox`. See `ide.js`.

Vendored: noVNC 1.3.0 (MPL-2.0), xterm.js 6.0.0 and its fit addon (MIT), marked (MIT), highlight.js (BSD-3).
