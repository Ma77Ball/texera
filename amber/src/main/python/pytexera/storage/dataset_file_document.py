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

import io
import os
import requests
import time
import urllib.parse


# (connect, read) timeouts in seconds for HTTP calls. Without these, a stalled
# endpoint blocks the Python worker thread indefinitely.
_HTTP_TIMEOUT = (5, 30)
# Bounded retry for transient failures, with exponential backoff.
_MAX_RETRIES = 3
_BACKOFF_BASE_SECONDS = 0.5
_RETRYABLE_STATUS = frozenset({429, 500, 502, 503, 504})


def _get_with_retry(url: str, **kwargs) -> requests.Response:
    """
    Issue a GET with a bounded timeout, retrying transient failures (connection
    errors, timeouts, and retryable 5xx/429 responses) with exponential backoff.

    Returns the final Response so callers keep their own status handling. A
    retryable status that survives all attempts is returned as-is; only network
    errors that exhaust all attempts raise.
    """
    kwargs.setdefault("timeout", _HTTP_TIMEOUT)
    last_error = None
    last_response = None
    for attempt in range(_MAX_RETRIES + 1):
        try:
            response = requests.get(url, **kwargs)
            if response.status_code not in _RETRYABLE_STATUS:
                return response
            last_response = response
            last_error = f"status {response.status_code}"
        except (requests.ConnectionError, requests.Timeout) as exc:
            last_response = None
            last_error = exc
        if attempt < _MAX_RETRIES:
            time.sleep(_BACKOFF_BASE_SECONDS * (2**attempt))
    if last_response is not None:
        return last_response
    raise RuntimeError(
        f"Failed to reach {url} after {_MAX_RETRIES + 1} attempts: {last_error}"
    )


class DatasetFileDocument:
    def __init__(self, file_path: str):
        """
        Parses the file path into dataset metadata.

        :param file_path:
           Expected format - "/ownerEmail/datasetName/versionName/fileRelativePath"
           Example: "/bob@texera.com/twitterDataset/v1/california/irvine/tw1.csv"
        """
        parts = file_path.strip("/").split("/")
        if len(parts) < 4:
            raise ValueError(
                "Invalid file path format. "
                "Expected: /ownerEmail/datasetName/versionName/fileRelativePath"
            )

        self.owner_email = parts[0]
        self.dataset_name = parts[1]
        self.version_name = parts[2]
        self.file_relative_path = "/".join(parts[3:])

        self.jwt_token = os.getenv("USER_JWT_TOKEN")
        self.presign_endpoint = os.getenv("FILE_SERVICE_GET_PRESIGNED_URL_ENDPOINT")

        if not self.jwt_token:
            raise ValueError(
                "JWT token is required but not set in environment variables."
            )
        if not self.presign_endpoint:
            self.presign_endpoint = "http://localhost:9092/api/dataset/presign-download"

    def get_presigned_url(self) -> str:
        """
        Requests a presigned URL from the API.

        :return: The presigned URL as a string.
        :raises: RuntimeError if the request fails.
        """
        headers = {"Authorization": f"Bearer {self.jwt_token}"}
        encoded_file_path = urllib.parse.quote(
            f"/{self.owner_email}"
            f"/{self.dataset_name}"
            f"/{self.version_name}"
            f"/{self.file_relative_path}"
        )

        params = {"filePath": encoded_file_path}

        response = _get_with_retry(
            self.presign_endpoint, headers=headers, params=params
        )

        if response.status_code != 200:
            raise RuntimeError(
                f"Failed to get presigned URL: {response.status_code} {response.text}"
            )

        try:
            payload = response.json()
        except ValueError as e:
            raise RuntimeError(
                f"Failed to get presigned URL: invalid JSON response: {response.text}"
            ) from e

        presigned_url = payload.get("presignedUrl")
        if not isinstance(presigned_url, str) or not presigned_url:
            raise RuntimeError(
                f"Failed to get presigned URL: 'presignedUrl' missing from "
                f"response: {response.text}"
            )

        return presigned_url

    def read_file(self) -> io.BytesIO:
        """
        Reads the file content from the presigned URL.

        :return: A file-like object.
        :raises: RuntimeError if the retrieval fails.
        """
        presigned_url = self.get_presigned_url()
        response = _get_with_retry(presigned_url)

        if response.status_code != 200:
            raise RuntimeError(
                f"Failed to retrieve file content: "
                f"{response.status_code} {response.text}"
            )

        return io.BytesIO(response.content)
