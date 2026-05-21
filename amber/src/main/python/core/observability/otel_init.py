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

"""Bootstraps OpenTelemetry for the Python worker.

Default-off: unless ``OTEL_SDK_DISABLED`` is "false", returns ``None`` and emits
nothing. Security boundaries enforced here (do not relax without review):

  - OTLP endpoint host must be in ``ALLOWED_ENDPOINT_HOSTS``; otherwise the SDK
    stays disabled with one WARN. Prevents an attacker who can set environment
    variables from exfiltrating telemetry to an arbitrary host.
  - Only ``ALLOWED_RESOURCE_ATTRIBUTE_KEYS`` are accepted from
    ``OTEL_RESOURCE_ATTRIBUTES``; other keys are dropped.
"""

import os
from typing import Mapping, Optional
from urllib.parse import urlparse

from loguru import logger
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor, SpanExporter

ALLOWED_ENDPOINT_HOSTS = frozenset(
    {
        "localhost",
        "127.0.0.1",
        "::1",
        "otel-collector",
        "otel-collector.default.svc.cluster.local",
    }
)
ALLOWED_ENDPOINT_SCHEMES = frozenset({"http", "https", "grpc"})
ALLOWED_RESOURCE_ATTRIBUTE_KEYS = frozenset(
    {"service.name", "service.version", "deployment.environment"}
)

_initialized = False
_current: Optional[TracerProvider] = None


def init(service_name: str, service_version: str = "unknown") -> Optional[TracerProvider]:
    """Initialize OTel from the process environment. Idempotent."""
    global _initialized, _current
    if _initialized:
        return _current
    _initialized = True
    _current = _build(os.environ, service_name, service_version, test_exporter=None)
    return _current


def init_for_testing(
    env: Mapping[str, str],
    service_name: str,
    service_version: str,
    test_exporter: SpanExporter,
) -> Optional[TracerProvider]:
    global _initialized, _current
    _initialized = True
    _current = _build(env, service_name, service_version, test_exporter)
    return _current


def reset_for_testing() -> None:
    global _initialized, _current
    _initialized = False
    _current = None


def _build(
    env: Mapping[str, str],
    service_name: str,
    service_version: str,
    test_exporter: Optional[SpanExporter],
) -> Optional[TracerProvider]:
    disabled = env.get("OTEL_SDK_DISABLED", "true").lower() != "false"
    if disabled:
        return None

    endpoint = env.get("OTEL_EXPORTER_OTLP_ENDPOINT")
    if endpoint and not _endpoint_allowed(endpoint):
        logger.warning(
            "OTel SDK staying disabled: OTLP endpoint {!r} is not in the allowlist.",
            endpoint,
        )
        return None

    resource = _build_resource(env, service_name, service_version)
    provider = TracerProvider(resource=resource)
    if test_exporter is not None:
        provider.add_span_processor(SimpleSpanProcessor(test_exporter))

    tracer = provider.get_tracer("org.apache.texera.observability")
    with tracer.start_as_current_span("service.start"):
        # body intentionally empty — no env values, no hostnames beyond service.name
        pass
    return provider


def _endpoint_allowed(endpoint: str) -> bool:
    try:
        parsed = urlparse(endpoint)
    except ValueError:
        return False
    scheme = (parsed.scheme or "").lower()
    host = (parsed.hostname or "").lower()
    return scheme in ALLOWED_ENDPOINT_SCHEMES and host in ALLOWED_ENDPOINT_HOSTS


def _build_resource(
    env: Mapping[str, str], service_name: str, service_version: str
) -> Resource:
    attrs = {"service.name": service_name, "service.version": service_version}
    raw = env.get("OTEL_RESOURCE_ATTRIBUTES")
    if raw:
        for pair in raw.split(","):
            if "=" not in pair:
                continue
            k, v = pair.split("=", 1)
            k = k.strip()
            v = v.strip()
            if k in ALLOWED_RESOURCE_ATTRIBUTE_KEYS:
                attrs[k] = v
    return Resource.create(attrs)
