// Interactive pseudo-terminal sessions backed by node-pty, one per developer terminal/log/stats tab.

import * as pty from "node-pty";

export interface PtySpawn {
  file: string;
  args: string[];
  cwd: string;
  env: NodeJS.ProcessEnv;
  cols: number;
  rows: number;
}

const sessions = new Map<number, pty.IPty>();
let nextId = 1;

export function createSession(
  spec: PtySpawn,
  onData: (id: number, data: string) => void,
  onExit: (id: number, code: number) => void
): number {
  const id = nextId++;
  const term = pty.spawn(spec.file, spec.args, {
    name: "xterm-256color",
    cols: spec.cols || 80,
    rows: spec.rows || 24,
    cwd: spec.cwd,
    env: spec.env as { [key: string]: string }
  });
  sessions.set(id, term);
  term.onData(d => onData(id, d));
  term.onExit(e => {
    sessions.delete(id);
    onExit(id, e.exitCode);
  });
  return id;
}

export function writeSession(id: number, data: string): void {
  sessions.get(id)?.write(data);
}

export function resizeSession(id: number, cols: number, rows: number): void {
  try {
    sessions.get(id)?.resize(Math.max(1, cols), Math.max(1, rows));
  } catch {
    // a resize on a just-exited pty throws; ignore
  }
}

export function killSession(id: number): void {
  const term = sessions.get(id);
  if (!term) return;
  try {
    term.kill();
  } catch {
    // already gone
  }
  sessions.delete(id);
}

export function killAll(): void {
  sessions.forEach(term => {
    try {
      term.kill();
    } catch {
      // ignore
    }
  });
  sessions.clear();
}
