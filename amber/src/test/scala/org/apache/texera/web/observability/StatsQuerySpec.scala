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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}

class StatsQuerySpec extends AnyFlatSpec with Matchers {

  private val from = Instant.parse("2026-01-01T00:00:00Z")
  private val to = from.plus(Duration.ofHours(1))

  "StatsQuery.validate" should "accept allowlisted names and bind the typed wid" in {
    Seq("runsPerDay", "failureRate", "p95Duration").foreach { name =>
      val q = StatsQuery.validate(name, workflowId = 42, from = from, to = to)
      q.workflowId shouldBe 42
      q.named.name shouldBe name
    }
  }

  it should "reject any non-allowlisted query name" in {
    Seq("admin", "config", "p99Duration", "../runsPerDay", "runsPerDay; vector(1)").foreach { n =>
      an[StatsQuery.ValidationError] should be thrownBy
        StatsQuery.validate(n, workflowId = 1, from = from, to = to)
    }
  }

  it should "reject non-positive workflowId" in {
    an[StatsQuery.ValidationError] should be thrownBy
      StatsQuery.validate("runsPerDay", workflowId = 0, from = from, to = to)
    an[StatsQuery.ValidationError] should be thrownBy
      StatsQuery.validate("runsPerDay", workflowId = -5, from = from, to = to)
  }

  it should "reject from >= to" in {
    an[StatsQuery.ValidationError] should be thrownBy
      StatsQuery.validate("runsPerDay", workflowId = 1, from = to, to = from)
    an[StatsQuery.ValidationError] should be thrownBy
      StatsQuery.validate("runsPerDay", workflowId = 1, from = from, to = from)
  }

  it should "reject a time window over 90 days" in {
    an[StatsQuery.ValidationError] should be thrownBy
      StatsQuery.validate(
        "runsPerDay",
        workflowId = 1,
        from = from,
        to = from.plus(Duration.ofDays(90).plusSeconds(1))
      )
  }
}
