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

import com.typesafe.scalalogging.LazyLogging

import java.util.concurrent.{
  Callable,
  ExecutionException,
  Executors,
  TimeUnit,
  TimeoutException
}

/**
  * Runs the configured checks in parallel and gives each one at most
  * `perCheckTimeoutMillis` to complete. A check that times out, throws, or
  * returns normally produces a `CheckDetail` — exceptions are never propagated
  * to the caller, so a flaky dependency cannot crash the probe handler.
  */
class HealthCheckRunner(
    checks: Seq[HealthCheck],
    perCheckTimeoutMillis: Long = 500L
) extends LazyLogging {

  // Hard upper bound on the per-check timeout: a probe must not slowloris the request thread.
  require(perCheckTimeoutMillis >= 1 && perCheckTimeoutMillis <= 5_000)

  private val pool = {
    val size = math.max(2, checks.size)
    Executors.newFixedThreadPool(
      size,
      (r: Runnable) => {
        val t = new Thread(r, "texera-healthcheck")
        t.setDaemon(true)
        t
      }
    )
  }

  def run(): HealthDetails = {
    val started = checks.map { c =>
      val t0 = System.nanoTime()
      val task = pool.submit(new Callable[Unit] {
        override def call(): Unit = c.check()
      })
      (c, t0, task)
    }
    val outcomes = started.map {
      case (c, t0, task) =>
        try {
          task.get(perCheckTimeoutMillis, TimeUnit.MILLISECONDS)
          CheckDetail(c.name, ok = true, elapsedMs(t0), None)
        } catch {
          case _: TimeoutException =>
            task.cancel(true)
            logger.warn(s"healthcheck '${c.name}' timed out after ${perCheckTimeoutMillis}ms")
            CheckDetail(c.name, ok = false, elapsedMs(t0), Some("TimeoutException"))
          case e: ExecutionException =>
            val cause = Option(e.getCause).getOrElse(e)
            logger.warn(
              s"healthcheck '${c.name}' failed: ${cause.getClass.getSimpleName}",
              cause
            )
            CheckDetail(c.name, ok = false, elapsedMs(t0), Some(cause.getClass.getSimpleName))
          case e: InterruptedException =>
            Thread.currentThread().interrupt()
            CheckDetail(c.name, ok = false, elapsedMs(t0), Some(e.getClass.getSimpleName))
        }
    }
    val status = if (outcomes.forall(_.ok)) HealthReport.Ok else HealthReport.Degraded
    HealthDetails(status, outcomes)
  }

  /** Drop the internal error classes — what anonymous probes get to see. */
  def report(): HealthReport = {
    val full = run()
    HealthReport(full.status, full.checks.map(c => PublicCheck(c.name, c.ok)))
  }

  def shutdown(): Unit = pool.shutdownNow()

  private def elapsedMs(startedNanos: Long): Long =
    (System.nanoTime() - startedNanos) / 1_000_000L
}
