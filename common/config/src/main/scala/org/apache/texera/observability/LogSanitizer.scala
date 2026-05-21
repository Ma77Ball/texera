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

import java.util.{Map => JMap}
import scala.util.matching.Regex

/**
 * Pure-function preprocessing for log records before they leave the process.
 *
 * Three security boundaries enforced here (do not relax without review):
 *
 *   - Bodies are stripped of C0 control characters except `\t`. Prevents log
 *     injection where an attacker crafts a message containing `\r\n` to forge
 *     a fake follow-on line in any text-rendered backend.
 *   - Bodies are capped at [[MaxBodyBytes]]; overage is truncated with a
 *     visible marker. Prevents a runaway log line from blowing the exporter
 *     buffer.
 *   - A small allowlist controls which MDC keys may become record attributes
 *     ([[AllowedMdcKeys]]). Prevents accidental MDC pollution from forging
 *     attributes that downstream tooling trusts.
 *
 * Secret scrubbing covers conservative defaults; operators may extend via
 * configuration in a follow-up PR.
 */
object LogSanitizer {

  val AllowedMdcKeys: Set[String] = Set("trace_id", "span_id", "workflow.id", "execution.id")
  val MaxBodyBytes: Int = 16 * 1024
  private val TruncationMarker = "...[TRUNCATED]"

  private val DefaultSecretPatterns: Seq[Regex] = Seq(
    "(?i)authorization\\s*:\\s*bearer\\s+\\S+".r,
    "(?i)password\\s*=\\s*\\S+".r,
    "AKIA[0-9A-Z]{16}".r
  )

  def sanitizeBody(msg: String): String = {
    if (msg == null) return ""
    var s = stripControls(msg)
    DefaultSecretPatterns.foreach { p => s = p.replaceAllIn(s, "[REDACTED]") }
    truncate(s)
  }

  def filterMdc(mdc: JMap[String, String]): Map[String, String] = {
    if (mdc == null) return Map.empty
    val b = Map.newBuilder[String, String]
    val it = mdc.entrySet.iterator
    while (it.hasNext) {
      val e = it.next()
      if (AllowedMdcKeys.contains(e.getKey) && e.getValue != null) {
        b += (e.getKey -> e.getValue)
      }
    }
    b.result()
  }

  private def stripControls(s: String): String = {
    val out = new java.lang.StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (c == '\t' || c >= 0x20) out.append(c) else out.append(' ')
      i += 1
    }
    out.toString
  }

  private def truncate(s: String): String = {
    val bytes = s.getBytes("UTF-8")
    if (bytes.length <= MaxBodyBytes) return s
    val budget = MaxBodyBytes - TruncationMarker.getBytes("UTF-8").length
    // Walk back to a complete UTF-8 character to avoid splitting a code point.
    var cut = budget
    while (cut > 0 && (bytes(cut) & 0xC0) == 0x80) cut -= 1
    new String(bytes, 0, cut, "UTF-8") + TruncationMarker
  }
}
