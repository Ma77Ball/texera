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

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

/**
  * Tiny token-bucket rate limiter keyed by source IP. Probes are exposed
  * unauthenticated; this caps the burst rate that one peer can use to amplify
  * traffic against the dependencies the probe touches (DB, MinIO, ...).
  *
  * The map is bounded by `maxKeys`. When the cap is reached we clear the map
  * — crude, but enough to keep memory finite under a hostile source-IP fanout.
  */
class IpRateLimiter(
    burst: Int = 10,
    refillPerSecond: Double = 10.0,
    maxKeys: Int = 10_000
) {
  require(burst >= 1)
  require(refillPerSecond > 0)
  require(maxKeys >= 1)

  private case class Bucket(tokens: Double, lastNanos: Long)
  private val buckets = new ConcurrentHashMap[String, Bucket]()

  /** Returns true if a token was available (caller may proceed). */
  def tryAcquire(ip: String, nowNanos: Long = System.nanoTime()): Boolean = {
    if (buckets.size() > maxKeys) buckets.clear()
    val result = new Array[Boolean](1)
    val updater = new BiFunction[String, Bucket, Bucket] {
      override def apply(k: String, prev: Bucket): Bucket = {
        val current = Option(prev).getOrElse(Bucket(burst.toDouble, nowNanos))
        val elapsedSec = math.max(0.0, (nowNanos - current.lastNanos) / 1e9)
        val refilled =
          math.min(burst.toDouble, current.tokens + elapsedSec * refillPerSecond)
        if (refilled >= 1.0) {
          result(0) = true
          Bucket(refilled - 1.0, nowNanos)
        } else {
          result(0) = false
          Bucket(refilled, nowNanos)
        }
      }
    }
    buckets.compute(ip, updater)
    result(0)
  }
}
