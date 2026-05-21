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

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.typesafe.scalalogging.LazyLogging

import java.net.URI
import java.net.http.HttpClient.Version
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/**
  * Read-only OpenSearch client scoped to a single index pattern (default
  * `texera-logs-*`). The search DSL is built **structurally** via Jackson
  * ObjectNodes — caller-supplied strings only ever land in JSON string
  * values, never in field names, operator names, or template fragments —
  * so a malicious `q` payload like `"} ] }` is just a literal `match`
  * term, not a way to alter the request body.
  */
class OpenSearchLogsClient(
    config: OpenSearchLogsClient.Config,
    httpClient: HttpClient = OpenSearchLogsClient.defaultHttpClient,
    mapper: ObjectMapper = OpenSearchLogsClient.defaultMapper
) extends LazyLogging {

  import OpenSearchLogsClient._

  /** Renders the search request body for `query`. Public so tests can assert on it. */
  def buildSearchBody(query: LogsQuery): ObjectNode = {
    val root = mapper.createObjectNode()
    root.put("size", query.size)
    root.put("timeout", QueryTimeoutMs.toString + "ms")
    // track_total_hits=true: we expose an honest total so the UI can render
    // "showing N of M" without hand-rolling its own counter; the cost is
    // bounded because size <= MaxSize.
    root.put("track_total_hits", true)

    // Deterministic sort so search_after gives stable pagination.
    val sort = root.putArray("sort")
    val tsSort = sort.addObject().putObject("@timestamp")
    tsSort.put("order", "asc")
    val idSort = sort.addObject().putObject("_id")
    idSort.put("order", "asc")

    // search_after: cursor is a JSON array we issued in a prior response.
    query.searchAfter.foreach { cursor =>
      // Decode safely; if it isn't a JSON array we refuse to forward it as
      // a quoted string (would break the DSL contract). Failures here mean
      // the caller corrupted the cursor; surface as a typed exception.
      val parsed =
        try mapper.readTree(cursor)
        catch {
          case e: Exception => throw new IllegalArgumentException("malformed cursor", e)
        }
      if (!parsed.isArray) {
        throw new IllegalArgumentException("cursor must be a JSON array")
      }
      root.replace("search_after", parsed)
    }

    // _source allowlist — the index may carry other fields (e.g. an unredacted
    // payload added by a future log appender). Strict allowlist keeps that
    // out of the API response by construction.
    val src = root.putArray("_source")
    Seq("@timestamp", "severity_text", "body", "trace_id", "span_id").foreach(src.add)

    val boolNode = root.putObject("query").putObject("bool")
    val filter = boolNode.putArray("filter")

    // executionId — typed Int, written as JSON number.
    val termExec = filter.addObject().putObject("term")
    termExec.put("attributes.execution.id", query.executionId)

    // Time window — typed Instants, written as ISO-8601 strings.
    val range = filter.addObject().putObject("range").putObject("@timestamp")
    range.put("gte", query.from.toString)
    range.put("lt", query.to.toString)

    query.level.foreach { lvl =>
      val termLvl = filter.addObject().putObject("term")
      termLvl.put("severity_text", lvl.name)
    }

    query.q.foreach { text =>
      // `match` on `body` only; never `script`, `regexp`, `query_string`, or
      // `wildcard`. Operator name is a constant; `text` is the JSON value.
      val matchObj = boolNode.putArray("must").addObject().putObject("match")
      val bodyMatch = matchObj.putObject("body")
      bodyMatch.put("query", text)
      bodyMatch.put("operator", "and")
    }

    root
  }

  /** Issue the search and parse the resulting page. */
  def search(query: LogsQuery): LogsPage = {
    val body = buildSearchBody(query)
    val request = HttpRequest
      .newBuilder()
      .uri(searchUri)
      .timeout(Duration.ofSeconds(HttpTimeoutSec))
      .header("Content-Type", "application/json")
      .header("Authorization", basicAuthHeader)
      .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
      .build()

    val resp: HttpResponse[String] = httpClient.send(request, BodyHandlers.ofString())
    if (resp.statusCode() / 100 != 2) {
      logger.warn(s"opensearch logs search returned ${resp.statusCode()}")
      throw new OpenSearchLogsClient.UpstreamError(resp.statusCode())
    }
    parseResponse(mapper.readTree(resp.body()))
  }

  private[observability] def parseResponse(root: JsonNode): LogsPage = {
    val hits = root.path("hits")
    val total = hits.path("total").path("value").asLong(0L)
    val arr = hits.path("hits")
    val entries = scala.collection.mutable.ArrayBuffer.empty[LogEntry]
    var lastSort: Option[JsonNode] = None
    if (arr.isArray) {
      val it = arr.elements()
      while (it.hasNext) {
        val hit = it.next()
        val src = hit.path("_source")
        entries += LogEntry(
          timestamp = src.path("@timestamp").asText(""),
          level = src.path("severity_text").asText(""),
          body = src.path("body").asText(""),
          traceId = Option(src.path("trace_id").asText(null)).filter(_.nonEmpty),
          spanId = Option(src.path("span_id").asText(null)).filter(_.nonEmpty)
        )
        if (hit.has("sort")) lastSort = Some(hit.get("sort"))
      }
    }
    val nextCursor = lastSort.map(mapper.writeValueAsString)
    LogsPage(entries.toSeq, total, nextCursor)
  }

  private def searchUri: URI =
    URI.create(s"${config.endpoint.stripSuffix("/")}/${config.indexPattern}/_search")

  private def basicAuthHeader: String = {
    val raw = s"${config.username}:${config.password}".getBytes(StandardCharsets.UTF_8)
    "Basic " + Base64.getEncoder.encodeToString(raw)
  }
}

object OpenSearchLogsClient {
  val QueryTimeoutMs: Long = 3_000L
  val HttpTimeoutSec: Long = 5L

  final case class Config(
      endpoint: String,
      username: String,
      password: String,
      // Stays bound; never overridden per request.
      indexPattern: String = "texera-logs-*"
  )

  final case class UpstreamError(status: Int)
      extends RuntimeException(s"opensearch returned $status")

  private val defaultMapper: ObjectMapper = new ObjectMapper()

  private val defaultHttpClient: HttpClient = HttpClient
    .newBuilder()
    .version(Version.HTTP_1_1)
    .connectTimeout(Duration.ofSeconds(2))
    .build()
}
