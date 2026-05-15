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

import { Subject } from "rxjs";
import { DebuggerFrameComponent } from "./debugger-frame.component";
import {
  BreakpointFault,
  BreakpointFaultEvent,
  TexeraWebsocketEventTypes,
  TexeraWebsocketRequestTypes,
  TexeraWebsocketRequestTypeMap,
} from "../../../types/workflow-websocket.interface";
import { WorkflowWebsocketService } from "../../../service/workflow-websocket/workflow-websocket.service";

/**
 * Minimal stand-in for the websocket service that lets a test push events
 * into the component and capture the requests it sends. Keeps the spec
 * fast and free of Angular TestBed setup.
 */
class StubWebsocketService {
  readonly event$ = new Subject<{ type: "BreakpointFaultEvent" } & BreakpointFaultEvent>();
  readonly sent: Array<{ type: TexeraWebsocketRequestTypes; payload: unknown }> = [];

  subscribeToEvent(_type: TexeraWebsocketEventTypes) {
    return this.event$.asObservable();
  }

  send<T extends TexeraWebsocketRequestTypes>(
    type: T,
    payload: TexeraWebsocketRequestTypeMap[T]
  ): void {
    this.sent.push({ type, payload });
  }
}

function mkFault(workerName: string, fields: string[]): BreakpointFault {
  return {
    workerName,
    faultedTuple: { id: 0, isInput: true, tuple: fields },
  };
}

function newComponent(): { component: DebuggerFrameComponent; ws: StubWebsocketService } {
  const ws = new StubWebsocketService();
  const component = new DebuggerFrameComponent(
    ws as unknown as WorkflowWebsocketService
  );
  component.ngOnInit();
  return { component, ws };
}

describe("DebuggerFrameComponent", () => {
  it("shows the empty state when no faults have arrived", () => {
    const { component } = newComponent();
    expect(component.hasNoFaults()).toBe(true);
  });

  it("appends faults from a BreakpointFaultEvent into the operator entry", () => {
    const { component, ws } = newComponent();
    const fault = mkFault("Worker:WF1-E1-filter-main-0", ["ada", "42"]);
    ws.event$.next({ type: "BreakpointFaultEvent", operatorId: "E1-filter", newFaults: [fault] });
    expect(component.hasNoFaults()).toBe(false);
    expect(component.visibleOperators()).toEqual([
      { operatorId: "E1-filter", faults: [fault] },
    ]);
  });

  it("appends across multiple events without dropping prior faults", () => {
    // Pin: the bridge guarantees newFaults is the delta. The component
    // must accumulate rather than replace, otherwise the panel would show
    // only the most recent fault after every new arrival.
    const { component, ws } = newComponent();
    const first = mkFault("Worker:WF1-E1-filter-main-0", ["ada"]);
    const second = mkFault("Worker:WF1-E1-filter-main-0", ["bob"]);
    ws.event$.next({ type: "BreakpointFaultEvent", operatorId: "E1-filter", newFaults: [first] });
    ws.event$.next({ type: "BreakpointFaultEvent", operatorId: "E1-filter", newFaults: [second] });
    expect(component.visibleOperators()[0].faults).toEqual([first, second]);
  });

  it("filters visible operators when @Input() operatorId is set", () => {
    const { component, ws } = newComponent();
    component.operatorId = "E1-filter";
    ws.event$.next({
      type: "BreakpointFaultEvent",
      operatorId: "E1-filter",
      newFaults: [mkFault("Worker:WF1-E1-filter-main-0", ["x"])],
    });
    ws.event$.next({
      type: "BreakpointFaultEvent",
      operatorId: "E1-mapper",
      newFaults: [mkFault("Worker:WF1-E1-mapper-main-0", ["y"])],
    });
    const visible = component.visibleOperators();
    expect(visible.length).toBe(1);
    expect(visible[0].operatorId).toBe("E1-filter");
  });

  it("onRetry sends a RetryRequest with the workers list and clears the entry", () => {
    const { component, ws } = newComponent();
    const fault1 = mkFault("Worker:WF1-E1-filter-main-0", ["x"]);
    const fault2 = mkFault("Worker:WF1-E1-filter-main-1", ["y"]);
    ws.event$.next({
      type: "BreakpointFaultEvent",
      operatorId: "E1-filter",
      newFaults: [fault1, fault2],
    });
    component.onRetry("E1-filter");
    expect(ws.sent).toEqual([
      {
        type: "RetryRequest",
        payload: { workers: ["Worker:WF1-E1-filter-main-0", "Worker:WF1-E1-filter-main-1"] },
      },
    ]);
    expect(component.hasNoFaults()).toBe(true);
  });

  it("onRetry does nothing when the operator has no recorded faults", () => {
    // Defensive: clicking Retry on a stale operator id (e.g. the bridge
    // already cleared it) must not send an empty workers array — the
    // engine treats that as "retry every worker", which is wrong.
    const { component, ws } = newComponent();
    component.onRetry("E1-filter");
    expect(ws.sent).toEqual([]);
  });
});
