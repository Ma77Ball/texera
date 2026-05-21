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

package org.apache.texera.web.observability

import java.time.{Duration, Instant}

/** Validated query payload for the `/api/stats/{queryName}` endpoint. */
final case class StatsQuery private (
    named: NamedQuery,
    workflowId: Int,
    from: Instant,
    to: Instant
)

object StatsQuery {
  val MaxWindow: Duration = Duration.ofDays(90)

  final case class ValidationError(message: String) extends Exception(message)

  /**
    * Validate-and-normalise. Inputs are typed before they ever touch the
    * PromQL template; an attacker cannot inject a PromQL fragment because
    * `workflowId` is parsed as an Int (so `';vector(1)//'` fails here) and
    * `queryName` is matched against a closed set.
    */
  @throws[ValidationError]
  def validate(
      queryName: String,
      workflowId: Int,
      from: Instant,
      to: Instant
  ): StatsQuery = {
    if (workflowId <= 0) throw ValidationError("workflowId must be positive")
    if (!from.isBefore(to)) throw ValidationError("from must be strictly before to")
    if (Duration.between(from, to).compareTo(MaxWindow) > 0) {
      throw ValidationError("time window may not exceed 90 days")
    }
    val name =
      NamedQuery.lookup(queryName).getOrElse {
        throw ValidationError(
          s"unknown query name; allowed: ${NamedQuery.all.map(_.name).mkString(", ")}"
        )
      }
    StatsQuery(name, workflowId, from, to)
  }
}

/**
  * Closed set of named PromQL templates. The `template` function builds a
  * string from typed inputs — workflowId formatted as %d so no caller
  * string ever reaches PromQL.
  */
sealed abstract class NamedQuery(val name: String) {
  def template(q: StatsQuery): String
}

object NamedQuery {

  case object RunsPerDay extends NamedQuery("runsPerDay") {
    override def template(q: StatsQuery): String =
      "sum(increase(workflow_starts_total{workflow_id=\"%d\"}[1d]))".format(q.workflowId)
  }

  case object FailureRate extends NamedQuery("failureRate") {
    override def template(q: StatsQuery): String =
      ("sum(rate(workflow_failures_total{workflow_id=\"%d\"}[5m])) / " +
        "sum(rate(workflow_starts_total{workflow_id=\"%d\"}[5m]))")
        .format(q.workflowId, q.workflowId)
  }

  case object P95Duration extends NamedQuery("p95Duration") {
    override def template(q: StatsQuery): String =
      ("histogram_quantile(0.95, sum by (le) (rate(workflow_duration_bucket" +
        "{workflow_id=\"%d\"}[5m])))").format(q.workflowId)
  }

  val all: Seq[NamedQuery] = Seq(RunsPerDay, FailureRate, P95Duration)

  def lookup(name: String): Option[NamedQuery] = all.find(_.name == name)
}
