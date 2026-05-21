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

/** Single log row as exposed to the frontend. Field names are stable JSON keys. */
final case class LogEntry(
    timestamp: String,
    level: String,
    body: String,
    traceId: Option[String],
    spanId: Option[String]
)

/**
  * Page of log entries. `nextCursor` opaquely encodes the OpenSearch
  * `search_after` payload; clients echo it back without inspecting it.
  */
final case class LogsPage(
    entries: Seq[LogEntry],
    total: Long,
    nextCursor: Option[String]
)
