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

import { Injectable } from "@angular/core";
import { HttpClient, HttpParams } from "@angular/common/http";
import { Observable } from "rxjs";
import { AppSettings } from "../../../common/app-setting";

export type WorkflowStatsName = "runsPerDay" | "failureRate" | "p95Duration";

export interface WorkflowStatsPoint {
  at: string;
  value: number;
}

export interface WorkflowStatsResponse {
  name: WorkflowStatsName;
  points: WorkflowStatsPoint[];
}

const STATS_API_BASE = `${AppSettings.getApiEndpoint()}/stats`;

@Injectable({ providedIn: "root" })
export class WorkflowStatsService {
  constructor(private http: HttpClient) {}

  /**
   * Fetch a named stats series. Callers may only pass one of the literal
   * {@link WorkflowStatsName} values — the server enforces the same
   * allowlist, so passing anything else fails with 400 anyway.
   */
  fetch(
    name: WorkflowStatsName,
    workflowId: number,
    from: string,
    to: string
  ): Observable<WorkflowStatsResponse> {
    const params = new HttpParams()
      .set("workflowId", String(workflowId))
      .set("from", from)
      .set("to", to);
    return this.http.get<WorkflowStatsResponse>(`${STATS_API_BASE}/${name}`, { params });
  }
}
