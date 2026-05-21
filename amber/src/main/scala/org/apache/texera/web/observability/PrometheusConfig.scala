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
  * Reads the read-only Prometheus query endpoint from `OBSERVABILITY_PROMETHEUS_ENDPOINT`.
  * Default targets the compose-stack service from PR 6.
  *
  * The service account behind this endpoint must only have read permission
  * — Prometheus pods in the texera Helm chart are launched without
  * `--web.enable-admin-api`, but operators bringing their own Prometheus
  * are responsible for the same restriction.
  */
object PrometheusConfig {
  def fromEnv(env: String => Option[String] = k => Option(System.getenv(k))): PrometheusClient.Config = {
    PrometheusClient.Config(
      endpoint = env("OBSERVABILITY_PROMETHEUS_ENDPOINT").getOrElse("http://localhost:9090")
    )
  }
}
