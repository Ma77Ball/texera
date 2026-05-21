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

import com.typesafe.scalalogging.LazyLogging
import io.dropwizard.auth.Auth
import org.apache.texera.auth.SessionUser
import org.apache.texera.dao.jooq.generated.enums.UserRoleEnum
import org.apache.texera.web.resource.dashboard.user.workflow.WorkflowAccessResource

import java.time.Instant
import javax.ws.rs._
import javax.ws.rs.core.{MediaType, Response}

/**
  * Read-only stats proxy: maps an allowlisted query name to a server-built
  * PromQL template, executes it, and returns chart-ready points.
  *
  * The endpoint never accepts raw PromQL. The path component
  * `{queryName}` is matched against a closed set; `workflowId` is parsed
  * as an Int (any non-numeric value 404s at the JAX-RS layer before
  * validation even runs); `from`/`to` are typed Instants with a 90-day
  * window cap; ownership is enforced via `hasReadAccess` with an admin
  * bypass.
  */
@Path("/stats")
@Produces(Array(MediaType.APPLICATION_JSON))
class StatsResource(
    client: PrometheusClient,
    perUserLimiter: TokenBucketRateLimiter =
      new TokenBucketRateLimiter(burst = 20, refillPerSecond = 20.0)
) extends LazyLogging {

  @GET
  @Path("/{queryName}")
  def stats(
      @PathParam("queryName") queryName: String,
      @QueryParam("workflowId") workflowId: Int,
      @QueryParam("from") fromParam: String,
      @QueryParam("to") toParam: String,
      @Auth user: SessionUser
  ): Response = {
    if (!perUserLimiter.tryAcquire(s"u:${user.getUid}")) {
      return rateLimited()
    }
    val from =
      try Instant.parse(fromParam)
      catch { case _: Exception => return badRequest("invalid 'from' timestamp") }
    val to =
      try Instant.parse(toParam)
      catch { case _: Exception => return badRequest("invalid 'to' timestamp") }

    val query =
      try StatsQuery.validate(queryName, workflowId, from, to)
      catch {
        case e: StatsQuery.ValidationError => return badRequest(e.message)
      }

    val isAdmin = user.isRoleOf(UserRoleEnum.ADMIN)
    if (!isAdmin && !WorkflowAccessResource.hasReadAccess(query.workflowId, user.getUid)) {
      return forbidden()
    }

    val series =
      try client.queryRange(query)
      catch {
        case e: PrometheusClient.UpstreamError =>
          logger.warn(s"prometheus upstream error: ${e.status}")
          return Response.status(Response.Status.BAD_GATEWAY).build()
        case e: Exception =>
          logger.warn(s"prometheus call failed: ${e.getClass.getSimpleName}")
          return Response.status(Response.Status.BAD_GATEWAY).build()
      }

    Response.ok(StatsResponse(query.named.name, series.points)).build()
  }

  private def badRequest(msg: String): Response =
    Response
      .status(Response.Status.BAD_REQUEST)
      .entity(java.util.Collections.singletonMap("error", msg))
      .build()

  private def forbidden(): Response = Response.status(Response.Status.FORBIDDEN).build()

  private def rateLimited(): Response =
    Response
      .status(429)
      .entity(java.util.Collections.singletonMap("error", "rate_limited"))
      .build()
}

/** Stable wire format consumed by the Workflow Stats Angular page. */
final case class StatsResponse(name: String, points: Seq[PrometheusClient.DataPoint])
