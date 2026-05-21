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

import { TestBed } from "@angular/core/testing";
import { HttpClientTestingModule, HttpTestingController } from "@angular/common/http/testing";
import { WorkflowStatsService } from "./workflow-stats.service";
import { AppSettings } from "../../../common/app-setting";

describe("WorkflowStatsService", () => {
  let service: WorkflowStatsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
    });
    service = TestBed.inject(WorkflowStatsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it("issues GET /stats/{name} with workflowId and time range", () => {
    service.fetch("runsPerDay", 42, "2026-01-01T00:00:00Z", "2026-01-07T00:00:00Z").subscribe();
    const expected = `${AppSettings.getApiEndpoint()}/stats/runsPerDay`;
    const req = httpMock.expectOne(r => r.url === expected);
    expect(req.request.method).toBe("GET");
    expect(req.request.params.get("workflowId")).toBe("42");
    expect(req.request.params.get("from")).toBe("2026-01-01T00:00:00Z");
    expect(req.request.params.get("to")).toBe("2026-01-07T00:00:00Z");
    req.flush({ name: "runsPerDay", points: [] });
  });

  it("only fetches one of the three allowlisted names", () => {
    // The function signature constrains `name` to the literal union, so the
    // following line is a *type-only* assertion at compile time. We still
    // exercise each name at runtime to catch typos.
    (["runsPerDay", "failureRate", "p95Duration"] as const).forEach(name => {
      service.fetch(name, 1, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z").subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith(`/stats/${name}`));
      req.flush({ name, points: [] });
    });
  });
});
