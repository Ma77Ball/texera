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

// Core containers whose health the panel surfaces; others are hidden to reduce noise.
export const CORE_SERVICES: string[] = [
  "dashboard-service",
  "file-service",
  "access-control-service",
  "config-service",
  "workflow-compiling-service",
  "workflow-computing-unit-managing-service",
  "workflow-runtime-coordinator-service",
  "texera-postgres"
];
