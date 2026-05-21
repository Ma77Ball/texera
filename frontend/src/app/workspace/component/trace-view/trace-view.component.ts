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

import { Component, Inject, InjectionToken, Input, OnChanges, Optional, SimpleChanges } from "@angular/core";
import { CommonModule } from "@angular/common";
import { DomSanitizer, SafeResourceUrl } from "@angular/platform-browser";

/** Operator-configured Jaeger UI origin, e.g. `"https://jaeger.internal"`. */
export const JAEGER_BASE_URL = new InjectionToken<string>("JAEGER_BASE_URL");

/**
 * Embeds the Jaeger trace UI for a single trace.
 *
 * The trace id is *strictly* validated against `^[0-9a-f]{32}$` before it
 * is composed into the iframe `src`. An invalid id renders an error state
 * with no `src` at all — the iframe never carries caller-supplied bytes
 * other than 32 hex characters. The iframe also runs under a
 * `sandbox="allow-scripts allow-same-origin"` attribute so the Jaeger UI
 * keeps its own origin's storage isolation; combined with the chart-side
 * CSP `frame-src` allowlist (operator-configured in `index.html`) this
 * gives layered defence against either a malicious id or a redirect-style
 * embed escape.
 */
@Component({
  selector: "texera-trace-view",
  templateUrl: "./trace-view.component.html",
  styleUrls: ["./trace-view.component.scss"],
  standalone: true,
  imports: [CommonModule],
})
export class TraceViewComponent implements OnChanges {
  /** Raw trace id from the route / parent component; treated as untrusted. */
  @Input() traceId?: string;

  static readonly TRACE_ID_PATTERN = /^[0-9a-f]{32}$/;

  /** Bound to the iframe `[src]`; stays null until validation succeeds. */
  trustedSrc: SafeResourceUrl | null = null;
  /** Bound to an error banner; null when the iframe is showing. */
  errorMessage: string | null = null;

  constructor(
    private sanitizer: DomSanitizer,
    @Optional() @Inject(JAEGER_BASE_URL) private jaegerBaseUrl: string | null
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes.traceId) {
      this.recompute();
    }
  }

  private recompute(): void {
    const id = (this.traceId ?? "").trim();
    if (!id) {
      this.trustedSrc = null;
      this.errorMessage = "No trace id provided.";
      return;
    }
    if (!TraceViewComponent.TRACE_ID_PATTERN.test(id)) {
      // No `src` is set — the iframe element keeps an empty source.
      this.trustedSrc = null;
      this.errorMessage = "Invalid trace id.";
      return;
    }
    const base = this.jaegerBaseUrl ?? "";
    if (!base) {
      this.trustedSrc = null;
      this.errorMessage = "Jaeger embed URL is not configured.";
      return;
    }
    const url = `${base.replace(/\/$/, "")}/trace/${id}?uiEmbed=v0`;
    // `bypassSecurityTrustResourceUrl` is fine here: `id` matches the strict
    // 32-hex regex above, and `base` comes from operator config — neither
    // path admits caller-supplied bytes other than 32 hex characters.
    this.trustedSrc = this.sanitizer.bypassSecurityTrustResourceUrl(url);
    this.errorMessage = null;
  }
}
