// Bridges a minimal, typed API to the renderer without exposing Node or ipcRenderer.

import { contextBridge, ipcRenderer } from "electron";

contextBridge.exposeInMainWorld("texera", {
  dashboards: () => ipcRenderer.invoke("dashboards"),
  state: () => ipcRenderer.invoke("state"),
  setObservability: (value: boolean) => ipcRenderer.invoke("set-observability", value),
  start: () => ipcRenderer.invoke("start"),
  stop: () => ipcRenderer.invoke("stop"),
  open: (url: string) => ipcRenderer.invoke("open", url),
  openDockerInstall: () => ipcRenderer.invoke("open-docker-install")
});
