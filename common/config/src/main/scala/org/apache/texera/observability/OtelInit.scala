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

import com.typesafe.scalalogging.LazyLogging
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.{SimpleSpanProcessor, SpanExporter}

import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bootstraps OpenTelemetry for a Texera service.
 *
 * Default-off: unless `OTEL_SDK_DISABLED` is set to "false", returns [[OpenTelemetry.noop]]
 * so deployments without observability env are byte-identical to today.
 *
 * Security boundaries enforced here (do not relax without review):
 *   - The OTLP endpoint host must be in [[OtelInit.AllowedEndpointHosts]]; otherwise the SDK
 *     stays disabled with a single WARN. This prevents an attacker who can set environment
 *     variables on the process from exfiltrating telemetry to an arbitrary host.
 *   - Only [[OtelInit.AllowedResourceAttributeKeys]] are accepted from
 *     `OTEL_RESOURCE_ATTRIBUTES`; arbitrary keys are dropped.
 */
object OtelInit extends LazyLogging {

  /** Hosts allowed in `OTEL_EXPORTER_OTLP_ENDPOINT`. Cluster-local DNS only. */
  private val AllowedEndpointHosts: Set[String] = Set(
    "localhost",
    "127.0.0.1",
    "::1",
    "otel-collector",
    "otel-collector.default.svc.cluster.local"
  )

  /** Schemes allowed in `OTEL_EXPORTER_OTLP_ENDPOINT`. */
  private val AllowedEndpointSchemes: Set[String] = Set("http", "https", "grpc")

  /** Resource attribute keys honored from `OTEL_RESOURCE_ATTRIBUTES`. */
  private val AllowedResourceAttributeKeys: Set[String] = Set(
    "service.name",
    "service.version",
    "deployment.environment"
  )

  private val initialized = new AtomicBoolean(false)
  @volatile private var current: OpenTelemetry = OpenTelemetry.noop()

  /**
   * Initialize OpenTelemetry from the process environment.
   *
   * Safe to call once at service startup. Subsequent calls are no-ops and return the
   * already-initialized [[OpenTelemetry]] instance.
   */
  def init(serviceName: String, serviceVersion: String): OpenTelemetry = {
    if (!initialized.compareAndSet(false, true)) return current
    current = build(sys.env, serviceName, serviceVersion, testExporter = None)
    current
  }

  /** Convenience: derive the version from the running JAR's manifest, else "unknown". */
  def init(serviceName: String): OpenTelemetry = {
    val v = Option(getClass.getPackage.getImplementationVersion).getOrElse("unknown")
    init(serviceName, v)
  }

  /** Test-only entry point: reset the singleton and inject env / exporter. */
  private[observability] def initForTesting(
      env: Map[String, String],
      serviceName: String,
      serviceVersion: String,
      testExporter: Option[SpanExporter]
  ): OpenTelemetry = {
    initialized.set(true)
    current = build(env, serviceName, serviceVersion, testExporter)
    current
  }

  private[observability] def resetForTesting(): Unit = {
    initialized.set(false)
    current = OpenTelemetry.noop()
  }

  private def build(
      env: Map[String, String],
      serviceName: String,
      serviceVersion: String,
      testExporter: Option[SpanExporter]
  ): OpenTelemetry = {
    val disabled = env.get("OTEL_SDK_DISABLED").forall(_.equalsIgnoreCase("true"))
    if (disabled) return OpenTelemetry.noop()

    env.get("OTEL_EXPORTER_OTLP_ENDPOINT") match {
      case Some(ep) if !endpointAllowed(ep) =>
        logger.warn(
          s"OTel SDK staying disabled: OTLP endpoint '$ep' is not in the allowlist."
        )
        return OpenTelemetry.noop()
      case _ => // empty or allowlisted — fine
    }

    val resource = buildResource(env, serviceName, serviceVersion)
    val exporter: SpanExporter = testExporter.getOrElse(NoopSpanExporter)
    val tracerProvider = SdkTracerProvider
      .builder()
      .setResource(resource)
      .addSpanProcessor(SimpleSpanProcessor.create(exporter))
      .build()

    val sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()

    // Install on the Logback appender so logs are bridged through this SDK.
    TexeraOtelAppender.setOpenTelemetry(sdk)

    // Emit a single startup span so any configured exporter sees activity. Body is
    // intentionally minimal — no env values, no hostnames beyond service.name.
    val span = sdk.getTracer("org.apache.texera.observability").spanBuilder("service.start").startSpan()
    try {
      // no attributes — see scaladoc
    } finally span.end()

    sdk
  }

  private def endpointAllowed(endpoint: String): Boolean = {
    try {
      val uri = URI.create(endpoint)
      val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
      val host = Option(uri.getHost).map(_.toLowerCase).getOrElse("")
      AllowedEndpointSchemes.contains(scheme) && AllowedEndpointHosts.contains(host)
    } catch {
      case _: IllegalArgumentException => false
    }
  }

  private def buildResource(
      env: Map[String, String],
      serviceName: String,
      serviceVersion: String
  ): Resource = {
    val base = Resource
      .getDefault
      .toBuilder
      .put("service.name", serviceName)
      .put("service.version", serviceVersion)

    env.get("OTEL_RESOURCE_ATTRIBUTES").foreach { raw =>
      raw.split(",").foreach { pair =>
        val idx = pair.indexOf('=')
        if (idx > 0) {
          val k = pair.substring(0, idx).trim
          val v = pair.substring(idx + 1).trim
          if (AllowedResourceAttributeKeys.contains(k)) {
            base.put(k, v)
          }
        }
      }
    }
    base.build()
  }

  private object NoopSpanExporter extends SpanExporter {
    override def `export`(spans: java.util.Collection[io.opentelemetry.sdk.trace.data.SpanData]) =
      CompletableResultCode.ofSuccess()
    override def flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override def shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
  }
}
