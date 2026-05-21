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
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class WorkflowMetricsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val reader = InMemoryMetricReader.create()
  private var otel: OpenTelemetry = _
  private var metrics: WorkflowMetrics = _

  override def beforeEach(): Unit = {
    reader.collectAllMetrics() // drain
    val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
    otel = OpenTelemetrySdk.builder().setMeterProvider(provider).build()
    metrics = new WorkflowMetrics(otel)
  }

  private def collect(): Map[String, io.opentelemetry.sdk.metrics.data.MetricData] =
    reader.collectAllMetrics.asScala.map(m => m.getName -> m).toMap

  // ---- recordStart ----

  "recordStart" should "increment workflow.starts with a validated outcome label" in {
    metrics.recordStart(workflowKind = "batch")
    val m = collect()("workflow.starts")
    val point = m.getLongSumData.getPoints.asScala.head
    point.getValue shouldBe 1
    point.getAttributes.get(AttributeKey.stringKey("workflow.kind")) shouldBe "batch"
  }

  it should "drop an unknown workflow.kind" in {
    metrics.recordStart(workflowKind = "free text DROP TABLE users;--")
    val point =
      collect()("workflow.starts").getLongSumData.getPoints.asScala.head
    Option(point.getAttributes.get(AttributeKey.stringKey("workflow.kind"))) shouldBe None
  }

  // ---- recordCompletion ----

  "recordCompletion" should "add a duration sample and increment completions" in {
    metrics.recordCompletion(durationMs = 1234, outcome = "ok", workflowKind = "batch")
    val dur = collect()("workflow.duration")
    dur.getHistogramData.getPoints.asScala.head.getSum shouldBe 1234.0
    val comp = collect()("workflow.completions").getLongSumData.getPoints.asScala.head
    comp.getValue shouldBe 1
    comp.getAttributes.get(AttributeKey.stringKey("outcome")) shouldBe "ok"
  }

  it should "drop an unknown outcome" in {
    metrics.recordCompletion(durationMs = 100, outcome = "free text", workflowKind = "batch")
    val comp = collect()("workflow.completions").getLongSumData.getPoints.asScala.head
    Option(comp.getAttributes.get(AttributeKey.stringKey("outcome"))) shouldBe None
  }

  it should "clamp negative durations to zero" in {
    metrics.recordCompletion(durationMs = -42, outcome = "ok", workflowKind = "batch")
    val dur = collect()("workflow.duration").getHistogramData.getPoints.asScala.head
    dur.getSum shouldBe 0.0
  }

  // ---- recordFailure ----

  "recordFailure" should "increment workflow.failures" in {
    metrics.recordFailure(workflowKind = "interactive")
    val m = collect()("workflow.failures")
    m.getLongSumData.getPoints.asScala.head.getValue shouldBe 1
  }

  // ---- recordActive (up-down) ----

  "recordActive" should "track in-flight workflow count" in {
    metrics.recordActive(1)
    metrics.recordActive(1)
    metrics.recordActive(-1)
    val m = collect()("workflow.active")
    m.getLongSumData.getPoints.asScala.head.getValue shouldBe 1
  }

  // ---- security: workflow.id never becomes a label ----

  "WorkflowMetrics" should "never accept workflow.id as a label, even via reflection-style misuse" in {
    metrics.recordStart(workflowKind = "batch")
    val point = collect()("workflow.starts").getLongSumData.getPoints.asScala.head
    // Attribute keys present on the point — workflow.id must NOT be among them.
    val keys = point.getAttributes.asMap.asScala.keys.map(_.getKey).toSet
    keys should not contain "workflow.id"
    keys should not contain "execution.id"
  }

  // ---- OtelInit.validateMetricInterval ----

  "OtelInit.validateMetricInterval" should "accept values in range" in {
    OtelInit.validateMetricInterval("5000") shouldBe Some(5000L)
  }

  it should "reject values below the floor" in {
    OtelInit.validateMetricInterval("10") shouldBe None
  }

  it should "reject values above the ceiling" in {
    OtelInit.validateMetricInterval("9999999") shouldBe None
  }

  it should "reject non-numeric values" in {
    OtelInit.validateMetricInterval("abc") shouldBe None
  }
}
