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

import jakarta.ws.rs.core.SecurityContext
import org.apache.texera.auth.SessionUser
import org.apache.texera.dao.jooq.generated.enums.UserRoleEnum
import org.apache.texera.dao.jooq.generated.tables.pojos.User
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.security.Principal

class HealthCheckDetailsResourceSpec extends AnyFlatSpec with Matchers {

  private def securityContext(role: UserRoleEnum): SecurityContext = {
    val u = new User(1, "u", null, null, null, null, role, null, null, null, null)
    val principal = new SessionUser(u)
    new SecurityContext {
      override def getUserPrincipal: Principal = principal
      override def isUserInRole(role: String): Boolean = principal.isRoleOf(UserRoleEnum.valueOf(role))
      override def isSecure: Boolean = false
      override def getAuthenticationScheme: String = "Bearer"
    }
  }

  private val anonymous: SecurityContext = new SecurityContext {
    override def getUserPrincipal: Principal = null
    override def isUserInRole(role: String): Boolean = false
    override def isSecure: Boolean = false
    override def getAuthenticationScheme: String = null
  }

  "GET /admin/healthcheck/details" should "expose latency and error class to admins" in {
    val res = new HealthCheckDetailsResource(
      Seq(
        HealthCheck("a") {},
        HealthCheck("b")(throw new java.sql.SQLException("boom"))
      )
    )
    val r = res.details(securityContext(UserRoleEnum.ADMIN))
    r.getStatus shouldBe 200
    val body = r.getEntity.asInstanceOf[HealthDetails]
    body.status shouldBe HealthReport.Degraded
    val b = body.checks.find(_.name == "b").get
    b.ok shouldBe false
    b.errorClass shouldBe Some("SQLException")
    b.latencyMs should be >= 0L
  }

  it should "deny non-admin callers with 403" in {
    val res = new HealthCheckDetailsResource(Seq.empty)
    res.details(securityContext(UserRoleEnum.REGULAR)).getStatus shouldBe 403
  }

  it should "deny anonymous callers with 403" in {
    val res = new HealthCheckDetailsResource(Seq.empty)
    res.details(anonymous).getStatus shouldBe 403
  }
}
