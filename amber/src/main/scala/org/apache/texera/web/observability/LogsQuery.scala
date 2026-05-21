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

import java.time.{Duration, Instant}

/**
  * Typed projection of the public `GET /api/executions/{id}/logs` query
  * parameters. The resource never accepts a raw DSL fragment; every value
  * here is either a primitive, an enum, or already validated, and the
  * OpenSearch DSL is built from these fields exclusively.
  */
final case class LogsQuery private (
    executionId: Int,
    from: Instant,
    to: Instant,
    level: Option[LogLevel],
    q: Option[String],
    size: Int,
    searchAfter: Option[String]
)

object LogsQuery {
  val MaxQLen: Int = 256
  val MaxSize: Int = 1000
  val DefaultSize: Int = 100
  val MaxWindow: Duration = Duration.ofDays(7)

  /** Sentinel for rejection from the validator — short enough to be safe to surface. */
  final case class ValidationError(message: String) extends Exception(message)

  /**
    * Validates and normalises raw HTTP inputs. Any failure throws
    * `ValidationError`; callers should translate that to a 400 with the
    * supplied message (no echoing of raw caller-supplied values into the
    * response — the messages here are all server-controlled).
    */
  @throws[ValidationError]
  def validate(
      executionId: Int,
      from: Instant,
      to: Instant,
      level: Option[String],
      q: Option[String],
      size: Option[Int],
      searchAfter: Option[String]
  ): LogsQuery = {
    if (!from.isBefore(to)) {
      throw ValidationError("from must be strictly before to")
    }
    if (Duration.between(from, to).compareTo(MaxWindow) > 0) {
      throw ValidationError("time window may not exceed 7 days")
    }
    val parsedLevel = level.map(_.trim).filter(_.nonEmpty).map(LogLevel.parse)
    val parsedQ = q.map(_.trim).filter(_.nonEmpty).map { s =>
      if (s.length > MaxQLen) throw ValidationError(s"q exceeds $MaxQLen characters")
      s
    }
    val clampedSize = size.map(s => math.min(MaxSize, math.max(1, s))).getOrElse(DefaultSize)
    val sa = searchAfter.map(_.trim).filter(_.nonEmpty).map { s =>
      // search_after is an opaque cursor we generated server-side; treat as
      // ASCII printable, cap length to prevent header / log abuse.
      if (s.length > 256) throw ValidationError("search_after cursor too long")
      if (!s.forall(c => c >= 0x20 && c < 0x7f)) {
        throw ValidationError("search_after cursor contains illegal characters")
      }
      s
    }
    LogsQuery(executionId, from, to, parsedLevel, parsedQ, clampedSize, sa)
  }
}

/** Strict enum of accepted severity filters. */
sealed abstract class LogLevel(val name: String)
object LogLevel {
  case object TRACE extends LogLevel("TRACE")
  case object DEBUG extends LogLevel("DEBUG")
  case object INFO  extends LogLevel("INFO")
  case object WARN  extends LogLevel("WARN")
  case object ERROR extends LogLevel("ERROR")

  val values: Seq[LogLevel] = Seq(TRACE, DEBUG, INFO, WARN, ERROR)

  /** Throws `LogsQuery.ValidationError` on any value outside the enum. */
  def parse(s: String): LogLevel = {
    val upper = s.toUpperCase
    values.find(_.name == upper).getOrElse {
      throw LogsQuery.ValidationError(s"unknown level: must be one of ${values.map(_.name).mkString(",")}")
    }
  }
}
