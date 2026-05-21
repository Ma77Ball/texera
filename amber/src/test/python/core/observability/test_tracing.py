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

from core.observability import tracing


class TestValidateTraceparent:
    def test_accept_valid(self):
        tp = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        assert tracing.validate_traceparent(tp) == tp

    def test_reject_path_traversal(self):
        assert tracing.validate_traceparent("../../etc/passwd") is None

    def test_reject_wrong_version(self):
        assert (
            tracing.validate_traceparent(
                "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
            )
            is None
        )

    def test_reject_zero_trace_id(self):
        assert (
            tracing.validate_traceparent(
                "00-00000000000000000000000000000000-b7ad6b7169203331-01"
            )
            is None
        )

    def test_reject_zero_span_id(self):
        assert (
            tracing.validate_traceparent(
                "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01"
            )
            is None
        )

    def test_reject_none_and_empty(self):
        assert tracing.validate_traceparent(None) is None
        assert tracing.validate_traceparent("") is None


class TestValidateTracestate:
    def test_accept_ascii(self):
        assert tracing.validate_tracestate("v1=a,v2=b") == "v1=a,v2=b"

    def test_reject_non_ascii(self):
        assert tracing.validate_tracestate("v=héllo") is None

    def test_reject_oversize(self):
        assert tracing.validate_tracestate("k=" + "a" * 1024) is None


class TestValidateIds:
    def test_workflow_id_accepts_numeric_and_uuid(self):
        assert tracing.validate_workflow_id("12345") == "12345"
        assert (
            tracing.validate_workflow_id("550e8400-e29b-41d4-a716-446655440000")
            == "550e8400-e29b-41d4-a716-446655440000"
        )

    def test_workflow_id_rejects_injection_shapes(self):
        assert tracing.validate_workflow_id("../etc") is None
        assert tracing.validate_workflow_id("a/b") is None
        assert tracing.validate_workflow_id("a\r\nb") is None
        assert tracing.validate_workflow_id("x" * 65) is None
        assert tracing.validate_workflow_id(None) is None

    def test_operator_id_same_rules(self):
        assert tracing.validate_operator_id("CSV_source-1.v2") == "CSV_source-1.v2"
        assert tracing.validate_operator_id("op name") is None
        assert tracing.validate_operator_id("op\r\nx") is None


class TestExtractTraceparent:
    def test_extracts_from_headers_dict(self):
        h = {"traceparent": "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"}
        out = tracing.extract_traceparent(h)
        assert out == "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"

    def test_case_insensitive_header_key(self):
        h = {"TraceParent": "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"}
        out = tracing.extract_traceparent(h)
        assert out is not None

    def test_returns_none_when_invalid(self):
        h = {"traceparent": "not-valid"}
        assert tracing.extract_traceparent(h) is None

    def test_returns_none_when_missing(self):
        assert tracing.extract_traceparent({}) is None
        assert tracing.extract_traceparent(None) is None
