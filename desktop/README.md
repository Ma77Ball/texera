# Texera Control Panel (Windows)

A small desktop app that sets up, starts, stops, and monitors a single-node Texera stack on Windows, on top of Docker Desktop. It is a thin control layer over the existing `bin/single-node` Docker Compose deployment, not a separate runtime.

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

## Build the Windows installer

Produces `release/TexeraSetup-<version>.exe` via electron-builder (NSIS). Must run on Windows (or a Windows CI runner); it cannot be built or tested on Linux. CI builds it in `.github/workflows/windows-installer.yml` and uploads the `.exe` as an artifact.

```
npm run dist
```

## Status and known gaps

This is an MVP. Before a signed release:

- Bundling of the full deploy context (env files, referenced config) needs hardening; the panel currently expects the bundled `single-node` and `observability` resource folders.
- First start pulls multi-GB images; a progress indicator is not yet wired.
- Port-conflict detection, WSL2 backend checks, and code-signing are follow-ups.
