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

import com.fasterxml.jackson.databind.ObjectMapper
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}

class PrometheusClientSpec extends AnyFlatSpec with Matchers {

  private val client = new PrometheusClient(PrometheusClient.Config("http://prom:9090"))
  private val from = Instant.parse("2026-01-01T00:00:00Z")
  private val to = from.plus(Duration.ofHours(1))

  private def q(name: String, wid: Int = 7): StatsQuery =
    StatsQuery.validate(name, wid, from, to)

  "buildPromQL" should "substitute the workflow id as a numeric literal" in {
    val pql = client.buildPromQL(q("runsPerDay", wid = 99))
    pql should include("workflow_id=\"99\"")
  }

  it should "never contain the raw user input — workflowId is an Int by the time it reaches this layer" in {
    // The caller's path is: JAX-RS parses ?workflowId= as Int (so '"; vector(1) //'
    // 404s before validation), then StatsQuery.validate accepts only positive
    // integers, then NamedQuery.template formats the Int with %d.
    val pql = client.buildPromQL(q("failureRate", wid = 1))
    // Sanity: format string survives intact, no leftover %d.
    pql should not include "%d"
    // Both metric references reflect the same Int.
    pql.split("workflow_id=").length shouldBe 3
  }

  it should "render all three named queries to distinct PromQL strings" in {
    val rendered = NamedQuery.all.map(n => client.buildPromQL(q(n.name)))
    rendered.toSet should have size 3
    rendered.forall(_.contains("workflow_id=\"7\"")) shouldBe true
  }

  "parseResponse" should "lift Prometheus's matrix payload into ordered DataPoints" in {
    val mapper = new ObjectMapper()
    val body = mapper.readTree(
      """
        |{
        |  "status": "success",
        |  "data": {
        |    "resultType": "matrix",
        |    "result": [{
        |      "metric": {},
        |      "values": [
        |        [1735689600, "1.5"],
        |        [1735693200, "2.0"]
        |      ]
        |    }]
        |  }
        |}
      """.stripMargin
    )
    val ts = client.parseResponse(body)
    ts.points.map(_.value) shouldBe Seq(1.5, 2.0)
    ts.points.head.at.getEpochSecond shouldBe 1735689600L
  }

  it should "return an empty series when there are no results" in {
    val mapper = new ObjectMapper()
    val body = mapper.readTree("""{"status":"success","data":{"result":[]}}""")
    client.parseResponse(body).points shouldBe empty
  }

  it should "throw on Prometheus reporting an error status" in {
    val mapper = new ObjectMapper()
    val body = mapper.readTree("""{"status":"error","data":{"result":[]}}""")
    an[PrometheusClient.UpstreamError] should be thrownBy client.parseResponse(body)
  }
}
