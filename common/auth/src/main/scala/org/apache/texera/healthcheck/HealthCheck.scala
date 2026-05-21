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

/** A single dependency check (DB, S3/MinIO, Pekko cluster, ...). */
trait HealthCheck {

  /** Stable, lowercase, ASCII identifier exposed to clients (`db`, `minio`, `pekko`, ...). */
  def name: String

  /** Throw on failure; return on success. Implementations must be cheap. */
  @throws[Exception]
  def check(): Unit
}

object HealthCheck {
  def apply(checkName: String)(thunk: => Unit): HealthCheck =
    new HealthCheck {
      override val name: String = checkName
      override def check(): Unit = thunk
    }
}

/** Public projection — no error strings, just `(name, ok)`. */
final case class PublicCheck(name: String, ok: Boolean)
final case class HealthReport(status: String, checks: Seq[PublicCheck])

object HealthReport {
  val Ok = "ok"
  val Degraded = "degraded"
}

/**
  * Admin-only projection. `errorClass` carries the simple class name of the throwable
  * (e.g. `TimeoutException`, `SQLException`) but never the message or stack trace.
  */
final case class CheckDetail(
    name: String,
    ok: Boolean,
    latencyMs: Long,
    errorClass: Option[String]
)
final case class HealthDetails(status: String, checks: Seq[CheckDetail])
