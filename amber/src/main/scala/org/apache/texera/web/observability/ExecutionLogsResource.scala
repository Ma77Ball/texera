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
import org.apache.texera.dao.SqlServer
import org.apache.texera.dao.jooq.generated.Tables.{WORKFLOW_EXECUTIONS, WORKFLOW_VERSION}
import org.apache.texera.web.resource.dashboard.user.workflow.WorkflowAccessResource
import org.slf4j.LoggerFactory

import java.time.Instant
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType, Response}

/**
  * Read-only proxy that lets the UI surface the structured logs OpenSearch
  * indexed for a workflow execution.
  *
  * Surface area is tiny on purpose: callers pass typed query parameters,
  * never a DSL fragment. The server enforces ownership (`hasReadAccess`),
  * input bounds (window ≤ 7 days, q ≤ 256 chars, size ≤ 1000), a per-user
  * rate-limit, a per-IP rate-limit, and writes one audit-log line per call.
  */
@Path("/executions")
@Produces(Array(MediaType.APPLICATION_JSON))
class ExecutionLogsResource(
    client: OpenSearchLogsClient,
    perUserLimiter: TokenBucketRateLimiter =
      new TokenBucketRateLimiter(burst = 20, refillPerSecond = 20.0),
    perIpLimiter: TokenBucketRateLimiter =
      new TokenBucketRateLimiter(burst = 40, refillPerSecond = 40.0)
) extends LazyLogging {

  private val auditLog = LoggerFactory.getLogger("audit.executions.logs")

  @GET
  @Path("/{eid}/logs")
  def getLogs(
      @PathParam("eid") eid: Int,
      @QueryParam("from") fromParam: String,
      @QueryParam("to") toParam: String,
      @QueryParam("level") @DefaultValue("") levelParam: String,
      @QueryParam("q") @DefaultValue("") qParam: String,
      @QueryParam("size") sizeParam: Integer,
      @QueryParam("cursor") @DefaultValue("") cursorParam: String,
      @Auth user: SessionUser,
      @Context req: HttpServletRequest
  ): Response = {
    // Per-IP cap first — keeps a hostile single IP from burning DB lookups
    // for ownership checks before its user-bucket would even kick in.
    val ip = ExecutionLogsResource.clientIp(req)
    if (!perIpLimiter.tryAcquire(s"ip:$ip")) {
      return rateLimited()
    }
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
      try
        LogsQuery.validate(
          executionId = eid,
          from = from,
          to = to,
          level = Option(levelParam).map(_.trim).filter(_.nonEmpty),
          q = Option(qParam).map(_.trim).filter(_.nonEmpty),
          size = Option(sizeParam).map(_.intValue()),
          searchAfter = Option(cursorParam).map(_.trim).filter(_.nonEmpty)
        )
      catch {
        case e: LogsQuery.ValidationError => return badRequest(e.message)
      }

    // Ownership: execution → version → workflow → access check.
    val wid =
      ExecutionLogsResource.lookupWorkflowIdForExecution(eid).getOrElse {
        return notFound()
      }
    if (!WorkflowAccessResource.hasReadAccess(wid, user.getUid)) {
      auditDenied(user, query, "no_access")
      return Response.status(Response.Status.FORBIDDEN).build()
    }

    val page =
      try client.search(query)
      catch {
        case e: OpenSearchLogsClient.UpstreamError =>
          logger.warn(s"opensearch upstream error: ${e.status}")
          return Response.status(Response.Status.BAD_GATEWAY).build()
        case e: Exception =>
          logger.warn(s"opensearch search failed: ${e.getClass.getSimpleName}")
          return Response.status(Response.Status.BAD_GATEWAY).build()
      }

    audit(user, query, page.total)
    Response.ok(page).build()
  }

  private def badRequest(msg: String): Response =
    Response
      .status(Response.Status.BAD_REQUEST)
      .entity(java.util.Collections.singletonMap("error", msg))
      .build()

  private def notFound(): Response =
    Response.status(Response.Status.NOT_FOUND).build()

  private def rateLimited(): Response =
    Response
      .status(429)
      .entity(java.util.Collections.singletonMap("error", "rate_limited"))
      .build()

  // Audit format: tab-separated keys=values so existing line-oriented log
  // pipelines can grep this without parsing JSON. Values are server-controlled
  // (the user-supplied q is *replaced* with its length, never echoed).
  private def audit(user: SessionUser, q: LogsQuery, total: Long): Unit = {
    auditLog.info(
      s"uid=${user.getUid}\teid=${q.executionId}\tfrom=${q.from}\tto=${q.to}" +
        s"\tlevel=${q.level.map(_.name).getOrElse("-")}" +
        s"\tq_len=${q.q.map(_.length).getOrElse(0)}" +
        s"\thits=$total"
    )
  }

  private def auditDenied(user: SessionUser, q: LogsQuery, reason: String): Unit = {
    auditLog.info(
      s"uid=${user.getUid}\teid=${q.executionId}\tdenied=$reason"
    )
  }
}

object ExecutionLogsResource {

  /** Returns the workflow id for an execution, or `None` if the eid is unknown. */
  private[observability] def lookupWorkflowIdForExecution(eid: Int): Option[Integer] = {
    val ctx = SqlServer.getInstance().createDSLContext()
    Option(
      ctx
        .select(WORKFLOW_VERSION.WID)
        .from(WORKFLOW_EXECUTIONS)
        .join(WORKFLOW_VERSION)
        .on(WORKFLOW_EXECUTIONS.VID.eq(WORKFLOW_VERSION.VID))
        .where(WORKFLOW_EXECUTIONS.EID.eq(eid))
        .fetchOne(WORKFLOW_VERSION.WID)
    )
  }

  /** Trust X-Forwarded-For only as the rate-limit key; never used for auth. */
  private[observability] def clientIp(req: HttpServletRequest): String = {
    val xff = Option(req.getHeader("X-Forwarded-For")).map(_.trim).filter(_.nonEmpty)
    xff.map(_.split(",")(0).trim).getOrElse(Option(req.getRemoteAddr).getOrElse("unknown"))
  }
}
