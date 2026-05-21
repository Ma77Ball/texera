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

package org.apache.texera.healthcheck

import jakarta.servlet.http.HttpServletRequest
import jakarta.ws.rs.core.{Context, MediaType, Response}
import jakarta.ws.rs.{GET, Path, Produces}

/**
  * Anonymous-callable liveness / readiness probes for k8s.
  *
  * `live`  → 200 as long as the process is up.
  * `ready` → 200/503, body is `{status, checks: [{name, ok}]}` with no error
  * strings, versions, hostnames, or stack traces.
  *
  * Per-source-IP token bucket caps the rate at which one peer can poke the
  * dependencies a readiness check fans out to.
  */
@Path("/healthcheck")
@Produces(Array(MediaType.APPLICATION_JSON))
class HealthCheckResource(
    checks: Seq[HealthCheck],
    rateLimiter: IpRateLimiter = new IpRateLimiter(),
    perCheckTimeoutMillis: Long = 500L
) {

  private val runner = new HealthCheckRunner(checks, perCheckTimeoutMillis)

  // Backwards-compatible alias for legacy /api/healthcheck callers. Behaves like /live.
  @GET
  def root(@Context req: HttpServletRequest): Response = live(req)

  @GET
  @Path("/live")
  def live(@Context req: HttpServletRequest): Response = {
    if (!rateLimiter.tryAcquire(HealthCheckResource.clientIp(req))) {
      return HealthCheckResource.tooManyRequests
    }
    Response.ok(HealthReport(HealthReport.Ok, Seq.empty)).build()
  }

  @GET
  @Path("/ready")
  def ready(@Context req: HttpServletRequest): Response = {
    if (!rateLimiter.tryAcquire(HealthCheckResource.clientIp(req))) {
      return HealthCheckResource.tooManyRequests
    }
    val report = runner.report()
    val status =
      if (report.status == HealthReport.Ok) Response.Status.OK
      else Response.Status.SERVICE_UNAVAILABLE
    Response.status(status).entity(report).build()
  }
}

object HealthCheckResource {

  /**
    * Resolve the caller IP for rate-limiting. Trust `X-Forwarded-For` only if
    * one is present — we treat the *left-most* token as the original client
    * (k8s-style L4/L7 proxies prepend). `req.getRemoteAddr` is the fallback.
    */
  private[healthcheck] def clientIp(req: HttpServletRequest): String = {
    val xff = Option(req.getHeader("X-Forwarded-For")).map(_.trim).filter(_.nonEmpty)
    xff.map(_.split(",")(0).trim).getOrElse(Option(req.getRemoteAddr).getOrElse("unknown"))
  }

  private[healthcheck] def tooManyRequests: Response =
    Response.status(429).entity(Map("status" -> "rate_limited")).build()
}
