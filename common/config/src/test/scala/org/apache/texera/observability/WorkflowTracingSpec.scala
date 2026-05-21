// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.texera.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class WorkflowTracingSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val exporter = InMemorySpanExporter.create()
  private var otel: OpenTelemetry = _

  override def beforeEach(): Unit = {
    exporter.reset()
    val provider = SdkTracerProvider
      .builder()
      .addSpanProcessor(SimpleSpanProcessor.create(exporter))
      .build()
    otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
  }

  // ---- validateTraceparent ----

  "validateTraceparent" should "accept a valid W3C traceparent" in {
    val tp = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
    WorkflowTracing.validateTraceparent(tp) shouldBe Some(tp)
  }

  it should "reject path-traversal-style values" in {
    WorkflowTracing.validateTraceparent("../../etc/passwd") shouldBe None
  }

  it should "reject wrong version" in {
    WorkflowTracing.validateTraceparent(
      "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
    ) shouldBe None
  }

  it should "reject all-zero trace id" in {
    WorkflowTracing.validateTraceparent(
      "00-00000000000000000000000000000000-b7ad6b7169203331-01"
    ) shouldBe None
  }

  it should "reject all-zero span id" in {
    WorkflowTracing.validateTraceparent(
      "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01"
    ) shouldBe None
  }

  it should "reject null and empty" in {
    WorkflowTracing.validateTraceparent(null) shouldBe None
    WorkflowTracing.validateTraceparent("") shouldBe None
  }

  // ---- validateTracestate ----

  "validateTracestate" should "accept short ASCII tracestate" in {
    WorkflowTracing.validateTracestate("vendor1=abc,vendor2=xyz") shouldBe Some(
      "vendor1=abc,vendor2=xyz"
    )
  }

  it should "reject non-ASCII" in {
    WorkflowTracing.validateTracestate("vendor=héllo") shouldBe None
  }

  it should "reject oversize tracestate" in {
    WorkflowTracing.validateTracestate("k=" + ("a" * 1024)) shouldBe None
  }

  // ---- validateWorkflowId / validateOperatorId ----

  "validateWorkflowId" should "accept Texera's numeric and UUID ids alike" in {
    WorkflowTracing.validateWorkflowId("550e8400-e29b-41d4-a716-446655440000") shouldBe
      Some("550e8400-e29b-41d4-a716-446655440000")
    WorkflowTracing.validateWorkflowId("12345") shouldBe Some("12345")
  }

  it should "reject injection-shaped strings" in {
    WorkflowTracing.validateWorkflowId("../etc") shouldBe None
    WorkflowTracing.validateWorkflowId("a/b") shouldBe None
    WorkflowTracing.validateWorkflowId("a\r\nb") shouldBe None
    WorkflowTracing.validateWorkflowId("x" * 65) shouldBe None
    WorkflowTracing.validateWorkflowId(null) shouldBe None
  }

  "validateOperatorId" should "accept alnum-dot-dash-underscore up to 64 chars" in {
    WorkflowTracing.validateOperatorId("CSV_source-1.v2") shouldBe Some("CSV_source-1.v2")
    WorkflowTracing.validateOperatorId("x" * 64) shouldBe Some("x" * 64)
  }

  it should "reject control chars and overly long ids" in {
    WorkflowTracing.validateOperatorId("op\r\nname") shouldBe None
    WorkflowTracing.validateOperatorId("x" * 65) shouldBe None
    WorkflowTracing.validateOperatorId("contains space") shouldBe None
  }

  // ---- capAttribute ----

  "capAttribute" should "truncate values over 256 chars and strip control chars" in {
    val v = "hello\r\nworld" + ("x" * 10000)
    val capped = WorkflowTracing.capAttribute(v)
    capped.length should be <= 256
    capped should not include "\r"
    capped should not include "\n"
  }

  it should "leave short, clean values untouched" in {
    WorkflowTracing.capAttribute("ok") shouldBe "ok"
  }

  // ---- contextFromTraceparent ----

  "contextFromTraceparent" should "build a Context with the carrier's trace id" in {
    val tp = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
    val ctx = WorkflowTracing.contextFromTraceparent(tp, null)
    val sc = io.opentelemetry.api.trace.Span.fromContext(ctx).getSpanContext
    sc.getTraceId shouldBe "0af7651916cd43dd8448eb211c80319c"
    sc.getSpanId shouldBe "b7ad6b7169203331"
    sc.isValid shouldBe true
  }

  it should "return a root context for an invalid traceparent" in {
    val ctx = WorkflowTracing.contextFromTraceparent("../../etc/passwd", null)
    val sc = io.opentelemetry.api.trace.Span.fromContext(ctx).getSpanContext
    sc.isValid shouldBe false
  }

  // ---- withWorkflowSpan ----

  "withWorkflowSpan" should "emit a span with validated attributes" in {
    val result = WorkflowTracing.withWorkflowSpan(
      otel,
      workflowId = "550e8400-e29b-41d4-a716-446655440000",
      executionId = "550e8400-e29b-41d4-a716-446655440000",
      parentContext = Context.root()
    ) { _ => 42 }
    result shouldBe 42

    val spans = exporter.getFinishedSpanItems.asScala
    spans should have size 1
    val s = spans.head
    s.getName shouldBe "workflow.execute"
    s.getAttributes.get(AttributeKey.stringKey("workflow.id")) shouldBe
      "550e8400-e29b-41d4-a716-446655440000"
  }

  it should "skip invalid workflow/execution ids on the attribute set, not crash" in {
    WorkflowTracing.withWorkflowSpan(
      otel,
      workflowId = "../escape",
      executionId = "ex\r\n" + ("x" * 1000),
      parentContext = Context.root()
    ) { _ => () }
    val s = exporter.getFinishedSpanItems.asScala.head
    Option(s.getAttributes.get(AttributeKey.stringKey("workflow.id"))) shouldBe None
    Option(s.getAttributes.get(AttributeKey.stringKey("execution.id"))) shouldBe None
  }

  it should "record exceptions on the span and rethrow" in {
    val ex = intercept[RuntimeException] {
      WorkflowTracing.withWorkflowSpan(
        otel,
        workflowId = "550e8400-e29b-41d4-a716-446655440000",
        executionId = "550e8400-e29b-41d4-a716-446655440000",
        parentContext = Context.root()
      ) { _ => throw new RuntimeException("boom") }
    }
    ex.getMessage shouldBe "boom"
    val s = exporter.getFinishedSpanItems.asScala.head
    s.getEvents.asScala.map(_.getName) should contain("exception")
  }

  // ---- cross-boundary scenario ----

  "a valid traceparent flowing in" should "produce a child span under that trace" in {
    val tp = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
    val parent = WorkflowTracing.contextFromTraceparent(tp, null)
    WorkflowTracing.withWorkflowSpan(
      otel,
      workflowId = "550e8400-e29b-41d4-a716-446655440000",
      executionId = "550e8400-e29b-41d4-a716-446655440000",
      parentContext = parent
    ) { _ => () }
    val s = exporter.getFinishedSpanItems.asScala.head
    s.getTraceId shouldBe "0af7651916cd43dd8448eb211c80319c"
    s.getParentSpanId shouldBe "b7ad6b7169203331"
  }

  "an invalid traceparent flowing in" should "yield a fresh root trace" in {
    val parent = WorkflowTracing.contextFromTraceparent("not-valid", null)
    WorkflowTracing.withWorkflowSpan(
      otel,
      workflowId = "550e8400-e29b-41d4-a716-446655440000",
      executionId = "550e8400-e29b-41d4-a716-446655440000",
      parentContext = parent
    ) { _ => () }
    val s = exporter.getFinishedSpanItems.asScala.head
    s.getTraceId should not be "0af7651916cd43dd8448eb211c80319c"
    s.getParentSpanContext.isValid shouldBe false
  }
}
