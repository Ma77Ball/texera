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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LogSanitizerSpec extends AnyFlatSpec with Matchers {

  "sanitizeBody" should "return empty string for null" in {
    LogSanitizer.sanitizeBody(null) shouldBe ""
  }

  it should "strip CRLF and other C0 control chars (preserving tab)" in {
    val out = LogSanitizer.sanitizeBody("hello\r\nFAKE LINE\tworldx")
    out should not include "\r"
    out should not include "\n"
    out should not include ""
    out should include("\t") // tab preserved
    out should include("hello")
    out should include("FAKE LINE")
    out should include("world")
  }

  it should "redact Bearer tokens (case-insensitive)" in {
    LogSanitizer.sanitizeBody("Authorization: Bearer abc.def.ghi") should include("[REDACTED]")
    LogSanitizer.sanitizeBody("Authorization: Bearer abc") should not include "abc"
    LogSanitizer.sanitizeBody("authorization:bearer xyz") should include("[REDACTED]")
  }

  it should "redact password=... patterns" in {
    val out = LogSanitizer.sanitizeBody("connect with password=hunter2 ok")
    out should include("[REDACTED]")
    out should not include "hunter2"
  }

  it should "redact AWS access key IDs" in {
    val out = LogSanitizer.sanitizeBody("key AKIAIOSFODNN7EXAMPLE in config")
    out should include("[REDACTED]")
    out should not include "AKIAIOSFODNN7EXAMPLE"
  }

  it should "cap output at 16 KiB and add a truncation marker" in {
    val big = "x" * (32 * 1024)
    val out = LogSanitizer.sanitizeBody(big)
    out.getBytes("UTF-8").length should be <= 16 * 1024
    out should endWith("...[TRUNCATED]")
  }

  it should "leave short, clean messages untouched" in {
    LogSanitizer.sanitizeBody("ordinary message") shouldBe "ordinary message"
  }

  "filterMdc" should "keep only allowlisted keys" in {
    val in = new java.util.HashMap[String, String]()
    in.put("trace_id", "abc")
    in.put("span_id", "def")
    in.put("workflow.id", "wf-1")
    in.put("execution.id", "ex-1")
    in.put("user.secret", "TOPSECRET")
    in.put("forged.line", "evil")
    val out = LogSanitizer.filterMdc(in)
    out.keySet shouldBe Set("trace_id", "span_id", "workflow.id", "execution.id")
    out.get("user.secret") shouldBe None
    out.get("forged.line") shouldBe None
    out("trace_id") shouldBe "abc"
  }

  it should "handle null MDC map" in {
    LogSanitizer.filterMdc(null) shouldBe Map.empty[String, String]
  }
}
