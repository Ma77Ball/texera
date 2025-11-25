import { Injectable } from "@angular/core";
import { createClient, SupabaseClient } from "@supabase/supabase-js";

@Injectable({
  providedIn: "root",
})
export class SupabaseService {
  private supabase: SupabaseClient;

  constructor() {
    const supabaseUrl = "https://vctlhhfucuylvmpzoewr.supabase.co";
    const supabaseKey = ""
    this.supabase = createClient(supabaseUrl, supabaseKey);
  }

  async saveOperatorStats(data: {
    operator_id: string;
    cpu_start: number;
    cpu_avg: number;
    cpu_max: number;
    cpu_min: number;
    cpu_end: number;
    mem_start: number;
    mem_avg: number;
    mem_max: number;
    mem_min: number;
    mem_end: number;
  }) {
    const { error } = await this.supabase.from("operator_statistics").insert([data]);
    if (error) {
      console.error("Supabase insert error:", error);
    } else {
      console.log("Stats saved to Supabase");
    }
  }
  async saveWorkflowStats(data: {
    workflow_id: number | undefined;
    cpu_start: number;
    cpu_avg: number;
    cpu_max: number;
    cpu_min: number;
    cpu_end: number;
    mem_start: number;
    mem_avg: number;
    mem_max: number;
    mem_min: number;
    mem_end: number;
  }) {
    const { error } = await this.supabase.from("workflow_statistics").insert([data]);
    if (error) {
      console.error("Supabase insert error:", error);
    } else {
      console.log("Stats saved to workflow Supabase");
    }
  }

  private average(values: number[]): number {
    if (values.length === 0) return 0;
    return values.reduce((sum, val) => sum + val, 0) / values.length;
  }

  async getAggregatedWorkflowStats(workflowId: number) {
    const { data, error } = await this.supabase
      .from("workflow_statistics")
      .select("cpu_start, cpu_avg, cpu_max, cpu_end, mem_start, mem_avg, mem_max, mem_end")
      .eq("workflow_id", workflowId);

    if (error) {
      console.error("Supabase fetch error:", error);
      return null;
    }

    if (!data || data.length === 0) {
      console.warn("No workflow stats found for workflow_id:", workflowId);
      return null;
    }

    const cpuStart = data.map(d => Number(d.cpu_start));
    const cpuAvg = data.map(d => Number(d.cpu_avg));
    const cpuMax = data.map(d => Number(d.cpu_max));
    const cpuEnd = data.map(d => Number(d.cpu_end));
    const memStart = data.map(d => Number(d.mem_start));
    const memAvg = data.map(d => Number(d.mem_avg));
    const memMax = data.map(d => Number(d.mem_max));
    const memEnd = data.map(d => Number(d.mem_end));

    return {
      maxCpuUsage: Math.max(...cpuMax),
      avgCpuUsage: this.average(cpuAvg),
      startCpuUsage: this.average(cpuStart),
      endCpuUsage: this.average(cpuEnd),
      maxMemUsage: Math.max(...memMax),
      avgMemUsage: this.average(memAvg),
      startMemUsage: this.average(memStart),
      endMemUsage: this.average(memEnd),
    };
  }
}
