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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TokenBucketRateLimiterSpec extends AnyFlatSpec with Matchers {
  private val t0: Long = 1_000_000_000L

  "TokenBucketRateLimiter" should "allow up to `burst` calls before rejecting" in {
    val rl = new TokenBucketRateLimiter(burst = 3, refillPerSecond = 10.0)
    (1 to 3).foreach(_ => rl.tryAcquire("a", t0) shouldBe true)
    rl.tryAcquire("a", t0) shouldBe false
  }

  it should "refill at the configured rate" in {
    val rl = new TokenBucketRateLimiter(burst = 2, refillPerSecond = 10.0)
    rl.tryAcquire("a", t0) shouldBe true
    rl.tryAcquire("a", t0) shouldBe true
    rl.tryAcquire("a", t0) shouldBe false
    // 200 ms later: 2 tokens regenerated (capped at burst).
    val later = t0 + 200_000_000L
    rl.tryAcquire("a", later) shouldBe true
    rl.tryAcquire("a", later) shouldBe true
    rl.tryAcquire("a", later) shouldBe false
  }

  it should "track each key independently" in {
    val rl = new TokenBucketRateLimiter(burst = 1, refillPerSecond = 1.0)
    rl.tryAcquire("a", t0) shouldBe true
    rl.tryAcquire("a", t0) shouldBe false
    rl.tryAcquire("b", t0) shouldBe true
  }
}
