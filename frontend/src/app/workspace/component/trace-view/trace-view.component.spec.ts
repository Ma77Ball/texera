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

import { ComponentFixture, TestBed } from "@angular/core/testing";
import { JAEGER_BASE_URL, TraceViewComponent } from "./trace-view.component";

describe("TraceViewComponent", () => {
  let fixture: ComponentFixture<TraceViewComponent>;
  let component: TraceViewComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TraceViewComponent],
      providers: [{ provide: JAEGER_BASE_URL, useValue: "https://jaeger.example.com" }],
    }).compileComponents();
    fixture = TestBed.createComponent(TraceViewComponent);
    component = fixture.componentInstance;
  });

  function setTraceIdAndDetect(value: string | undefined) {
    component.traceId = value;
    component.ngOnChanges({
      traceId: { currentValue: value, previousValue: undefined, firstChange: true, isFirstChange: () => true },
    });
    fixture.detectChanges();
  }

  it("renders the iframe for a well-formed 32-hex trace id", () => {
    setTraceIdAndDetect("00112233445566778899aabbccddeeff");
    expect(component.trustedSrc).not.toBeNull();
    expect(component.errorMessage).toBeNull();
    const iframe: HTMLIFrameElement | null = fixture.nativeElement.querySelector("iframe");
    expect(iframe).not.toBeNull();
    expect(iframe!.getAttribute("sandbox")).toBe("allow-scripts allow-same-origin");
    expect(iframe!.getAttribute("referrerpolicy")).toBe("no-referrer");
  });

  it("rejects HTML / script payloads as a trace id", () => {
    const cases = [
      "<script>alert(1)</script>",
      "00112233445566778899aabbccddeeff'><script>",
      "../etc/passwd",
      "00112233445566778899aabbccddeeff/../",
      "00112233445566778899AABBCCDDEEFF", // uppercase — regex is lowercase only
      "00112233", // too short
      "00112233445566778899aabbccddeeff00", // too long
      "",
      "   ",
    ];
    for (const bad of cases) {
      setTraceIdAndDetect(bad);
      if (component.trustedSrc !== null) {
        throw new Error(`trustedSrc should be null for input: ${JSON.stringify(bad)}`);
      }
      if (component.errorMessage === null) {
        throw new Error(`errorMessage should be set for input: ${JSON.stringify(bad)}`);
      }
      // The iframe element is *omitted entirely* (via *ngIf) when src is null,
      // so the attribute literally cannot leak the bad value.
      const iframe = fixture.nativeElement.querySelector("iframe");
      if (iframe !== null) {
        throw new Error(`iframe should be absent for input: ${JSON.stringify(bad)}`);
      }
    }
  });

  it("renders an error when no Jaeger base URL is configured", async () => {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [TraceViewComponent],
      // No JAEGER_BASE_URL provider on purpose.
    }).compileComponents();
    fixture = TestBed.createComponent(TraceViewComponent);
    component = fixture.componentInstance;
    setTraceIdAndDetect("00112233445566778899aabbccddeeff");
    expect(component.trustedSrc).toBeNull();
    expect(component.errorMessage).toBe("Jaeger embed URL is not configured.");
  });
});
