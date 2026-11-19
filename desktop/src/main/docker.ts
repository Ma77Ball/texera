// Thin wrapper that runs docker commands and maps them onto the pure helpers.

import { execFile } from "node:child_process";
import {
  buildComposeArgs,
  ComposeOptions,
  ContainerHealth,
  overallStatus,
  parseComposePs,
  StackStatus
} from "./compose";

export interface StackState {
  dockerInstalled: boolean;
  dockerRunning: boolean;
  status: StackStatus;
  containers: ContainerHealth[];
}

// Packaged apps (AppImage, .app) launch with a trimmed PATH; add the usual docker locations.
export function dockerEnv(): NodeJS.ProcessEnv {
  const extra = ["/usr/local/bin", "/usr/bin", "/opt/homebrew/bin", "/snap/bin"];
  const current = (process.env.PATH || "").split(":");
  const merged = Array.from(new Set(current.concat(extra))).filter(Boolean).join(":");
  return { ...process.env, PATH: merged };
}

// Run a command and resolve with its stdout, rejecting on a non-zero exit.
function run(cmd: string, args: string[], timeoutMs = 20000): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout: timeoutMs, windowsHide: true, env: dockerEnv() }, (err, stdout, stderr) => {
      if (err) reject(new Error(stderr || err.message));
      else resolve(stdout);
    });
  });
}

// Whether the docker CLI is on PATH.
export async function dockerInstalled(): Promise<boolean> {
  try {
    await run("docker", ["--version"], 8000);
    return true;
  } catch {
    return false;
  }
}

// Whether the docker daemon answers (Docker Desktop is up).
export async function dockerRunning(): Promise<boolean> {
  try {
    await run("docker", ["info", "--format", "{{.ServerVersion}}"], 8000);
    return true;
  } catch {
    return false;
  }
}

export async function composeUp(opts: ComposeOptions): Promise<void> {
  await run("docker", buildComposeArgs(opts, ["up", "-d"]), 600000);
}

export async function composeDown(opts: ComposeOptions): Promise<void> {
  await run("docker", buildComposeArgs(opts, ["down"]), 120000);
}

// Lifecycle for a single service: restart, stop, or start.
export async function serviceAction(opts: ComposeOptions, service: string, action: "restart" | "stop" | "start"): Promise<void> {
  const sub = action === "start" ? ["up", "-d", service] : [action, service];
  await run("docker", buildComposeArgs(opts, sub), 120000);
}

// Recent logs for one service, for the developer view.
export async function composeLogs(opts: ComposeOptions, service: string, tail = 300): Promise<string> {
  try {
    return await run("docker", buildComposeArgs(opts, ["logs", "--no-color", "--tail", String(tail), service]), 15000);
  } catch (e) {
    return String(e instanceof Error ? e.message : e);
  }
}

// Query per-container health; empty when the stack is down.
export async function composePs(opts: ComposeOptions): Promise<ContainerHealth[]> {
  try {
    const out = await run("docker", buildComposeArgs(opts, ["ps", "--format", "json"]), 15000);
    return parseComposePs(out);
  } catch {
    return [];
  }
}

// One call the UI polls for the whole picture.
export async function getStackState(opts: ComposeOptions): Promise<StackState> {
  const installed = await dockerInstalled();
  if (!installed) {
    return { dockerInstalled: false, dockerRunning: false, status: "stopped", containers: [] };
  }
  const running = await dockerRunning();
  if (!running) {
    return { dockerInstalled: true, dockerRunning: false, status: "stopped", containers: [] };
  }
  const containers = await composePs(opts);
  return {
    dockerInstalled: true,
    dockerRunning: true,
    status: overallStatus(containers),
    containers
  };
}
