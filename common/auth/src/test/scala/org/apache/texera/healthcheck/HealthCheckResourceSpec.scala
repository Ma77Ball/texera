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
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HealthCheckResourceSpec extends AnyFlatSpec with Matchers {

  // Minimal HttpServletRequest stub — only `getRemoteAddr` and `getHeader` are
  // ever read by the resource under test.
  private def req(remoteAddr: String, xff: String = null): HttpServletRequest = {
    val handler = new java.lang.reflect.InvocationHandler {
      override def invoke(proxy: Any, method: java.lang.reflect.Method, args: Array[AnyRef]): AnyRef = {
        method.getName match {
          case "getRemoteAddr" => remoteAddr
          case "getHeader"     => if (args(0) == "X-Forwarded-For") xff else null
          case _               => null
        }
      }
    }
    java.lang.reflect.Proxy
      .newProxyInstance(
        classOf[HttpServletRequest].getClassLoader,
        Array(classOf[HttpServletRequest]),
        handler
      )
      .asInstanceOf[HttpServletRequest]
  }

  "GET /healthcheck/live" should "return 200 with status=ok when the process is up" in {
    val res = new HealthCheckResource(Seq.empty)
    val r = res.live(req("127.0.0.1"))
    r.getStatus shouldBe 200
    r.getEntity.asInstanceOf[HealthReport].status shouldBe HealthReport.Ok
  }

  "GET /healthcheck/ready" should "return 200 with no error string when every check passes" in {
    val res = new HealthCheckResource(Seq(HealthCheck("db") {}))
    val r = res.ready(req("127.0.0.1"))
    r.getStatus shouldBe 200
    val body = r.getEntity.asInstanceOf[HealthReport]
    body.status shouldBe HealthReport.Ok
    body.checks should contain only PublicCheck("db", ok = true)
  }

  it should "return 503 and leak no error message when a dependency fails" in {
    val res = new HealthCheckResource(
      Seq(HealthCheck("db")(throw new java.sql.SQLException("conn refused: see secret-stack-trace")))
    )
    val r = res.ready(req("127.0.0.1"))
    r.getStatus shouldBe 503
    val body = r.getEntity.asInstanceOf[HealthReport]
    body.status shouldBe HealthReport.Degraded
    body.checks should contain only PublicCheck("db", ok = false)
    // Public response must not echo the exception message anywhere.
    body.toString should not include "secret-stack-trace"
    body.toString should not include "conn refused"
  }

  it should "time out a slow dependency and still respond well under 1s" in {
    val res = new HealthCheckResource(
      Seq(HealthCheck("slow")(Thread.sleep(5_000L))),
      perCheckTimeoutMillis = 100L
    )
    val t0 = System.nanoTime()
    val r = res.ready(req("127.0.0.1"))
    val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
    r.getStatus shouldBe 503
    elapsedMs should be < 1_000L
  }

  it should "rate-limit per source IP (token bucket)" in {
    val res = new HealthCheckResource(
      Seq.empty,
      rateLimiter = new IpRateLimiter(burst = 2, refillPerSecond = 0.001)
    )
    res.ready(req("9.9.9.9")).getStatus shouldBe 200
    res.ready(req("9.9.9.9")).getStatus shouldBe 200
    res.ready(req("9.9.9.9")).getStatus shouldBe 429
    // A different IP gets its own bucket.
    res.ready(req("8.8.8.8")).getStatus shouldBe 200
  }

  it should "respect X-Forwarded-For for rate-limit keying" in {
    val res = new HealthCheckResource(
      Seq.empty,
      rateLimiter = new IpRateLimiter(burst = 1, refillPerSecond = 0.001)
    )
    res.ready(req("10.0.0.1", xff = "1.2.3.4")).getStatus shouldBe 200
    // Different forwarded-for, same remote addr → distinct bucket.
    res.ready(req("10.0.0.1", xff = "5.6.7.8")).getStatus shouldBe 200
    // Repeat of first forwarded-for → rejected.
    res.ready(req("10.0.0.1", xff = "1.2.3.4")).getStatus shouldBe 429
  }
}
