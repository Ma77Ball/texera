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

export type RunLogLevel = "TRACE" | "DEBUG" | "INFO" | "WARN" | "ERROR";

export interface RunLogEntry {
  timestamp: string;
  level: string;
  body: string;
  traceId?: string;
  spanId?: string;
}

export interface RunLogsPage {
  entries: RunLogEntry[];
  total: number;
  nextCursor?: string;
}

export interface RunLogsRequest {
  executionId: number;
  from: string;
  to: string;
  level?: RunLogLevel;
  q?: string;
  size?: number;
  cursor?: string;
}

const RUN_LOGS_API_BASE = `${AppSettings.getApiEndpoint()}/executions`;

@Injectable({ providedIn: "root" })
export class RunLogsService {
  constructor(private http: HttpClient) {}

  /**
   * Fetch a page of structured logs for one execution.
   *
   * The backend enforces ownership, an input-size cap, and a per-user rate
   * limit; callers should treat 403 / 429 / 400 as terminal and surface them
   * to the user. The request shape is intentionally minimal — there is no
   * way to pass through a raw OpenSearch DSL.
   */
  fetch(req: RunLogsRequest): Observable<RunLogsPage> {
    let params = new HttpParams().set("from", req.from).set("to", req.to);
    if (req.level) {
      params = params.set("level", req.level);
    }
    if (req.q && req.q.trim().length > 0) {
      params = params.set("q", req.q.trim());
    }
    if (req.size != null) {
      params = params.set("size", String(req.size));
    }
    if (req.cursor) {
      params = params.set("cursor", req.cursor);
    }
    return this.http.get<RunLogsPage>(`${RUN_LOGS_API_BASE}/${req.executionId}/logs`, { params });
  }
}
