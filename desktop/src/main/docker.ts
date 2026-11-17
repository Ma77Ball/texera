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

// Run a command and resolve with its stdout, rejecting on a non-zero exit.
function run(cmd: string, args: string[], timeoutMs = 20000): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout: timeoutMs, windowsHide: true }, (err, stdout, stderr) => {
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
