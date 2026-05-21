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

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HealthCheckRunnerSpec extends AnyFlatSpec with Matchers with OptionValues {

  private def passing(n: String): HealthCheck = HealthCheck(n) {}
  private def failing(n: String): HealthCheck =
    HealthCheck(n)(throw new java.sql.SQLException("conn refused"))
  private def slow(n: String, sleepMs: Long): HealthCheck =
    HealthCheck(n)(Thread.sleep(sleepMs))

  "HealthCheckRunner" should "report ok when every check passes" in {
    val r = new HealthCheckRunner(Seq(passing("a"), passing("b")), 500L)
    try {
      val full = r.run()
      full.status shouldBe HealthReport.Ok
      full.checks.map(_.ok) should contain only true
      full.checks.map(_.errorClass) should contain only None
    } finally r.shutdown()
  }

  it should "report degraded when a check throws, exposing only the class name" in {
    val r = new HealthCheckRunner(Seq(passing("a"), failing("db")), 500L)
    try {
      val full = r.run()
      full.status shouldBe HealthReport.Degraded
      val dbCheck = full.checks.find(_.name == "db").value
      dbCheck.ok shouldBe false
      dbCheck.errorClass shouldBe Some("SQLException")
    } finally r.shutdown()
  }

  it should "cut a slow check off at the configured timeout, marking it TimeoutException" in {
    val timeoutMs = 100L
    val r = new HealthCheckRunner(Seq(slow("slow", 5_000L)), timeoutMs)
    try {
      val t0 = System.nanoTime()
      val full = r.run()
      val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
      // Allow a generous fudge factor for GC / CI noise, but well under the 5s sleep.
      elapsedMs should be < 1_000L
      full.status shouldBe HealthReport.Degraded
      full.checks.head.errorClass shouldBe Some("TimeoutException")
    } finally r.shutdown()
  }

  it should "run multiple slow checks in parallel rather than serially" in {
    val sleepMs = 200L
    val r = new HealthCheckRunner(
      Seq(slow("a", sleepMs), slow("b", sleepMs), slow("c", sleepMs)),
      perCheckTimeoutMillis = 1_000L
    )
    try {
      val t0 = System.nanoTime()
      val full = r.run()
      val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
      // Parallel execution should finish in ~sleepMs, not 3 * sleepMs.
      elapsedMs should be < (sleepMs * 3 / 2)
      full.status shouldBe HealthReport.Ok
    } finally r.shutdown()
  }

  it should "produce a public report that drops latency and error class" in {
    val r = new HealthCheckRunner(Seq(passing("a"), failing("b")), 500L)
    try {
      val pub = r.report()
      pub.checks.map(_.name) should contain theSameElementsAs Seq("a", "b")
      pub.checks.map(c => (c.name, c.ok)) should contain allOf (("a", true), ("b", false))
    } finally r.shutdown()
  }

}
