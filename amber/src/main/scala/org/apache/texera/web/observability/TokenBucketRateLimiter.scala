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

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

/**
  * In-memory token bucket keyed by an opaque string token. Used for both the
  * per-user and per-IP caps on the logs endpoint. Map is bounded by
  * `maxKeys`; when the cap is reached we clear (crude but bounded under a
  * hostile fanout).
  */
class TokenBucketRateLimiter(
    burst: Int,
    refillPerSecond: Double,
    maxKeys: Int = 10_000
) {
  require(burst >= 1)
  require(refillPerSecond > 0)
  require(maxKeys >= 1)

  private case class Bucket(tokens: Double, lastNanos: Long)
  private val buckets = new ConcurrentHashMap[String, Bucket]()

  def tryAcquire(key: String, nowNanos: Long = System.nanoTime()): Boolean = {
    if (buckets.size() > maxKeys) buckets.clear()
    val out = new Array[Boolean](1)
    val updater = new BiFunction[String, Bucket, Bucket] {
      override def apply(k: String, prev: Bucket): Bucket = {
        val current = Option(prev).getOrElse(Bucket(burst.toDouble, nowNanos))
        val elapsedSec = math.max(0.0, (nowNanos - current.lastNanos) / 1e9)
        val refilled = math.min(burst.toDouble, current.tokens + elapsedSec * refillPerSecond)
        if (refilled >= 1.0) {
          out(0) = true
          Bucket(refilled - 1.0, nowNanos)
        } else {
          out(0) = false
          Bucket(refilled, nowNanos)
        }
      }
    }
    buckets.compute(key, updater)
    out(0)
  }
}
