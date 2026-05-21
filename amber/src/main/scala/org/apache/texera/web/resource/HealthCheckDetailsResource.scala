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

package org.apache.texera.web.resource

import org.apache.texera.auth.SessionUser
import org.apache.texera.dao.jooq.generated.enums.UserRoleEnum
import org.apache.texera.healthcheck._

import javax.ws.rs.core.{Context, MediaType, Response, SecurityContext}
import javax.ws.rs.{GET, Path, Produces}

/**
  * `javax` mirror of `org.apache.texera.healthcheck.HealthCheckDetailsResource`
  * for amber's Dropwizard 1.3 stack. Reads the SessionUser from the
  * SecurityContext set by `org.apache.texera.web.auth.UserAuthenticator`.
  */
@Path("/admin/healthcheck/details")
@Produces(Array(MediaType.APPLICATION_JSON))
class HealthCheckDetailsResource(
    checks: Seq[HealthCheck] = Seq.empty,
    perCheckTimeoutMillis: Long = 500L
) {

  private val runner = new HealthCheckRunner(checks, perCheckTimeoutMillis)

  @GET
  def details(@Context sc: SecurityContext): Response = {
    val authorized = Option(sc).flatMap(c => Option(c.getUserPrincipal)).exists {
      case u: SessionUser => u.isRoleOf(UserRoleEnum.ADMIN)
      case _              => false
    }
    if (authorized) Response.ok(runner.run()).build()
    else Response.status(Response.Status.FORBIDDEN).build()
  }
}
