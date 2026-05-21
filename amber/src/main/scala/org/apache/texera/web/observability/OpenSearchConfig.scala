/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.texera.web.observability

/**
  * Pulls the read-only logs-index credentials from env vars. Defaults are
  * the single-node Compose addresses so dev users see something useful
  * without extra setup; production deployments must override both the
  * endpoint and the credentials.
  *
  * `OBSERVABILITY_OPENSEARCH_ENDPOINT` — base URL, e.g. `https://opensearch:9200`.
  * `OBSERVABILITY_OPENSEARCH_USERNAME` / `_PASSWORD` — basic-auth pair.
  * `OBSERVABILITY_OPENSEARCH_INDEX_PATTERN` — defaults to `texera-logs-*`.
  *
  * The service account these credentials refer to must have read-only
  * permission on the configured index pattern and nothing else.
  */
object OpenSearchConfig {
  def fromEnv(env: String => Option[String] = k => Option(System.getenv(k))): OpenSearchLogsClient.Config = {
    OpenSearchLogsClient.Config(
      endpoint = env("OBSERVABILITY_OPENSEARCH_ENDPOINT").getOrElse("https://localhost:9200"),
      username = env("OBSERVABILITY_OPENSEARCH_USERNAME").getOrElse("admin"),
      password = env("OBSERVABILITY_OPENSEARCH_PASSWORD").getOrElse(""),
      indexPattern = env("OBSERVABILITY_OPENSEARCH_INDEX_PATTERN").getOrElse("texera-logs-*")
    )
  }
}
