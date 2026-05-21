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
import io.opentelemetry.api.common.{AttributeKey, Attributes}

/**
 * Workflow-level metrics: starts, completions, failures, duration histogram,
 * and an up-down counter of in-flight workflows.
 *
 * Security boundary: the only labels accepted are the closed enums
 * [[WorkflowMetrics.AllowedOutcomes]] and [[WorkflowMetrics.AllowedKinds]].
 * **`workflow.id` / `execution.id` are never accepted as labels** — putting a
 * unique-per-workflow value on every metric would explode the time-series
 * cardinality and DoS the metrics backend. Per-workflow detail belongs in
 * traces and logs, not metrics.
 */
class WorkflowMetrics(otel: OpenTelemetry) {

  private val meter = otel.getMeter("org.apache.texera.observability")

  private val starts = meter.counterBuilder("workflow.starts").build()
  private val completions = meter.counterBuilder("workflow.completions").build()
  private val failures = meter.counterBuilder("workflow.failures").build()
  private val active = meter.upDownCounterBuilder("workflow.active").build()
  private val duration = meter.histogramBuilder("workflow.duration").ofLongs().build()

  def recordStart(workflowKind: String): Unit =
    starts.add(1, kindAttributes(workflowKind))

  def recordCompletion(durationMs: Long, outcome: String, workflowKind: String): Unit = {
    val attrs = outcomeAndKindAttributes(outcome, workflowKind)
    val safeDuration = math.max(0L, durationMs)
    duration.record(safeDuration, attrs)
    completions.add(1, attrs)
  }

  def recordFailure(workflowKind: String): Unit =
    failures.add(1, kindAttributes(workflowKind))

  def recordActive(delta: Long): Unit = active.add(delta)

  private def kindAttributes(workflowKind: String): Attributes = {
    val b = Attributes.builder()
    WorkflowMetrics.validateKind(workflowKind).foreach(v =>
      b.put(WorkflowMetrics.WorkflowKindKey, v)
    )
    b.build()
  }

  private def outcomeAndKindAttributes(outcome: String, workflowKind: String): Attributes = {
    val b = Attributes.builder()
    WorkflowMetrics.validateOutcome(outcome).foreach(v => b.put(WorkflowMetrics.OutcomeKey, v))
    WorkflowMetrics.validateKind(workflowKind).foreach(v =>
      b.put(WorkflowMetrics.WorkflowKindKey, v)
    )
    b.build()
  }
}

object WorkflowMetrics {

  val AllowedOutcomes: Set[String] = Set("ok", "failed", "cancelled")
  val AllowedKinds: Set[String] = Set("batch", "streaming", "interactive")

  val OutcomeKey: AttributeKey[String] = AttributeKey.stringKey("outcome")
  val WorkflowKindKey: AttributeKey[String] = AttributeKey.stringKey("workflow.kind")

  def validateOutcome(raw: String): Option[String] =
    Option(raw).filter(AllowedOutcomes.contains)

  def validateKind(raw: String): Option[String] =
    Option(raw).filter(AllowedKinds.contains)
}
