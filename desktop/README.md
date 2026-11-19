# Texera Control Panel

A small cross-platform desktop app that sets up, starts, stops, and monitors a single-node Texera stack on top of Docker Desktop. It is a thin control layer over the existing `bin/single-node` Docker Compose deployment, not a separate runtime. It runs on Windows, macOS, and Linux from one codebase, packaged with electron-builder.

## What it does

- Detects whether Docker Desktop is installed and running, and links to the download if not.
- Starts and stops the stack (`docker compose up -d` / `down`), with an optional observability overlay.
- Shows live per-service health, polled from `docker compose ps`.
- One-click buttons to open the Texera UI and the observability dashboards (Grafana, Jaeger, Parca, VictoriaMetrics).

## Layout

- `src/main/compose.ts`: pure helpers (command building, `ps` parsing, status). Unit-tested.
- `src/main/docker.ts`: runs the docker CLI and maps it onto those helpers.
- `src/main/main.ts`: Electron main process and IPC.
- `src/preload/preload.ts`: typed bridge to the renderer.
- `src/renderer/index.html`: the control panel UI.
- `test/`: `node --test` unit tests for the pure logic.

## Develop

```
cd desktop
npm install
npm test       # builds, then runs the unit tests
npm start      # launches the app against the repo's bin/single-node compose files
```

The repo ignores `package-lock.json` by convention, so dependency versions are pinned exactly in `package.json`.

## Install (end user)

Grab the installer for your OS from the `desktop-installers` CI artifacts (or a release), then:

- **Windows:** run `Texera-Control-Panel-<version>-win-x64.exe` and follow the installer. Unsigned builds trip SmartScreen: choose More info then Run anyway.
- **macOS:** open the `.dmg` and drag the app to Applications. Unsigned/un-notarized builds are Gatekeeper-blocked: right-click the app, Open, then confirm (or `xattr -dr com.apple.quarantine` the app).
- **Linux:** `chmod +x Texera-Control-Panel-<version>-linux-x86_64.AppImage` and run it, or install the `.deb` with `sudo dpkg -i`.

Docker Desktop (or Docker Engine) must be installed and running; the panel detects it and links to the download if missing.

## Build the installers

Each OS installer can only be built on its own OS (or a matching CI runner): you cannot produce a signed `.dmg` on Linux. electron-builder picks the current OS by default; the per-OS scripts are explicit.

```
npm run dist         # current OS, native targets
npm run dist:win     # NSIS .exe + portable .exe   (run on Windows)
npm run dist:mac     # .dmg + .zip                 (run on macOS)
npm run dist:linux   # .AppImage + .deb            (run on Linux)
```

Output lands in `release/`. CI builds all three in `.github/workflows/desktop-installers.yml` (a windows/macOS/ubuntu matrix) and uploads one artifact per platform.

## Status and known gaps

This is an MVP. Before a signed release:

- Bundling of the full deploy context (env files, referenced config) needs hardening; the panel currently expects the bundled `single-node` and `observability` resource folders.
- First start pulls multi-GB images; a progress indicator is not yet wired.
- Port-conflict detection and a Windows WSL2 backend check are follow-ups.
- Code-signing is unsolved: Windows Authenticode, macOS signing + Apple notarization, and icons under `build/` are all needed before a public release.
