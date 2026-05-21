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

import jakarta.ws.rs.core.{Context, MediaType, Response, SecurityContext}
import jakarta.ws.rs.{GET, Path, Produces}
import org.apache.texera.auth.SessionUser
import org.apache.texera.dao.jooq.generated.enums.UserRoleEnum

/**
  * Admin-only sibling of `HealthCheckResource`. Exposes per-check latency and
  * the *class name* of the last error — never the message or stack — so that
  * operators can triage a flapping dependency without leaking dependency
  * details to anonymous callers.
  *
  * Reads the `SessionUser` from the `SecurityContext` populated by
  * `org.apache.texera.auth.JwtAuthFilter` so this resource does not pull in a
  * Dropwizard version dependency (the `@Auth` annotation would).
  *
  * Services without an authenticated request path (e.g. `workflow-compiling-service`
  * today) should not register this resource.
  */
@Path("/admin/healthcheck/details")
@Produces(Array(MediaType.APPLICATION_JSON))
class HealthCheckDetailsResource(
    checks: Seq[HealthCheck],
    perCheckTimeoutMillis: Long = 500L
) {

  private val runner = new HealthCheckRunner(checks, perCheckTimeoutMillis)

  @GET
  def details(@Context securityContext: SecurityContext): Response = {
    HealthCheckDetailsResource.authorized(securityContext) match {
      case Some(_) => Response.ok(runner.run()).build()
      case None    => Response.status(Response.Status.FORBIDDEN).build()
    }
  }
}

object HealthCheckDetailsResource {

  /** Returns the admin SessionUser if the request carries one; otherwise `None`. */
  private[healthcheck] def authorized(sc: SecurityContext): Option[SessionUser] = {
    if (sc == null) return None
    sc.getUserPrincipal match {
      case u: SessionUser if u.isRoleOf(UserRoleEnum.ADMIN) => Some(u)
      case _                                                => None
    }
  }
}
