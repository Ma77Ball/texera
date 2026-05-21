// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.texera.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.logs.Severity

/**
 * Logback appender that emits sanitized records via the OTel logs API.
 *
 * The appender resolves the active [[OpenTelemetry]] instance lazily, so it is
 * safe to mount via `logback.xml` before [[OtelInit]] has run — events before
 * init are routed to the no-op SDK and dropped.
 *
 * All bodies and MDC values pass through [[LogSanitizer]]; see that class for
 * the security boundaries enforced.
 */
class TexeraOtelAppender extends AppenderBase[ILoggingEvent] {

  override def append(event: ILoggingEvent): Unit = {
    val otel = TexeraOtelAppender.currentOpenTelemetry
    if (otel eq OpenTelemetry.noop()) return
    val logger = otel.getLogsBridge.get("org.apache.texera.observability")
    val body = LogSanitizer.sanitizeBody(event.getFormattedMessage)
    val attrs = TexeraOtelAppender.buildAttributes(event)
    logger
      .logRecordBuilder()
      .setBody(body)
      .setSeverity(TexeraOtelAppender.toSeverity(event.getLevel))
      .setSeverityText(event.getLevel.toString)
      .setAllAttributes(attrs)
      .setTimestamp(event.getTimeStamp, java.util.concurrent.TimeUnit.MILLISECONDS)
      .emit()
  }
}

object TexeraOtelAppender {

  @volatile private var openTelemetry: OpenTelemetry = OpenTelemetry.noop()

  /** Install the SDK that the appender should emit through. Called from [[OtelInit]]. */
  def setOpenTelemetry(otel: OpenTelemetry): Unit = openTelemetry = otel

  /** Test-only alias; same behavior as [[setOpenTelemetry]] but signals intent. */
  private[observability] def setOpenTelemetryForTesting(otel: OpenTelemetry): Unit =
    openTelemetry = otel

  private def currentOpenTelemetry: OpenTelemetry = openTelemetry

  private[observability] def buildAttributes(event: ILoggingEvent): Attributes = {
    val b = Attributes.builder()
    b.put(AttributeKey.stringKey("logger.name"), event.getLoggerName)
    b.put(AttributeKey.stringKey("thread.name"), event.getThreadName)
    LogSanitizer.filterMdc(event.getMDCPropertyMap).foreach {
      case (k, v) => b.put(AttributeKey.stringKey(k), v)
    }
    b.build()
  }

  private[observability] def toSeverity(level: Level): Severity = level.toInt match {
    case Level.TRACE_INT => Severity.TRACE
    case Level.DEBUG_INT => Severity.DEBUG
    case Level.INFO_INT  => Severity.INFO
    case Level.WARN_INT  => Severity.WARN
    case Level.ERROR_INT => Severity.ERROR
    case _               => Severity.UNDEFINED_SEVERITY_NUMBER
  }
}
