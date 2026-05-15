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

package org.apache.texera.web.model.websocket.event

import org.apache.texera.amber.engine.common.executionruntimestate.BreakpointFault

/**
  * Emitted whenever new BreakpointFault entries are appended to the
  * controller-side breakpointStore. Pushed to the frontend over the
  * existing websocket; the Debugger panel subscribes to it.
  *
  * `operatorId` is the logical operator id (e.g. "E1-filter"), matching
  * the keys used elsewhere in the workflow UI. `newFaults` carries only
  * the faults added since the previous store state; the panel appends
  * them to its in-memory list rather than re-rendering from scratch.
  */
case class BreakpointFaultEvent(
    operatorId: String,
    newFaults: Seq[BreakpointFault]
) extends TexeraWebSocketEvent
