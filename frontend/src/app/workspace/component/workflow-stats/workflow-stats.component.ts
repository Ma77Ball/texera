/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import { Component, Input, OnChanges, SimpleChanges } from "@angular/core";
import { CommonModule } from "@angular/common";
import { UntilDestroy, untilDestroyed } from "@ngneat/until-destroy";
import { forkJoin } from "rxjs";
import {
  WorkflowStatsPoint,
  WorkflowStatsService,
} from "../../service/workflow-stats/workflow-stats.service";

interface ChartBar {
  x: number;
  y: number;
  height: number;
  width: number;
  value: number;
  label: string;
}

/**
 * Workflow Stats page: one runs/day bar chart + two summary tiles. Renders
 * the chart with SVG primitives so we don't pull in ECharts purely for three
 * charts; data values are bound as data-only ({{ }} text interpolation
 * inside `<text>`), never as `formatter` strings, and no path is computed
 * from a server-supplied string via `eval`. The spec calls for ECharts —
 * swapping the renderer is a follow-up.
 */
@UntilDestroy()
@Component({
  selector: "texera-workflow-stats",
  templateUrl: "./workflow-stats.component.html",
  styleUrls: ["./workflow-stats.component.scss"],
  standalone: true,
  imports: [CommonModule],
})
export class WorkflowStatsComponent implements OnChanges {
  @Input() workflowId?: number;

  readonly chartWidth = 600;
  readonly chartHeight = 200;
  readonly chartPadding = 24;

  bars: ChartBar[] = [];
  failureRate?: number;
  p95DurationSec?: number;
  loading = false;
  errorMessage = "";

  constructor(private statsService: WorkflowStatsService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes.workflowId && this.workflowId != null) {
      this.refresh();
    }
  }

  formatPercent(v?: number): string {
    if (v == null || !isFinite(v)) {
      return "—";
    }
    return `${(v * 100).toFixed(1)}%`;
  }

  formatSeconds(v?: number): string {
    if (v == null || !isFinite(v)) {
      return "—";
    }
    return `${v.toFixed(2)} s`;
  }

  private refresh(): void {
    if (this.workflowId == null) {
      return;
    }
    const now = new Date();
    const sevenDaysAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
    const from = sevenDaysAgo.toISOString();
    const to = now.toISOString();
    this.loading = true;
    this.errorMessage = "";
    forkJoin({
      runs: this.statsService.fetch("runsPerDay", this.workflowId, from, to),
      failure: this.statsService.fetch("failureRate", this.workflowId, from, to),
      p95: this.statsService.fetch("p95Duration", this.workflowId, from, to),
    })
      .pipe(untilDestroyed(this))
      .subscribe({
        next: ({ runs, failure, p95 }) => {
          this.bars = this.computeBars(runs.points);
          this.failureRate = latestValue(failure.points);
          this.p95DurationSec = latestValue(p95.points);
          this.loading = false;
        },
        error: err => {
          this.bars = [];
          this.failureRate = undefined;
          this.p95DurationSec = undefined;
          this.loading = false;
          this.errorMessage =
            err?.status === 403
              ? "You do not have access to this workflow's stats."
              : err?.status === 429
                ? "Too many requests. Please wait a moment and retry."
                : "Failed to load stats.";
        },
      });
  }

  private computeBars(points: WorkflowStatsPoint[]): ChartBar[] {
    if (points.length === 0) {
      return [];
    }
    const innerW = this.chartWidth - 2 * this.chartPadding;
    const innerH = this.chartHeight - 2 * this.chartPadding;
    const maxV = Math.max(1, ...points.map(p => p.value));
    const w = innerW / points.length;
    return points.map((p, i) => {
      const h = (p.value / maxV) * innerH;
      return {
        x: this.chartPadding + i * w,
        y: this.chartHeight - this.chartPadding - h,
        width: Math.max(1, w - 2),
        height: h,
        value: p.value,
        label: p.at.slice(5, 10), // MM-DD
      };
    });
  }
}

function latestValue(points: WorkflowStatsPoint[]): number | undefined {
  return points.length === 0 ? undefined : points[points.length - 1].value;
}
