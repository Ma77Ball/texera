// Electron entry: a single window that drives the Docker stack and opens dashboards.

import { app, BrowserWindow, ipcMain, shell } from "electron";
import * as path from "node:path";
import { ComposeOptions } from "./compose";
import { composeDown, composeUp, getStackState } from "./docker";
import { DASHBOARDS } from "./services";

const PROJECT_NAME = "texera-single-node";
const DOCKER_DESKTOP_URL = "https://www.docker.com/products/docker-desktop/";

// Bundled compose files live beside the app when packaged, in the repo during dev.
function composeDir(): string {
  if (app.isPackaged) return path.join(process.resourcesPath, "single-node");
  return path.resolve(__dirname, "../../../../bin/single-node");
}

let withObservability = true;

function options(): ComposeOptions {
  return { projectName: PROJECT_NAME, composeDir: composeDir(), withObservability };
}

function createWindow(): void {
  const win = new BrowserWindow({
    width: 760,
    height: 620,
    title: "Texera Control Panel",
    webPreferences: {
      preload: path.join(__dirname, "../preload/preload.js"),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  win.loadFile(path.join(__dirname, "../renderer/index.html"));
}

function registerIpc(): void {
  ipcMain.handle("dashboards", () => DASHBOARDS);
  ipcMain.handle("state", () => getStackState(options()));
  ipcMain.handle("set-observability", (_e, value: boolean) => {
    withObservability = Boolean(value);
    return withObservability;
  });
  ipcMain.handle("start", async () => {
    await composeUp(options());
  });
  ipcMain.handle("stop", async () => {
    await composeDown(options());
  });
  ipcMain.handle("open", (_e, url: string) => shell.openExternal(url));
  ipcMain.handle("open-docker-install", () => shell.openExternal(DOCKER_DESKTOP_URL));
}

app.whenReady().then(() => {
  registerIpc();
  createWindow();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
