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
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.{Span, SpanKind, StatusCode}
import io.opentelemetry.context.Context


/**
 * Trace-handling helpers used by the workflow execution path.
 *
 * Security boundaries enforced here (do not relax without review):
 *
 *   - Inbound W3C `traceparent` headers must match the spec exactly. Invalid
 *     values are dropped and a fresh root context is returned so an attacker
 *     who controls the header cannot forge a trace id that downstream tooling
 *     trusts.
 *   - `tracestate` is restricted to printable ASCII and length-capped.
 *   - Span attribute values originating from user input pass through one of
 *     [[validateWorkflowId]], [[validateOperatorId]], or [[capAttribute]] so
 *     no unbounded / control-laden value reaches the exporter.
 */
object WorkflowTracing {

  private val TraceparentRegex = "^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$".r
  private val ZeroTraceId = "00000000000000000000000000000000"
  private val ZeroSpanId = "0000000000000000"

  private val IdCharsRegex = "^[A-Za-z0-9_.\\-]{1,64}$".r
  private val MaxTracestateLen = 512
  private val MaxAttrLen = 256

  val WorkflowIdKey: AttributeKey[String] = AttributeKey.stringKey("workflow.id")
  val ExecutionIdKey: AttributeKey[String] = AttributeKey.stringKey("execution.id")
  val OperatorIdKey: AttributeKey[String] = AttributeKey.stringKey("operator.id")

  def validateTraceparent(raw: String): Option[String] = {
    if (raw == null) return None
    val s = raw.trim
    TraceparentRegex.findFirstIn(s).flatMap { _ =>
      val parts = s.split('-')
      if (parts(1) == ZeroTraceId || parts(2) == ZeroSpanId) None else Some(s)
    }
  }

  def validateTracestate(raw: String): Option[String] = {
    if (raw == null) return None
    if (raw.length > MaxTracestateLen) return None
    if (raw.exists(c => c < 0x20 || c > 0x7E)) return None
    Some(raw)
  }

  /**
   * Accepts identifiers Texera actually emits: numeric (Long) and UUID alike.
   * Restricts to `[A-Za-z0-9_.-]{1,64}` so injection vectors like path
   * traversal, control chars, or query DSL syntax cannot reach the exporter.
   */
  def validateWorkflowId(raw: String): Option[String] =
    if (raw == null) None else IdCharsRegex.findFirstIn(raw)

  def validateOperatorId(raw: String): Option[String] =
    if (raw == null) None else IdCharsRegex.findFirstIn(raw)

  def capAttribute(raw: String): String = {
    if (raw == null) return ""
    val stripped = raw.map(c => if (c == '\t' || c >= 0x20) c else ' ')
    if (stripped.length <= MaxAttrLen) stripped else stripped.substring(0, MaxAttrLen)
  }

  /**
   * Build a [[Context]] from an inbound traceparent header. Falls back to the
   * supplied parent (or root) when the header is missing or invalid.
   */
  def contextFromTraceparent(traceparent: String, tracestate: String): Context = {
    val base = Context.root()
    validateTraceparent(traceparent) match {
      case None => base
      case Some(tp) =>
        val parts = tp.split('-')
        val sampled = (Integer.parseInt(parts(3), 16) & 0x01) == 0x01
        val flags =
          if (sampled) io.opentelemetry.api.trace.TraceFlags.getSampled
          else io.opentelemetry.api.trace.TraceFlags.getDefault
        val state = validateTracestate(tracestate)
          .map { s =>
            val builder = io.opentelemetry.api.trace.TraceState.builder()
            s.split(',').foreach { kv =>
              val eq = kv.indexOf('=')
              if (eq > 0) builder.put(kv.substring(0, eq).trim, kv.substring(eq + 1).trim)
            }
            builder.build()
          }
          .getOrElse(io.opentelemetry.api.trace.TraceState.getDefault)
        val sc = io.opentelemetry.api.trace.SpanContext
          .createFromRemoteParent(parts(1), parts(2), flags, state)
        base.`with`(Span.wrap(sc))
    }
  }

  /**
   * Run `body` under a `workflow.execute` span. Attributes are added only when
   * the caller-supplied identifiers pass validation; exceptions are recorded
   * on the span before being rethrown.
   */
  def withWorkflowSpan[A](
      otel: OpenTelemetry,
      workflowId: String,
      executionId: String,
      parentContext: Context
  )(body: Context => A): A = withSpan(otel, "workflow.execute", parentContext) { (ctx, span) =>
    validateWorkflowId(workflowId).foreach(v => span.setAttribute(WorkflowIdKey, v))
    validateWorkflowId(executionId).foreach(v => span.setAttribute(ExecutionIdKey, v))
    body(ctx)
  }

  /** Run `body` under an `operator.execute` span. */
  def withOperatorSpan[A](
      otel: OpenTelemetry,
      operatorId: String,
      parentContext: Context
  )(body: Context => A): A = withSpan(otel, "operator.execute", parentContext) { (ctx, span) =>
    validateOperatorId(operatorId).foreach(v => span.setAttribute(OperatorIdKey, v))
    body(ctx)
  }

  private def withSpan[A](
      otel: OpenTelemetry,
      name: String,
      parentContext: Context
  )(body: (Context, Span) => A): A = {
    val span = otel
      .getTracer("org.apache.texera.observability")
      .spanBuilder(name)
      .setParent(parentContext)
      .setSpanKind(SpanKind.INTERNAL)
      .startSpan()
    val ctx = parentContext.`with`(span)
    val scope = ctx.makeCurrent()
    try body(ctx, span)
    catch {
      case t: Throwable =>
        span.recordException(t)
        span.setStatus(StatusCode.ERROR)
        throw t
    } finally {
      scope.close()
      span.end()
    }
  }
}
