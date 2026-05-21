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

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.typesafe.scalalogging.LazyLogging

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{URLEncoder => JURLEncoder}
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

/**
  * Read-only Prometheus query client. The PromQL string is **never**
  * concatenated with caller input — the resource hands typed parameters
  * (workflow id as an Int, the time range as Instants) to one of three
  * `NamedQuery` templates, which substitute constants only. The HTTP layer
  * URL-encodes the rendered PromQL before sending so even legitimate
  * special characters cannot escape into the URL.
  *
  * The Prometheus HTTP API surface this exercises is `/api/v1/query_range`
  * — read-only. Admin / write endpoints (the `/api/v1/admin/...` family,
  * `/-/reload`) are unused; on the deploy side the Prometheus pod also
  * runs without `--web.enable-admin-api`.
  */
class PrometheusClient(
    config: PrometheusClient.Config,
    httpClient: HttpClient = PrometheusClient.defaultHttpClient,
    mapper: ObjectMapper = PrometheusClient.defaultMapper
) extends LazyLogging {

  import PrometheusClient._

  /** Build the rendered PromQL for `query` (public so tests can assert on it). */
  def buildPromQL(query: StatsQuery): String = query.named.template(query)

  /** Issue the query_range call. */
  def queryRange(query: StatsQuery): TimeSeries = {
    val promQL = buildPromQL(query)
    val stepSec = stepSecondsFor(query)
    val params = Map(
      "query" -> promQL,
      "start" -> query.from.getEpochSecond.toString,
      "end" -> query.to.getEpochSecond.toString,
      "step" -> stepSec.toString,
      // Prometheus enforces its own timeout in addition to our HTTP one.
      "timeout" -> s"${PrometheusTimeoutSec}s"
    )
    val request = HttpRequest
      .newBuilder()
      .uri(buildUri(params))
      .timeout(Duration.ofSeconds(HttpTimeoutSec))
      .header("Accept", "application/json")
      .GET()
      .build()

    val resp: HttpResponse[String] = httpClient.send(request, BodyHandlers.ofString())
    if (resp.statusCode() / 100 != 2) {
      logger.warn(s"prometheus query_range returned ${resp.statusCode()}")
      throw new UpstreamError(resp.statusCode())
    }
    parseResponse(mapper.readTree(resp.body()))
  }

  private[observability] def parseResponse(root: JsonNode): TimeSeries = {
    val status = root.path("status").asText("")
    if (status != "success") {
      throw new UpstreamError(502)
    }
    val data = root.path("data")
    val result = data.path("result")
    val points = scala.collection.mutable.ArrayBuffer.empty[DataPoint]
    if (result.isArray && result.size() > 0) {
      val values = result.get(0).path("values")
      if (values.isArray) {
        val it = values.elements()
        while (it.hasNext) {
          val pair = it.next()
          val ts = pair.get(0).asLong(0L)
          val v = pair.get(1).asText("NaN")
          val d = try v.toDouble catch { case _: NumberFormatException => Double.NaN }
          points += DataPoint(Instant.ofEpochSecond(ts), d)
        }
      }
    }
    TimeSeries(points.toSeq)
  }

  private def buildUri(params: Map[String, String]): URI = {
    val qs = params
      .map { case (k, v) => s"${enc(k)}=${enc(v)}" }
      .mkString("&")
    URI.create(s"${config.endpoint.stripSuffix("/")}/api/v1/query_range?$qs")
  }

  private def enc(s: String): String = JURLEncoder.encode(s, StandardCharsets.UTF_8)

  // Resolution caps the number of points returned to ~100 — keeps both the
  // payload and the rendering work on the UI bounded regardless of range.
  private def stepSecondsFor(query: StatsQuery): Long = {
    val rangeSec = math.max(1L, Duration.between(query.from, query.to).getSeconds)
    math.max(60L, rangeSec / 100L)
  }
}

object PrometheusClient {
  val HttpTimeoutSec: Long = 5L
  val PrometheusTimeoutSec: Long = 3L

  final case class Config(endpoint: String)

  final case class UpstreamError(status: Int)
      extends RuntimeException(s"prometheus returned $status")

  final case class DataPoint(at: Instant, value: Double)
  final case class TimeSeries(points: Seq[DataPoint])

  private val defaultMapper: ObjectMapper = new ObjectMapper()

  private val defaultHttpClient: HttpClient = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(2))
    .build()
}
