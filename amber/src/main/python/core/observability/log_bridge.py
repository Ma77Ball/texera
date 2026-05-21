# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Bridge loguru records into OpenTelemetry logs with the same sanitization
guarantees as the Scala-side TexeraOtelAppender. See LogSanitizer.scala for
the rationale behind each boundary; this file mirrors them so a Python
worker cannot leak more than the Scala services do.
"""

import re
from typing import Callable, Dict, Mapping, Optional

from loguru import logger

ALLOWED_MDC_KEYS = frozenset({"trace_id", "span_id", "workflow.id", "execution.id"})
MAX_BODY_BYTES = 16 * 1024
_TRUNCATION_MARKER = "...[TRUNCATED]"

_SECRET_PATTERNS = [
    re.compile(r"(?i)authorization\s*:\s*bearer\s+\S+"),
    re.compile(r"(?i)password\s*=\s*\S+"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
]


def sanitize_body(msg: Optional[str]) -> str:
    if msg is None:
        return ""
    s = _strip_controls(msg)
    for p in _SECRET_PATTERNS:
        s = p.sub("[REDACTED]", s)
    return _truncate(s)


def filter_attributes(extra: Optional[Mapping[str, object]]) -> Dict[str, str]:
    if not extra:
        return {}
    return {
        k: str(v) for k, v in extra.items() if k in ALLOWED_MDC_KEYS and v is not None
    }


def _strip_controls(s: str) -> str:
    return "".join(c if c == "\t" or ord(c) >= 0x20 else " " for c in s)


def _truncate(s: str) -> str:
    encoded = s.encode("utf-8")
    if len(encoded) <= MAX_BODY_BYTES:
        return s
    budget = MAX_BODY_BYTES - len(_TRUNCATION_MARKER.encode("utf-8"))
    # decode with errors='ignore' so we never split a multibyte codepoint
    return encoded[:budget].decode("utf-8", errors="ignore") + _TRUNCATION_MARKER


EmitFn = Callable[[str, str, Dict[str, str]], None]


def make_sink(emit_fn: EmitFn):
    """Return a loguru sink that forwards records to ``emit_fn`` after sanitization.

    ``emit_fn`` is called with ``(severity_name, sanitized_body, attributes)``.
    The default ``install`` wires this to the OTel Logs API; tests pass a
    capturing function instead so no real SDK is required.
    """

    def sink(message):
        record = message.record
        body = sanitize_body(record.get("message"))
        attrs = filter_attributes(record.get("extra"))
        level = record.get("level")
        severity = getattr(level, "name", str(level))
        emit_fn(severity, body, attrs)

    return sink


def install():
    """Wire the loguru sink to the OTel Logs API.

    Safe to call even when the OTel SDK is the no-op instance (records are
    built and dropped). Importing the OTel logs API is deferred so this module
    is importable in environments without ``opentelemetry-sdk`` installed.
    """
    from opentelemetry import _logs as otel_logs
    from opentelemetry.sdk._logs import LogRecord  # noqa: F401 — type only

    otel_logger = otel_logs.get_logger("org.apache.texera.observability")

    def emit(severity_name: str, body: str, attrs: Dict[str, str]) -> None:
        otel_logger.emit(
            otel_logs.LogRecord(
                body=body,
                severity_text=severity_name,
                attributes=attrs,
            )
        )

    logger.add(make_sink(emit), level="DEBUG", format="{message}")
