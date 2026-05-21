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

package org.apache.texera.amber.engine.architecture.pythonworker

import io.opentelemetry.api.trace.{Span, TraceFlags}
import io.opentelemetry.context.Context
import org.apache.arrow.flight.{CallHeaders, FlightCallHeaders, HeaderCallOption}

/**
 * Builds a [[HeaderCallOption]] carrying a W3C `traceparent` derived from the
 * current OTel context, for cross-process trace propagation over Arrow Flight.
 *
 * If no valid current span exists, returns an empty option — never forges a
 * traceparent from incomplete state.
 */
object TraceparentHeaders {

  /** Returns Some(HeaderCallOption) when a valid current span exists; None otherwise. */
  def fromCurrentContext(): Option[HeaderCallOption] =
    fromContext(Context.current())

  private[pythonworker] def fromContext(ctx: Context): Option[HeaderCallOption] =
    traceparentOf(ctx).map(toHeaderOption)

  /** Build the W3C traceparent string from a Context. Pure, testable. */
  private[pythonworker] def traceparentOf(ctx: Context): Option[String] = {
    val sc = Span.fromContext(ctx).getSpanContext
    if (!sc.isValid) return None
    val sampled = if (sc.getTraceFlags.equals(TraceFlags.getSampled)) "01" else "00"
    Some(s"00-${sc.getTraceId}-${sc.getSpanId}-$sampled")
  }

  private def toHeaderOption(tp: String): HeaderCallOption = {
    val headers: CallHeaders = new FlightCallHeaders()
    headers.insert("traceparent", tp)
    new HeaderCallOption(headers)
  }
}
