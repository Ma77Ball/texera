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

import { Component, Input, OnInit } from "@angular/core";
import { UntilDestroy, untilDestroyed } from "@ngneat/until-destroy";
import { NgIf, NgFor } from "@angular/common";
import { WorkflowWebsocketService } from "../../../service/workflow-websocket/workflow-websocket.service";
import { BreakpointFault } from "../../../types/workflow-websocket.interface";

/**
 * Live Debugger panel. Subscribes to `BreakpointFaultEvent` over the
 * websocket and appends each new fault to the per-operator list. Step 2
 * surfaces only what's needed for the minimum viable end-to-end win:
 *
 *   - the failing tuple's stringified fields
 *   - the worker id that hit the exception
 *   - a single [Retry] button per operator that re-runs the current tuple
 *
 * Editing tuples, stepping back/forward, and operator-state inspection
 * land in later steps; their hooks (BreakpointFault has space for them
 * already) are deliberately not surfaced here.
 */
@UntilDestroy()
@Component({
  selector: "texera-debugger-frame",
  templateUrl: "./debugger-frame.component.html",
  styleUrls: ["./debugger-frame.component.scss"],
  imports: [NgIf, NgFor],
})
export class DebuggerFrameComponent implements OnInit {
  /** Optional filter: show only faults for this operator. */
  @Input() operatorId?: string;

  /** Operator id → ordered list of faults received since the run started. */
  faultsByOperator: Map<string, BreakpointFault[]> = new Map();

  constructor(private websocketService: WorkflowWebsocketService) {}

  ngOnInit(): void {
    this.websocketService
      .subscribeToEvent("BreakpointFaultEvent")
      .pipe(untilDestroyed(this))
      .subscribe(event => {
        const existing = this.faultsByOperator.get(event.operatorId) ?? [];
        // The bridge guarantees `newFaults` is the delta; we append rather
        // than replace so the panel keeps prior history while the run is paused.
        this.faultsByOperator.set(event.operatorId, [...existing, ...event.newFaults]);
      });
  }

  /** Operator entries that match the current filter (or all, if unfiltered). */
  visibleOperators(): Array<{ operatorId: string; faults: ReadonlyArray<BreakpointFault> }> {
    return Array.from(this.faultsByOperator.entries())
      .filter(([opId]) => this.operatorId === undefined || opId === this.operatorId)
      .map(([opId, faults]) => ({ operatorId: opId, faults }));
  }

  /**
   * Retry the failing tuple on every worker that reported a fault for this
   * operator. Reuses the existing `RetryRequest` websocket message; the
   * controller-side `RetryWorkflowHandler` calls `retry_current_tuple` per
   * worker and resumes the workflow.
   */
  onRetry(operatorId: string): void {
    const faults = this.faultsByOperator.get(operatorId) ?? [];
    const workers = faults.map(f => f.workerName);
    if (workers.length === 0) {
      return;
    }
    this.websocketService.send("RetryRequest", { workers });
    // Optimistically clear the panel entry for this operator. If the retry
    // hits the same exception again, the bridge re-sends a fresh event.
    this.faultsByOperator.delete(operatorId);
  }

  /** True when there are no faults to display. Drives the empty state. */
  hasNoFaults(): boolean {
    return this.visibleOperators().length === 0;
  }
}
