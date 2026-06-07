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

"""In-memory size estimation for queue items.

Sizes a data payload by its frame's real (Arrow) buffers, falling back to a
shallow ``sys.getsizeof`` when no buffer-level size is available.
"""

from __future__ import annotations

import sys
from typing import Any, Optional


def estimate_in_mem_size(item: Any) -> int:
    """In-memory size of a queue item, in bytes.

    Prefer the data buffers of ``item.payload.frame``; fall back to a shallow
    ``getsizeof`` of the payload, then the item.
    """
    payload = getattr(item, "payload", None)
    if payload is not None:
        frame = getattr(payload, "frame", None)
        if frame is not None:
            frame_size = _estimate_frame_size(frame)
            if frame_size is not None:
                return frame_size
        return sys.getsizeof(payload)
    return sys.getsizeof(item)


def _estimate_frame_size(frame: Any) -> Optional[int]:
    """Buffer-level size of an Arrow frame, or None if it can't be determined.

    Tries, in order: nbytes, get_total_buffer_size(), summed batch nbytes.
    Each step is guarded so a missing or raising accessor falls through.
    """
    # nbytes (pyarrow.Table / RecordBatch)
    try:
        nbytes = getattr(frame, "nbytes", None)
        if isinstance(nbytes, int):
            return nbytes
    except Exception:
        pass

    # total allocated buffer size
    get_total_buffer_size = getattr(frame, "get_total_buffer_size", None)
    if callable(get_total_buffer_size):
        try:
            total = get_total_buffer_size()
            if isinstance(total, int):
                return total
        except Exception:
            pass

    # convert to record batches and sum their byte counts
    to_batches = getattr(frame, "to_batches", None)
    if callable(to_batches):
        try:
            total = 0
            found = False
            for batch in to_batches():
                batch_nbytes = getattr(batch, "nbytes", None)
                if isinstance(batch_nbytes, int):
                    total += batch_nbytes
                    found = True
            if found:
                return total
        except Exception:
            pass

    return None
