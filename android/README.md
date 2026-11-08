# Otto app

The second screen and the device agent in one Android app, in Kotlin (package `io.github.otto`, Android 12+):

- a handle on the screen's right edge: a tap or a swipe across it fades the second screen in and out over any app;
  a drag up or down moves it;
- the second screen: the browser agents drive (embedded Chromium, in its own process), files, artifacts, the box's
  desktop and terminal, its status;
- links to the paired box's hub and to the artifact hub, tapped in any app, open there;
- the device agent: an accessibility service for the UI tree, screenshots, taps, swipes and typing, plus installs and
  logs, run by the box over the device link.

Moved in from the second-screen and device-agent apps (Java), rewritten in Kotlin; the wire protocols to the box's
relay are unchanged (`box/relay/src/browser.ts`, `box/relay/src/device.ts`). The reverse tunnel and the device HTTP
API come later (see `docs/architecture.md`).

## Build

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64   # any JDK 17+
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

`local.properties` gives the Android SDK (`sdk.dir=…`). The release is signed with `OTTO_KEYSTORE`
(`OTTO_KEYSTORE_PASSWORD`, `OTTO_KEY_ALIAS`) when set, else with the debug key. `-PversionCode=N -PversionName=X`
set the version; an update must not lower the versionCode.

Nothing about a particular box is built in: no host, port, token or domain. The web UI is copied from `../web` into
the APK's assets at build time (task `syncWeb`), so `web/` is its only source.

### Extra link hosts

The app opens links to `*.ts.net` (the tailnet's names) on the hub's and the artifact hub's routes, and Android only
lets an app claim hosts its manifest lists. For artifact links on any other host, such as an artifact hub's public
domain, list the hosts at build time:

```sh
./gradlew assembleRelease -Potto.extraLinkHosts=artifacts.example.com
```

or put `otto.extraLinkHosts=artifacts.example.com` (comma-separated for several) in `local.properties`, which isn't
committed. Their `/a/`, `/p/` and `/v/` links are then claimed too.

## Pairing

On the box, with `[phone] hub_url` and `artifacts_url` set in `~/.config/otto/config.toml`
(see `install/config.example.toml`):

```sh
otto pair            # prints the pairing link: otto://pair?box=…&browser_port=…&…
otto pair --qr       # and as a QR code to scan (needs qrencode)
otto pair --send     # opens it on the phone through the device link
```

The link carries the box's tailnet name, the relay's ports (browser, pipe, device), both links' tokens, the hub's URL
and the artifact hub's (and optionally its public address and the box's control panel). The app shows what it's
about to save and asks before saving it. The setup screen shows the pairing and both links' states, and can unpair.

## Setup on the phone

Open Otto. It's a setup screen: the handle's switch at the top, then each thing it needs, with a button for each:

- **Draw over other apps**: for the handle and the second screen.
- **Accessibility service**: Settings › Accessibility › Installed apps › Otto. Android 13+ may first want
  App info › ⋮ › *Allow restricted settings* for an app not installed from a store.
- **Install apps**: lets agents install and update APKs.
- **Notification**: one quiet notification while the handle is on.
- **Log access**: once over adb, `adb shell pm grant io.github.otto android.permission.READ_LOGS`.
- **Open links here**: Open by default › Supported web addresses, turn on `*.ts.net` (and any extra hosts).
- **Background**: unrestricted battery use, so Otto stays connected with the screen off.

## How it's built

- `OverlayService`, a foreground service while the handle is on, keeps the handle (an overlay window), the second
  screen (`Screen`, shown in the transparent `ScreenActivity` while it's in, so other overlays, Back, the keyboard
  and Home work as in any app) and the device link (`device/DeviceLink`).
- `BrowserService` runs the browser in its own process (`:browser`): Android's WebView debugging switch is per
  process, so the DevTools socket `BrowserLink` hands to the box holds the browser's tabs and nothing else. Its pages
  are drawn into the screen through `SurfaceControlViewHost`; until the screen first embeds them they're laid out in
  a window that isn't shown, so agents can drive and screenshot them with the screen never opened.
- **Keeping the browser up.** While the handle is on, `BrowserService` is a foreground service of its own, started by
  `OverlayService`; both post the same notification (same id and channel), so there's one. A foreground service's
  process is neither frozen nor killed in the background, so the browser link stays up with the screen closed or
  the phone locked. `BrowserLink` reconnects with a growing pause (1 s to 30 s), at once when the network changes or
  the pairing does, and writes a line now and then to notice a dead connection. `Screen` rebinds if its binding dies.
  Turning the handle off stops both.
- `LinkActivity` takes the links: the manifest claims `https://*.ts.net` on the hub's routes (`/`, `/desktop`,
  `/terminal`, `/files`, `/api/fs/`) and the artifact hub's (`/a/`, `/p/`, `/v/`); at runtime the app keeps only links
  to the paired hub and artifact hub (`Links`) and hands every other one to the default browser.
- `Pairing` keeps what pairing gave in a small JSON file both processes read; `AppWeb` answers the web UI's
  `config.js` with the hub's and artifact hub's URLs from it.
- `device/`: `AgentAccessibility` (UI tree, gestures, global actions, screenshots), `Commands` (screenshot, ui, tap,
  longpress, swipe, text, key, wait, current, launch, forcestop, packages, appinfo, install, uninstall, logcat, info),
  `UiTree`, `Installer`, `LogReader` (READ_LOGS), `Frames` (the device link's wire format).
- `BootReceiver` brings the handle back after a restart or an update, if it was on.
