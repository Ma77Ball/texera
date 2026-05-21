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

import ch.qos.logback.classic.{Level, LoggerContext}
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.{LoggerFactory, MDC}

import scala.jdk.CollectionConverters._

class TexeraOtelAppenderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val exporter = InMemoryLogRecordExporter.create()
  private var otel: OpenTelemetry = _
  private var appender: TexeraOtelAppender = _
  private val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]

  override def beforeEach(): Unit = {
    exporter.reset()
    val provider = SdkLoggerProvider
      .builder()
      .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
      .build()
    otel = OpenTelemetrySdk.builder().setLoggerProvider(provider).build()
    TexeraOtelAppender.setOpenTelemetryForTesting(otel)
    appender = new TexeraOtelAppender()
    appender.setContext(ctx)
    appender.start()
    MDC.clear()
  }

  override def afterEach(): Unit = {
    appender.stop()
    MDC.clear()
    TexeraOtelAppender.setOpenTelemetryForTesting(OpenTelemetry.noop())
  }

  private def emit(level: Level, msg: String): Unit = {
    val logger = ctx.getLogger("test.logger").asInstanceOf[ch.qos.logback.classic.Logger]
    val event = new ch.qos.logback.classic.spi.LoggingEvent(
      logger.getName,
      logger,
      level,
      msg,
      null,
      null
    )
    appender.doAppend(event)
  }

  "TexeraOtelAppender" should "forward an INFO log to the OTel exporter" in {
    emit(Level.INFO, "hello world")
    val records = exporter.getFinishedLogRecordItems.asScala
    records should have size 1
    val r = records.head
    r.getBody.asString shouldBe "hello world"
    r.getSeverity shouldBe Severity.INFO
  }

  it should "map logback levels to OTel severities" in {
    emit(Level.ERROR, "boom")
    emit(Level.WARN, "warn")
    emit(Level.DEBUG, "dbg")
    val sevs = exporter.getFinishedLogRecordItems.asScala.map(_.getSeverity).toSet
    sevs should contain allOf (Severity.ERROR, Severity.WARN, Severity.DEBUG)
  }

  it should "strip CRLF from the body before export" in {
    emit(Level.INFO, "hello\r\nFAKE LINE")
    val body = exporter.getFinishedLogRecordItems.asScala.head.getBody.asString
    body should not include "\r"
    body should not include "\n"
  }

  it should "redact Bearer tokens in the body" in {
    emit(Level.INFO, "Authorization: Bearer abc.def.ghi")
    val body = exporter.getFinishedLogRecordItems.asScala.head.getBody.asString
    body should include("[REDACTED]")
    body should not include "abc.def.ghi"
  }

  it should "truncate bodies larger than 16 KiB" in {
    emit(Level.INFO, "x" * (32 * 1024))
    val body = exporter.getFinishedLogRecordItems.asScala.head.getBody.asString
    body.getBytes("UTF-8").length should be <= 16 * 1024
    body should endWith("...[TRUNCATED]")
  }

  it should "export allowlisted MDC keys as attributes and drop the rest" in {
    MDC.put("trace_id", "00000000000000000000000000000001")
    MDC.put("workflow.id", "wf-42")
    MDC.put("user.secret", "TOPSECRET")
    emit(Level.INFO, "with mdc")
    val r = exporter.getFinishedLogRecordItems.asScala.head
    val attrs = r.getAttributes
    attrs.get(AttributeKey.stringKey("trace_id")) shouldBe "00000000000000000000000000000001"
    attrs.get(AttributeKey.stringKey("workflow.id")) shouldBe "wf-42"
    attrs.get(AttributeKey.stringKey("user.secret")) shouldBe null
  }

  it should "be a no-op when OpenTelemetry is the noop instance" in {
    TexeraOtelAppender.setOpenTelemetryForTesting(OpenTelemetry.noop())
    emit(Level.INFO, "ignored")
    exporter.getFinishedLogRecordItems shouldBe empty
  }
}
