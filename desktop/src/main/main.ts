// Electron entry: one frameless window, the control panel. The Texera UI opens in the browser.

import { app, BrowserWindow, Menu, clipboard, ipcMain, shell } from "electron";
import * as path from "node:path";
import { ComposeOptions, buildComposeArgs } from "./compose";
import { composeDown, composeLogs, composeUp, dockerEnv, getStackState, serviceAction } from "./docker";
import { DASHBOARDS, SERVICE_GROUP_ORDER, classifyService } from "./services";
import { createSession, killAll, killSession, resizeSession, writeSession, PtySpawn } from "./pty";

const PROJECT_NAME = "texera-single-node";
const DOCKER_DESKTOP_URL = "https://www.docker.com/products/docker-desktop/";
const TEXERA_UI_URL = process.env.TEXERA_UI_URL || "http://localhost";

let win: BrowserWindow | null = null;
let withObservability = true;

// Bundled compose files live beside the app when packaged, in the repo during dev.
function composeDir(): string {
  if (app.isPackaged) return path.join(process.resourcesPath, "single-node");
  return path.resolve(__dirname, "../../../../bin/single-node");
}

function options(): ComposeOptions {
  return { projectName: PROJECT_NAME, composeDir: composeDir(), withObservability };
}

// The Texera UI link honors the env override so it matches the running deployment.
function dashboards() {
  return DASHBOARDS.map(d => (d.key === "texera" ? { ...d, url: TEXERA_UI_URL } : d));
}

function defaultShell(): { file: string; args: string[] } {
  if (process.platform === "win32") return { file: process.env.COMSPEC || "powershell.exe", args: [] };
  return { file: process.env.SHELL || "bash", args: [] };
}

// A pty tab is a plain shell, a shell inside a service, a live log follow, or docker stats.
function ptySpec(kind: string, service: string, cols: number, rows: number): PtySpawn {
  const base = { cwd: composeDir(), env: dockerEnv(), cols, rows };
  if (kind === "service-shell") return { ...base, file: "docker", args: buildComposeArgs(options(), ["exec", service, "sh"]) };
  if (kind === "logs") return { ...base, file: "docker", args: buildComposeArgs(options(), ["logs", "-f", "--tail", "200", service]) };
  if (kind === "stats") return { ...base, file: "docker", args: ["stats"] };
  const sh = defaultShell();
  return { ...base, file: sh.file, args: sh.args };
}

function createWindow(): void {
  win = new BrowserWindow({
    width: 860,
    height: 720,
    minWidth: 540,
    title: "Texera",
    frame: false,
    backgroundColor: "#11161c",
    webPreferences: {
      preload: path.join(__dirname, "../preload/preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      webviewTag: true
    }
  });
  win.loadFile(path.join(__dirname, "../renderer/index.html"));

  // Tell the renderer when to drop the window border (maximized / fullscreen).
  const sendState = () => win?.webContents.send("win-state", { maximized: win.isMaximized() || win.isFullScreen() });
  win.on("maximize", sendState);
  win.on("unmaximize", sendState);
  win.on("enter-full-screen", sendState);
  win.on("leave-full-screen", sendState);
  win.webContents.on("did-finish-load", sendState);
}

function registerIpc(): void {
  ipcMain.handle("dashboards", () => dashboards());
  ipcMain.handle("state", async () => {
    const s = await getStackState(options());
    const containers = s.containers.map(c => ({ ...c, ...classifyService(c.service || c.name) }));
    return { ...s, groupOrder: SERVICE_GROUP_ORDER, containers };
  });
  ipcMain.handle("set-observability", (_e, value: boolean) => {
    withObservability = Boolean(value);
    return withObservability;
  });
  ipcMain.handle("logs", (_e, service: string) => composeLogs(options(), service));
  ipcMain.handle("service-action", (_e, o: { service: string; action: "restart" | "stop" | "start" }) =>
    serviceAction(options(), o.service, o.action));
  ipcMain.handle("copy", (_e, text: string) => clipboard.writeText(String(text)));
  ipcMain.handle("pty:create", (_e, o: { kind: string; service?: string; cols: number; rows: number }) =>
    createSession(
      ptySpec(o.kind, o.service || "", o.cols, o.rows),
      (id, data) => win?.webContents.send("pty:data", { id, data }),
      (id, code) => win?.webContents.send("pty:exit", { id, code })
    ));
  ipcMain.on("pty:input", (_e, o: { id: number; data: string }) => writeSession(o.id, o.data));
  ipcMain.on("pty:resize", (_e, o: { id: number; cols: number; rows: number }) => resizeSession(o.id, o.cols, o.rows));
  ipcMain.on("pty:kill", (_e, o: { id: number }) => killSession(o.id));
  ipcMain.handle("start", async () => {
    await composeUp(options());
  });
  ipcMain.handle("stop", async () => {
    await composeDown(options());
  });
  ipcMain.handle("open", (_e, url: string) => shell.openExternal(url));
  ipcMain.handle("open-docker-install", () => shell.openExternal(DOCKER_DESKTOP_URL));
  ipcMain.handle("win-minimize", () => win?.minimize());
  ipcMain.handle("win-toggle-maximize", () => {
    if (!win) return;
    win.isMaximized() ? win.unmaximize() : win.maximize();
  });
  ipcMain.handle("win-close", () => win?.close());
}

app.whenReady().then(() => {
  Menu.setApplicationMenu(null); // no File/Edit/View menu bar
  registerIpc();
  createWindow();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("will-quit", () => killAll());

app.on("window-all-closed", () => {
  killAll();
  if (process.platform !== "darwin") app.quit();
});
