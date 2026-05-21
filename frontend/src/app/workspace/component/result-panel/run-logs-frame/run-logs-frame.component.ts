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
import { CommonModule, DatePipe } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { UntilDestroy, untilDestroyed } from "@ngneat/until-destroy";
import {
  RunLogEntry,
  RunLogLevel,
  RunLogsRequest,
  RunLogsService,
} from "../../../service/run-logs/run-logs.service";

/**
 * Render the structured logs OpenSearch indexed for one execution.
 *
 * The component renders log bodies via Angular text interpolation only —
 * no `[innerHTML]`, no `bypassSecurityTrustHtml`. The backend already
 * strips control characters from log bodies, but we treat them as
 * untrusted text on the UI side regardless.
 */
@UntilDestroy()
@Component({
  selector: "texera-run-logs-frame",
  templateUrl: "./run-logs-frame.component.html",
  styleUrls: ["./run-logs-frame.component.scss"],
  standalone: true,
  imports: [CommonModule, FormsModule, DatePipe],
})
export class RunLogsFrameComponent implements OnChanges {
  @Input() executionId?: number;
  /** ISO-8601 lower bound; defaults to 1 hour ago when not supplied. */
  @Input() from?: string;
  /** ISO-8601 upper bound; defaults to now when not supplied. */
  @Input() to?: string;

  readonly levels: ReadonlyArray<RunLogLevel | ""> = ["", "TRACE", "DEBUG", "INFO", "WARN", "ERROR"];

  selectedLevel: RunLogLevel | "" = "";
  filterText = "";

  entries: RunLogEntry[] = [];
  total = 0;
  loading = false;
  errorMessage = "";

  constructor(private runLogsService: RunLogsService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes.executionId && this.executionId != null) {
      this.refresh();
    }
  }

  applyFilters(): void {
    this.refresh();
  }

  /** Routes to the embedded Jaeger UI; component lands in PR 8. */
  traceLinkFor(entry: RunLogEntry): string | null {
    if (!entry.traceId) {
      return null;
    }
    return `trace?id=${encodeURIComponent(entry.traceId)}`;
  }

  /** Stable key for *ngFor; (timestamp, body) is unique enough in practice. */
  trackByEntry(_idx: number, entry: RunLogEntry): string {
    return `${entry.timestamp}|${entry.body.slice(0, 64)}`;
  }

  private refresh(): void {
    if (this.executionId == null) {
      return;
    }
    const now = new Date();
    const oneHourAgo = new Date(now.getTime() - 60 * 60 * 1000);
    const req: RunLogsRequest = {
      executionId: this.executionId,
      from: this.from ?? oneHourAgo.toISOString(),
      to: this.to ?? now.toISOString(),
      level: this.selectedLevel === "" ? undefined : this.selectedLevel,
      q: this.filterText.trim() || undefined,
      size: 200,
    };
    this.loading = true;
    this.errorMessage = "";
    this.runLogsService
      .fetch(req)
      .pipe(untilDestroyed(this))
      .subscribe({
        next: page => {
          this.entries = page.entries;
          this.total = page.total;
          this.loading = false;
        },
        error: err => {
          this.entries = [];
          this.total = 0;
          this.loading = false;
          this.errorMessage =
            err?.status === 403
              ? "You do not have access to this execution's logs."
              : err?.status === 429
                ? "Too many requests. Please wait a moment and retry."
                : "Failed to load logs.";
        },
      });
  }
}
