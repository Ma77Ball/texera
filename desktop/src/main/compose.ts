// Pure helpers for building `docker compose` commands and parsing their output.
// Kept free of Electron and child_process so it is unit-testable in plain Node.

export interface ComposeOptions {
  projectName: string;
  composeDir: string;
  withObservability: boolean;
}

export interface ContainerHealth {
  name: string;
  service: string;
  state: string;
  health: string;
}

export type StackStatus = "stopped" | "starting" | "running";

// The compose files to layer, in order; the observability overlay is optional.
export function composeFiles(withObservability: boolean): string[] {
  const files = ["docker-compose.yml"];
  if (withObservability) files.push("docker-compose.observability.yml");
  return files;
}

// Build the argument list for `docker <args>` given a compose sub-command.
export function buildComposeArgs(opts: ComposeOptions, sub: string[]): string[] {
  const args = ["compose", "-p", opts.projectName];
  for (const file of composeFiles(opts.withObservability)) {
    args.push("-f", file);
  }
  return args.concat(sub);
}

// Parse `docker compose ps --format json` which emits either NDJSON or a JSON array.
export function parseComposePs(stdout: string): ContainerHealth[] {
  const lines = stdout
    .split("\n")
    .map(line => line.trim())
    .filter(line => line.length > 0);
  const rows: Record<string, unknown>[] = [];
  if (lines.length === 1 && lines[0].startsWith("[")) {
    for (const row of JSON.parse(lines[0])) rows.push(row);
  } else {
    for (const line of lines) {
      try {
        rows.push(JSON.parse(line));
      } catch {
        // ignore non-JSON lines
      }
    }
  }
  return rows.map(row => ({
    name: String(row.Name ?? row.name ?? ""),
    service: String(row.Service ?? row.service ?? ""),
    state: String(row.State ?? row.state ?? ""),
    health: String(row.Health ?? row.health ?? "")
  }));
}

// Collapse per-container health into one stack status for the UI.
export function overallStatus(containers: ContainerHealth[]): StackStatus {
  const running = containers.filter(c => c.state === "running");
  if (running.length === 0) return "stopped";
  const anyStarting = running.some(c => c.health === "starting");
  return anyStarting ? "starting" : "running";
}
