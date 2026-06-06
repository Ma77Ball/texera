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

import sys

import pyarrow as pa
import pytest

from core.models.payload import DataFrame
from core.util.customized_queue.size_estimation import estimate_in_mem_size


class _Payload:
    """Minimal payload wrapper exposing a `frame`, like DataFrame/StateFrame."""

    def __init__(self, frame):
        self.frame = frame


class _Element:
    """Minimal queue item exposing a `payload`, like DataElement."""

    def __init__(self, payload):
        self.payload = payload


class _NoiseyTable:
    """Frame whose buffer-level accessors raise, to exercise fall-through."""

    @property
    def nbytes(self):
        raise RuntimeError("nbytes unavailable")

    def get_total_buffer_size(self):
        raise RuntimeError("buffer size unavailable")

    def to_batches(self):
        raise RuntimeError("cannot convert")


class _OnlyTotalBuffer:
    """Frame that exposes only get_total_buffer_size() (tier b)."""

    def get_total_buffer_size(self):
        return 4096


class _OnlyBatches:
    """Frame that exposes only a record-batch representation (tier c)."""

    def __init__(self, batch_sizes):
        self._batch_sizes = batch_sizes

    def to_batches(self):
        return [_Batch(n) for n in self._batch_sizes]


class _Batch:
    def __init__(self, nbytes):
        self.nbytes = nbytes


@pytest.fixture
def arrow_table():
    # A non-trivial table so its buffer size clearly exceeds the wrapper size.
    return pa.table({"a": list(range(10_000)), "b": [str(i) for i in range(10_000)]})


class TestEstimateInMemSize:
    # ---- positive: real Arrow DataFrame uses true buffer size ----

    def test_arrow_dataframe_reports_buffer_size(self, arrow_table):
        element = _Element(DataFrame(arrow_table))
        assert estimate_in_mem_size(element) == arrow_table.nbytes

    def test_arrow_dataframe_far_exceeds_shallow_size(self, arrow_table):
        element = _Element(DataFrame(arrow_table))
        # The whole point of the change: deep size >> shallow wrapper size.
        assert estimate_in_mem_size(element) > sys.getsizeof(element) * 100

    # ---- each fallback tier hit in isolation ----

    def test_tier_a_direct_nbytes(self):
        frame = _Batch(1234)  # exposes only `nbytes`
        assert estimate_in_mem_size(_Element(_Payload(frame))) == 1234

    def test_tier_b_total_buffer_size(self):
        assert estimate_in_mem_size(_Element(_Payload(_OnlyTotalBuffer()))) == 4096

    def test_tier_c_table_representation(self):
        frame = _OnlyBatches([100, 200, 300])
        assert estimate_in_mem_size(_Element(_Payload(frame))) == 600

    def test_tier_d_shallow_fallback_on_payload(self):
        # Frame exposes nothing usable -> shallow size of the payload.
        payload = _Payload(object())
        assert estimate_in_mem_size(_Element(payload)) == sys.getsizeof(payload)

    def test_raising_accessors_fall_through_to_shallow(self):
        payload = _Payload(_NoiseyTable())
        # All buffer accessors raise; must not propagate, falls back to payload.
        assert estimate_in_mem_size(_Element(payload)) == sys.getsizeof(payload)

    # ---- negative / edge cases ----

    def test_empty_arrow_table(self):
        table = pa.table({"a": pa.array([], type=pa.int64())})
        element = _Element(DataFrame(table))
        assert estimate_in_mem_size(element) == table.nbytes

    def test_none_item_uses_shallow(self):
        assert estimate_in_mem_size(None) == sys.getsizeof(None)

    def test_control_element_without_frame(self):
        # payload present but no `frame` attribute -> shallow size of payload.
        payload = object()
        element = _Element(payload)
        assert estimate_in_mem_size(element) == sys.getsizeof(payload)

    def test_item_without_payload_uses_shallow(self):
        item = "a plain string item"
        assert estimate_in_mem_size(item) == sys.getsizeof(item)

    def test_tier_a_preferred_over_b_and_c(self):
        # A real Arrow table exposes nbytes, get_total_buffer_size and to_batches;
        # tier a (nbytes) must win.
        table = pa.table({"x": list(range(1000))})
        assert estimate_in_mem_size(_Element(DataFrame(table))) == table.nbytes
