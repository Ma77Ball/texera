// Bridges a minimal, typed API to the renderer without exposing Node or ipcRenderer.

import { contextBridge, ipcRenderer } from "electron";

contextBridge.exposeInMainWorld("texera", {
  dashboards: () => ipcRenderer.invoke("dashboards"),
  state: () => ipcRenderer.invoke("state"),
  setObservability: (value: boolean) => ipcRenderer.invoke("set-observability", value),
  start: () => ipcRenderer.invoke("start"),
  stop: () => ipcRenderer.invoke("stop"),
  logs: (service: string) => ipcRenderer.invoke("logs", service),
  serviceAction: (service: string, action: string) => ipcRenderer.invoke("service-action", { service, action }),
  copy: (text: string) => ipcRenderer.invoke("copy", text),
  ptyCreate: (kind: string, service: string, cols: number, rows: number) =>
    ipcRenderer.invoke("pty:create", { kind, service, cols, rows }),
  ptyInput: (id: number, data: string) => ipcRenderer.send("pty:input", { id, data }),
  ptyResize: (id: number, cols: number, rows: number) => ipcRenderer.send("pty:resize", { id, cols, rows }),
  ptyKill: (id: number) => ipcRenderer.send("pty:kill", { id }),
  onPtyData: (cb: (o: { id: number; data: string }) => void) => ipcRenderer.on("pty:data", (_e, o) => cb(o)),
  onPtyExit: (cb: (o: { id: number; code: number }) => void) => ipcRenderer.on("pty:exit", (_e, o) => cb(o)),
  open: (url: string) => ipcRenderer.invoke("open", url),
  openDockerInstall: () => ipcRenderer.invoke("open-docker-install"),
  minimize: () => ipcRenderer.invoke("win-minimize"),
  toggleMaximize: () => ipcRenderer.invoke("win-toggle-maximize"),
  close: () => ipcRenderer.invoke("win-close"),
  onWindowState: (cb: (s: { maximized: boolean }) => void) =>
    ipcRenderer.on("win-state", (_e, s) => cb(s))
});
