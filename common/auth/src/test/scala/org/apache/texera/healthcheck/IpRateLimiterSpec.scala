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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IpRateLimiterSpec extends AnyFlatSpec with Matchers {

  // All tests pin "now" so they are deterministic; real elapsed clock time is
  // never consulted by tryAcquire when we pass nowNanos explicitly.
  private val t0: Long = 1_000_000_000L

  "IpRateLimiter" should "allow up to `burst` calls before rejecting" in {
    val limiter = new IpRateLimiter(burst = 3, refillPerSecond = 10.0)
    (1 to 3).foreach(_ => limiter.tryAcquire("1.1.1.1", t0) shouldBe true)
    limiter.tryAcquire("1.1.1.1", t0) shouldBe false
  }

  it should "refill tokens over time at the configured rate" in {
    val limiter = new IpRateLimiter(burst = 2, refillPerSecond = 10.0)
    limiter.tryAcquire("1.1.1.1", t0) shouldBe true
    limiter.tryAcquire("1.1.1.1", t0) shouldBe true
    limiter.tryAcquire("1.1.1.1", t0) shouldBe false
    // 200 ms later: 200ms * 10/s = 2 tokens (capped at burst=2).
    val later = t0 + 200_000_000L
    limiter.tryAcquire("1.1.1.1", later) shouldBe true
    limiter.tryAcquire("1.1.1.1", later) shouldBe true
    limiter.tryAcquire("1.1.1.1", later) shouldBe false
  }

  it should "track each IP independently" in {
    val limiter = new IpRateLimiter(burst = 1, refillPerSecond = 1.0)
    limiter.tryAcquire("1.1.1.1", t0) shouldBe true
    limiter.tryAcquire("1.1.1.1", t0) shouldBe false
    limiter.tryAcquire("2.2.2.2", t0) shouldBe true
    limiter.tryAcquire("2.2.2.2", t0) shouldBe false
  }

  it should "reject after a sustained flood from one IP" in {
    val limiter = new IpRateLimiter(burst = 10, refillPerSecond = 10.0)
    // 100 calls all at the same instant — only the first 10 should pass.
    val results = (1 to 100).map(_ => limiter.tryAcquire("1.1.1.1", t0))
    results.count(identity) shouldBe 10
  }
}
