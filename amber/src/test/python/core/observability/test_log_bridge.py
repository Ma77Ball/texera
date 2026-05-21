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

from core.observability import log_bridge


class TestLogSanitizer:
    def test_strips_crlf_keeps_tab(self):
        out = log_bridge.sanitize_body("hello\r\nFAKE\tworld\x01")
        assert "\r" not in out
        assert "\n" not in out
        assert "\x01" not in out
        assert "\t" in out
        assert "hello" in out

    def test_redacts_bearer_token(self):
        out = log_bridge.sanitize_body("Authorization: Bearer abc.def.ghi")
        assert "[REDACTED]" in out
        assert "abc.def.ghi" not in out

    def test_redacts_password_pattern(self):
        out = log_bridge.sanitize_body("connect password=hunter2 done")
        assert "[REDACTED]" in out
        assert "hunter2" not in out

    def test_redacts_aws_access_key(self):
        out = log_bridge.sanitize_body("aws=AKIAIOSFODNN7EXAMPLE here")
        assert "[REDACTED]" in out
        assert "AKIAIOSFODNN7EXAMPLE" not in out

    def test_truncates_oversize(self):
        out = log_bridge.sanitize_body("x" * (32 * 1024))
        assert len(out.encode("utf-8")) <= 16 * 1024
        assert out.endswith("...[TRUNCATED]")

    def test_passthrough_short_clean(self):
        assert log_bridge.sanitize_body("ordinary") == "ordinary"

    def test_handles_none(self):
        assert log_bridge.sanitize_body(None) == ""


class TestLoguruSink:
    def test_sink_emits_otel_record(self):
        emitted = []

        def fake_emit(severity, body, attributes):
            emitted.append((severity, body, attributes))

        sink = log_bridge.make_sink(emit_fn=fake_emit)
        # A loguru "message" object is structured; pass a dict that mimics
        # the fields the sink reads.
        sink(
            _loguru_message(
                level_name="INFO",
                message="hello world",
                extra={"trace_id": "abc", "workflow.id": "wf-1", "user.secret": "x"},
            )
        )
        assert len(emitted) == 1
        sev, body, attrs = emitted[0]
        assert sev == "INFO"
        assert body == "hello world"
        assert attrs.get("trace_id") == "abc"
        assert attrs.get("workflow.id") == "wf-1"
        assert "user.secret" not in attrs

    def test_sink_sanitizes_body(self):
        emitted = []
        sink = log_bridge.make_sink(emit_fn=lambda s, b, a: emitted.append((s, b, a)))
        sink(_loguru_message("INFO", "Authorization: Bearer x\r\nFAKE", {}))
        body = emitted[0][1]
        assert "[REDACTED]" in body
        assert "\r" not in body
        assert "\n" not in body


def _loguru_message(level_name, message, extra):
    """Build a minimal stand-in for loguru's Message record."""

    class _Level:
        name = level_name

    class _Record(dict):
        pass

    record = _Record(level=_Level(), message=message, extra=extra)

    class _Message:
        @property
        def record(self):
            return record

    return _Message()
