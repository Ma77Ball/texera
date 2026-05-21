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

"""Trace-handling helpers mirroring WorkflowTracing.scala.

The Python worker is downstream of the Scala controller — the only inbound
trace context is a W3C ``traceparent`` header carried over the Arrow Flight
call. This module validates that header and exposes ID validators used when
attributing spans on the Python side.
"""

import re
from typing import Mapping, Optional

_TRACEPARENT_RE = re.compile(r"^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")
_ZERO_TRACE = "0" * 32
_ZERO_SPAN = "0" * 16
_ID_CHARS_RE = re.compile(r"^[A-Za-z0-9_.\-]{1,64}$")
_MAX_TRACESTATE = 512


def validate_traceparent(raw: Optional[str]) -> Optional[str]:
    if not raw:
        return None
    s = raw.strip()
    if not _TRACEPARENT_RE.match(s):
        return None
    parts = s.split("-")
    if parts[1] == _ZERO_TRACE or parts[2] == _ZERO_SPAN:
        return None
    return s


def validate_tracestate(raw: Optional[str]) -> Optional[str]:
    if raw is None:
        return None
    if len(raw) > _MAX_TRACESTATE:
        return None
    if any(ord(c) < 0x20 or ord(c) > 0x7E for c in raw):
        return None
    return raw


def validate_workflow_id(raw: Optional[str]) -> Optional[str]:
    if raw is None:
        return None
    return raw if _ID_CHARS_RE.match(raw) else None


def validate_operator_id(raw: Optional[str]) -> Optional[str]:
    return validate_workflow_id(raw)


def extract_traceparent(headers: Optional[Mapping[str, str]]) -> Optional[str]:
    """Pull a validated traceparent from a request-headers mapping.

    Lookup is case-insensitive. Returns ``None`` for missing or malformed
    headers; downstream code should then start a fresh trace rather than
    forge one from attacker-supplied input.
    """
    if not headers:
        return None
    for k, v in headers.items():
        if k and k.lower() == "traceparent":
            return validate_traceparent(v)
    return None
