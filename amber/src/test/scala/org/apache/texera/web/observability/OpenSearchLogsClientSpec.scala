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
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.jdk.CollectionConverters._

class OpenSearchLogsClientSpec extends AnyFlatSpec with Matchers with OptionValues {

  private val cfg = OpenSearchLogsClient.Config(
    endpoint = "https://os:9200",
    username = "u",
    password = "p"
  )
  private val client = new OpenSearchLogsClient(cfg)

  private def baseQuery(q: Option[String] = None): LogsQuery =
    LogsQuery.validate(
      executionId = 42,
      from = Instant.parse("2026-01-01T00:00:00Z"),
      to = Instant.parse("2026-01-01T01:00:00Z"),
      level = Some("INFO"),
      q = q,
      size = Some(50),
      searchAfter = None
    )

  "buildSearchBody" should "constrain size, timeout, total tracking, and sort order" in {
    val body = client.buildSearchBody(baseQuery())
    body.path("size").asInt() shouldBe 50
    body.path("timeout").asText() shouldBe (OpenSearchLogsClient.QueryTimeoutMs.toString + "ms")
    body.path("track_total_hits").asBoolean() shouldBe true
    body.path("sort").get(0).has("@timestamp") shouldBe true
  }

  it should "filter by executionId and the time range" in {
    val body = client.buildSearchBody(baseQuery())
    val filters = body.path("query").path("bool").path("filter")
    val asJson = filters.toString
    asJson should include(""""attributes.execution.id":42""")
    asJson should include(""""gte":"2026-01-01T00:00:00Z"""")
    asJson should include(""""lt":"2026-01-01T01:00:00Z"""")
  }

  it should "place user `q` only inside a match clause on `body`, never as a DSL operator" in {
    val attacks = Seq(
      """"} ] }""",
      "*",
      """{"size":100000}""",
      """script: ctx._source.x = 1""",
      """OR true == true /*"""
    )
    attacks.foreach { hostile =>
      val body = client.buildSearchBody(baseQuery(Some(hostile)))
      val must = body.path("query").path("bool").path("must")
      must.size() shouldBe 1
      val matchNode = must.get(0).path("match").path("body")
      matchNode.path("query").asText() shouldBe hostile
      matchNode.path("operator").asText() shouldBe "and"

      // The hostile string must not have introduced a script / regexp /
      // query_string / wildcard / range / term operator at any nested
      // level. Any of those names appearing means structure escaped.
      val rendered = body.toString
      Seq("\"script\"", "\"regexp\"", "\"query_string\"", "\"wildcard\"")
        .foreach { forbidden =>
          rendered should not include forbidden
        }
    }
  }

  it should "scope the _source projection to a fixed allowlist" in {
    val body = client.buildSearchBody(baseQuery())
    val src = body.path("_source").elements().asScala.map(_.asText()).toSet
    src shouldBe Set("@timestamp", "severity_text", "body", "trace_id", "span_id")
  }

  it should "embed a server-provided cursor verbatim and reject malformed ones" in {
    val mapper = new ObjectMapper()
    val good = mapper.writeValueAsString(mapper.createArrayNode().add(1).add("abc"))
    val q = LogsQuery.validate(
      executionId = 1,
      from = Instant.parse("2026-01-01T00:00:00Z"),
      to = Instant.parse("2026-01-01T01:00:00Z"),
      level = None,
      q = None,
      size = None,
      searchAfter = Some(good)
    )
    val body = client.buildSearchBody(q)
    body.path("search_after").isArray shouldBe true

    val bad = LogsQuery.validate(
      executionId = 1,
      from = Instant.parse("2026-01-01T00:00:00Z"),
      to = Instant.parse("2026-01-01T01:00:00Z"),
      level = None,
      q = None,
      size = None,
      searchAfter = Some("{}")
    )
    an[IllegalArgumentException] should be thrownBy client.buildSearchBody(bad)
  }

  "parseResponse" should "extract hits, total, and the next cursor" in {
    val mapper = new ObjectMapper()
    val resp = mapper.readTree(
      """
        |{
        |  "hits": {
        |    "total": {"value": 17},
        |    "hits": [
        |      {
        |        "sort": ["2026-01-01T00:00:00Z", "id-1"],
        |        "_source": {
        |          "@timestamp": "2026-01-01T00:00:00Z",
        |          "severity_text": "INFO",
        |          "body": "hello",
        |          "trace_id": "abc",
        |          "span_id": "def"
        |        }
        |      }
        |    ]
        |  }
        |}
      """.stripMargin
    )
    val page = client.parseResponse(resp)
    page.total shouldBe 17
    page.entries should have size 1
    page.entries.head.level shouldBe "INFO"
    page.entries.head.body shouldBe "hello"
    page.entries.head.traceId shouldBe Some("abc")
    page.nextCursor.value should include("\"id-1\"")
  }

  it should "return an empty page when there are no hits" in {
    val mapper = new ObjectMapper()
    val resp = mapper.readTree("""{"hits": {"total": {"value": 0}, "hits": []}}""")
    val page = client.parseResponse(resp)
    page.total shouldBe 0
    page.entries shouldBe empty
    page.nextCursor shouldBe None
  }

}
