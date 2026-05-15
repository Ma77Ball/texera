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

package org.apache.texera.amber.engine.architecture.controller.promisehandlers

import com.twitter.util.Future
import org.apache.texera.amber.engine.architecture.controller.ControllerAsyncRPCHandlerInitializer
import org.apache.texera.amber.engine.architecture.rpc.controlcommands.{
  AsyncRPCContext,
  BreakpointFaultTriggeredRequest
}
import org.apache.texera.amber.engine.architecture.rpc.controlreturns.EmptyReturn
import org.apache.texera.amber.engine.common.executionruntimestate.BreakpointFault

trait BreakpointFaultHandler {
  this: ControllerAsyncRPCHandlerInitializer =>

  override def breakpointFaultTriggered(
      msg: BreakpointFaultTriggeredRequest,
      ctx: AsyncRPCContext
  ): Future[EmptyReturn] = {
    // Rebuild the domain BreakpointFault from the wire request so the web
    // layer's BreakpointFault callback can pick it up exactly like the
    // ConsoleMessage relay does. The two protos carry the same fields by
    // design; the duplication exists only to avoid a circular proto import.
    val fault = BreakpointFault(
      workerName = msg.workerName,
      faultedTuple = Some(
        BreakpointFault.BreakpointTuple(
          id = msg.tupleId,
          isInput = msg.isInput,
          tuple = msg.tuple
        )
      )
    )
    sendToClient(fault)
    EmptyReturn()
  }
}
