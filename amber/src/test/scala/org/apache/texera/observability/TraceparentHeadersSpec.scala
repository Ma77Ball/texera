/*
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

package org.apache.texera.amber.engine.architecture.pythonworker

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TraceparentHeadersSpec extends AnyFlatSpec with Matchers {

  "traceparentOf" should "return None when no span is active" in {
    TraceparentHeaders.traceparentOf(Context.root()) shouldBe None
  }

  it should "build a valid W3C traceparent from an active span" in {
    val otel: OpenTelemetry = OpenTelemetrySdk
      .builder()
      .setTracerProvider(SdkTracerProvider.builder().build())
      .build()
    val span = otel.getTracer("test").spanBuilder("op").startSpan()
    try {
      val ctx = Context.root().`with`(span)
      val tp = TraceparentHeaders.traceparentOf(ctx)
      tp shouldBe defined
      val sc = Span.fromContext(ctx).getSpanContext
      tp.get should startWith(s"00-${sc.getTraceId}-${sc.getSpanId}-")
      tp.get should fullyMatch regex "^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"
    } finally span.end()
  }

  "fromContext" should "produce Some(HeaderCallOption) when a span is active" in {
    val otel: OpenTelemetry = OpenTelemetrySdk
      .builder()
      .setTracerProvider(SdkTracerProvider.builder().build())
      .build()
    val span = otel.getTracer("test").spanBuilder("op").startSpan()
    try {
      TraceparentHeaders.fromContext(Context.root().`with`(span)) shouldBe defined
    } finally span.end()
    TraceparentHeaders.fromContext(Context.root()) shouldBe None
  }
}
