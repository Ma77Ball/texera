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

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class OtelInitSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val exporter = InMemorySpanExporter.create()

  override def beforeEach(): Unit = {
    exporter.reset()
    OtelInit.resetForTesting()
  }

  "OtelInit" should "return a no-op SDK and emit no spans when OTEL_SDK_DISABLED=true" in {
    val otel = OtelInit.initForTesting(
      env = Map("OTEL_SDK_DISABLED" -> "true"),
      serviceName = "test-svc",
      serviceVersion = "0.0.0",
      testExporter = Some(exporter)
    )
    otel shouldBe OpenTelemetry.noop()
    exporter.getFinishedSpanItems.asScala shouldBe empty
  }

  it should "configure the SDK and emit a service.start span when enabled" in {
    OtelInit.initForTesting(
      env = Map("OTEL_SDK_DISABLED" -> "false"),
      serviceName = "test-svc",
      serviceVersion = "1.2.3",
      testExporter = Some(exporter)
    )
    val spans = exporter.getFinishedSpanItems.asScala
    spans should have size 1
    val s = spans.head
    s.getName shouldBe "service.start"
    s.getKind shouldBe SpanKind.INTERNAL
    s.getResource.getAttribute(
      io.opentelemetry.api.common.AttributeKey.stringKey("service.name")
    ) shouldBe "test-svc"
    s.getResource.getAttribute(
      io.opentelemetry.api.common.AttributeKey.stringKey("service.version")
    ) shouldBe "1.2.3"
  }

  it should "reject a non-allowlisted OTLP endpoint and stay disabled" in {
    val otel = OtelInit.initForTesting(
      env = Map(
        "OTEL_SDK_DISABLED" -> "false",
        "OTEL_EXPORTER_OTLP_ENDPOINT" -> "http://attacker.example.com"
      ),
      serviceName = "test-svc",
      serviceVersion = "0.0.0",
      testExporter = Some(exporter)
    )
    otel shouldBe OpenTelemetry.noop()
    exporter.getFinishedSpanItems.asScala shouldBe empty
  }

  it should "reject a non-http/grpc scheme and stay disabled" in {
    val otel = OtelInit.initForTesting(
      env = Map(
        "OTEL_SDK_DISABLED" -> "false",
        "OTEL_EXPORTER_OTLP_ENDPOINT" -> "file:///etc/passwd"
      ),
      serviceName = "test-svc",
      serviceVersion = "0.0.0",
      testExporter = Some(exporter)
    )
    otel shouldBe OpenTelemetry.noop()
    exporter.getFinishedSpanItems.asScala shouldBe empty
  }

  it should "accept localhost OTLP endpoints" in {
    val otel = OtelInit.initForTesting(
      env = Map(
        "OTEL_SDK_DISABLED" -> "false",
        "OTEL_EXPORTER_OTLP_ENDPOINT" -> "http://localhost:4318"
      ),
      serviceName = "test-svc",
      serviceVersion = "0.0.0",
      testExporter = Some(exporter)
    )
    otel should not be OpenTelemetry.noop()
    exporter.getFinishedSpanItems.asScala.map(_.getName) should contain("service.start")
  }

  it should "drop non-allowlisted resource attributes" in {
    OtelInit.initForTesting(
      env = Map(
        "OTEL_SDK_DISABLED" -> "false",
        "OTEL_RESOURCE_ATTRIBUTES" -> "secret=v,deployment.environment=staging,bad.key=x"
      ),
      serviceName = "test-svc",
      serviceVersion = "0.0.0",
      testExporter = Some(exporter)
    )
    val res = exporter.getFinishedSpanItems.asScala.head.getResource
    res.getAttribute(
      io.opentelemetry.api.common.AttributeKey.stringKey("deployment.environment")
    ) shouldBe "staging"
    Option(
      res.getAttribute(io.opentelemetry.api.common.AttributeKey.stringKey("secret"))
    ) shouldBe None
    Option(
      res.getAttribute(io.opentelemetry.api.common.AttributeKey.stringKey("bad.key"))
    ) shouldBe None
  }

  it should "default to disabled when OTEL_SDK_DISABLED is unset" in {
    val otel = OtelInit.initForTesting(
      env = Map.empty,
      serviceName = "test-svc",
      serviceVersion = "0.0.0",
      testExporter = Some(exporter)
    )
    otel shouldBe OpenTelemetry.noop()
    exporter.getFinishedSpanItems.asScala shouldBe empty
  }
}
