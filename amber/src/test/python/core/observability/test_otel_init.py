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

from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from core.observability import otel_init


class TestOtelInit:
    def setup_method(self):
        self.exporter = InMemorySpanExporter()
        otel_init.reset_for_testing()

    def test_disabled_when_env_is_true(self):
        provider = otel_init.init_for_testing(
            env={"OTEL_SDK_DISABLED": "true"},
            service_name="pyamber",
            service_version="0.0.0",
            test_exporter=self.exporter,
        )
        assert provider is None
        assert self.exporter.get_finished_spans() == ()

    def test_disabled_by_default(self):
        provider = otel_init.init_for_testing(
            env={},
            service_name="pyamber",
            service_version="0.0.0",
            test_exporter=self.exporter,
        )
        assert provider is None
        assert self.exporter.get_finished_spans() == ()

    def test_enabled_emits_service_start(self):
        provider = otel_init.init_for_testing(
            env={"OTEL_SDK_DISABLED": "false"},
            service_name="pyamber",
            service_version="1.2.3",
            test_exporter=self.exporter,
        )
        assert provider is not None
        spans = self.exporter.get_finished_spans()
        assert len(spans) == 1
        assert spans[0].name == "service.start"
        attrs = spans[0].resource.attributes
        assert attrs["service.name"] == "pyamber"
        assert attrs["service.version"] == "1.2.3"

    def test_rejects_non_allowlisted_host(self):
        provider = otel_init.init_for_testing(
            env={
                "OTEL_SDK_DISABLED": "false",
                "OTEL_EXPORTER_OTLP_ENDPOINT": "http://attacker.example.com",
            },
            service_name="pyamber",
            service_version="0.0.0",
            test_exporter=self.exporter,
        )
        assert provider is None
        assert self.exporter.get_finished_spans() == ()

    def test_rejects_disallowed_scheme(self):
        provider = otel_init.init_for_testing(
            env={
                "OTEL_SDK_DISABLED": "false",
                "OTEL_EXPORTER_OTLP_ENDPOINT": "file:///etc/passwd",
            },
            service_name="pyamber",
            service_version="0.0.0",
            test_exporter=self.exporter,
        )
        assert provider is None

    def test_accepts_localhost(self):
        provider = otel_init.init_for_testing(
            env={
                "OTEL_SDK_DISABLED": "false",
                "OTEL_EXPORTER_OTLP_ENDPOINT": "http://localhost:4318",
            },
            service_name="pyamber",
            service_version="0.0.0",
            test_exporter=self.exporter,
        )
        assert provider is not None

    def test_resource_attribute_allowlist(self):
        otel_init.init_for_testing(
            env={
                "OTEL_SDK_DISABLED": "false",
                "OTEL_RESOURCE_ATTRIBUTES": (
                    "secret=v,deployment.environment=staging,bad.key=x"
                ),
            },
            service_name="pyamber",
            service_version="0.0.0",
            test_exporter=self.exporter,
        )
        spans = self.exporter.get_finished_spans()
        attrs = spans[0].resource.attributes
        assert attrs.get("deployment.environment") == "staging"
        assert "secret" not in attrs
        assert "bad.key" not in attrs
