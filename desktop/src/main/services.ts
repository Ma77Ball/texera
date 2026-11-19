// Static metadata: which dashboards to offer and which containers to show health for.

export interface DashboardLink {
  key: string;
  label: string;
  url: string;
}

// One-click launch targets shown in the control panel.
export const DASHBOARDS: DashboardLink[] = [
  { key: "texera", label: "Texera UI", url: "http://localhost" },
  { key: "grafana", label: "Grafana (metrics + logs)", url: "http://localhost:3000" },
  { key: "jaeger", label: "Jaeger (traces)", url: "http://localhost:16686" },
  { key: "parca", label: "Parca (CPU profiles)", url: "http://localhost:7070" },
  { key: "victoriametrics", label: "VictoriaMetrics", url: "http://localhost:8428" }
];

export interface ServiceInfo {
  group: string;
  label: string;
}

// Groups rendered in this order; only non-empty ones are shown.
export const SERVICE_GROUP_ORDER = [
  "Core services",
  "Workflow engine",
  "AI & notebooks",
  "Data & storage",
  "Observability"
];

// First keyword that appears in a service name decides its group; default is Core.
const GROUP_RULES: { group: string; keywords: string[] }[] = [
  { group: "Observability", keywords: ["grafana", "jaeger", "parca", "loki", "victoria", "otel", "prometheus", "tempo", "mimir"] },
  { group: "Data & storage", keywords: ["postgres", "minio", "rustfs", "lakefs", "lakekeeper", "iceberg", "redis", "mysql", "zookeeper", "kafka"] },
  { group: "Workflow engine", keywords: ["workflow", "computing-unit", "runtime-coordinator", "amber", "compiling"] },
  { group: "AI & notebooks", keywords: ["litellm", "agent", "jupyter", "notebook", "llm"] }
];

// Words that should keep a specific casing instead of plain title-case.
const SPECIAL: Record<string, string> = {
  victoriametrics: "VictoriaMetrics", litellm: "LiteLLM", postgres: "Postgres", rustfs: "RustFS",
  lakefs: "LakeFS", lakekeeper: "Lakekeeper", minio: "MinIO", jupyter: "Jupyter", otel: "OpenTelemetry", ui: "UI", db: "DB"
};

// Turn a raw compose service name into a human label, e.g. access-control-service -> Access Control.
export function prettyServiceName(name: string): string {
  const base = name.replace(/^texera[-_]/i, "").replace(/[-_]service$/i, "");
  if (SPECIAL[base.toLowerCase()]) return SPECIAL[base.toLowerCase()];
  return base
    .split(/[-_]/)
    .filter(Boolean)
    .map(word => SPECIAL[word.toLowerCase()] || word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

// Map a raw service name to its group and human label.
export function classifyService(name: string): ServiceInfo {
  const lower = name.toLowerCase();
  for (const rule of GROUP_RULES) {
    if (rule.keywords.some(k => lower.includes(k))) {
      return { group: rule.group, label: prettyServiceName(name) };
    }
  }
  return { group: "Core services", label: prettyServiceName(name) };
}
