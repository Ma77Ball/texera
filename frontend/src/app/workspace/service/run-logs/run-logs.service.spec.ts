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
import { RunLogsPage, RunLogsService } from "./run-logs.service";
import { AppSettings } from "../../../common/app-setting";

describe("RunLogsService", () => {
  let service: RunLogsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
    });
    service = TestBed.inject(RunLogsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it("issues GET /executions/{id}/logs with only the supplied query params", () => {
    service
      .fetch({
        executionId: 42,
        from: "2026-01-01T00:00:00Z",
        to: "2026-01-01T01:00:00Z",
        level: "INFO",
        q: "  hello  ",
        size: 100,
      })
      .subscribe();

    const expectedUrl = `${AppSettings.getApiEndpoint()}/executions/42/logs`;
    const req = httpMock.expectOne(r => r.url === expectedUrl);
    expect(req.request.method).toBe("GET");
    expect(req.request.params.get("from")).toBe("2026-01-01T00:00:00Z");
    expect(req.request.params.get("to")).toBe("2026-01-01T01:00:00Z");
    expect(req.request.params.get("level")).toBe("INFO");
    // Trims `q` before sending.
    expect(req.request.params.get("q")).toBe("hello");
    expect(req.request.params.get("size")).toBe("100");
    // No raw DSL keys leak into the URL.
    expect(req.request.params.has("cursor")).toBe(false);
    req.flush({ entries: [], total: 0 } as RunLogsPage);
  });

  it("omits blank q and undefined level/size/cursor", () => {
    service
      .fetch({
        executionId: 1,
        from: "2026-01-01T00:00:00Z",
        to: "2026-01-01T00:01:00Z",
        q: "   ",
      })
      .subscribe();

    const req = httpMock.expectOne(r => r.url.endsWith("/executions/1/logs"));
    expect(req.request.params.has("q")).toBe(false);
    expect(req.request.params.has("level")).toBe(false);
    expect(req.request.params.has("size")).toBe(false);
    expect(req.request.params.has("cursor")).toBe(false);
    req.flush({ entries: [], total: 0 } as RunLogsPage);
  });

  it("forwards an opaque server-issued cursor verbatim", () => {
    service
      .fetch({
        executionId: 1,
        from: "2026-01-01T00:00:00Z",
        to: "2026-01-01T00:01:00Z",
        cursor: '[1,"abc"]',
      })
      .subscribe();

    const req = httpMock.expectOne(r => r.url.endsWith("/executions/1/logs"));
    expect(req.request.params.get("cursor")).toBe('[1,"abc"]');
    req.flush({ entries: [], total: 0 } as RunLogsPage);
  });
});
