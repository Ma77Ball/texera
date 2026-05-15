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

package org.apache.texera.web.service

import com.typesafe.scalalogging.LazyLogging
import org.apache.texera.amber.core.virtualidentity.ActorVirtualIdentity
import org.apache.texera.amber.engine.architecture.rpc.controlcommands.RetryWorkflowRequest
import org.apache.texera.amber.engine.common.client.AmberClient
import org.apache.texera.amber.engine.common.executionruntimestate.{
  BreakpointFault,
  ExecutionBreakpointStore,
  OperatorBreakpoints
}
import org.apache.texera.amber.util.VirtualIdentityUtils
import org.apache.texera.web.{SubscriptionManager, WebsocketInput}
import org.apache.texera.web.model.websocket.event.{BreakpointFaultEvent, TexeraWebSocketEvent}
import org.apache.texera.web.model.websocket.request.RetryRequest
import org.apache.texera.web.storage.ExecutionStateStore

/**
  * Pure store-update logic for BreakpointFault events. Extracted so it can
  * be exercised in tests without spinning up an AmberClient subscription.
  */
object BreakpointFaultProcessor {

  /** Map a worker actor name back to its logical operator id. */
  def operatorIdOf(workerName: String): String =
    VirtualIdentityUtils
      .getPhysicalOpId(ActorVirtualIdentity(workerName))
      .logicalOpId
      .id

  /**
    * Append the fault to the OperatorBreakpoints entry for its operator,
    * creating the entry on first fault. The store accumulates faults in
    * arrival order under `unresolvedBreakpoints`.
    */
  def appendFault(
      store: ExecutionBreakpointStore,
      fault: BreakpointFault
  ): ExecutionBreakpointStore = {
    val opId = operatorIdOf(fault.workerName)
    val existing = store.operatorInfo.getOrElse(opId, OperatorBreakpoints())
    val updated = existing.copy(unresolvedBreakpoints = existing.unresolvedBreakpoints :+ fault)
    store.copy(operatorInfo = store.operatorInfo.updated(opId, updated))
  }

  /**
    * Compute the per-operator delta between two breakpoint stores. Emits
    * one [[BreakpointFaultEvent]] per operator that gained faults, carrying
    * only the *new* faults. Operators with no change are omitted; operators
    * that lost faults (e.g. after resolution) are not currently surfaced as
    * a removal event — step 2's panel only cares about additions.
    */
  def diffFaults(
      oldStore: ExecutionBreakpointStore,
      newStore: ExecutionBreakpointStore
  ): Iterable[TexeraWebSocketEvent] = {
    newStore.operatorInfo.flatMap {
      case (opId, newInfo) =>
        val oldFaults =
          oldStore.operatorInfo.get(opId).map(_.unresolvedBreakpoints).getOrElse(Seq.empty)
        val newFaults = newInfo.unresolvedBreakpoints.drop(oldFaults.size)
        if (newFaults.isEmpty) None
        else Some(BreakpointFaultEvent(operatorId = opId, newFaults = newFaults))
    }
  }
}

/**
  * Listens for BreakpointFault client events emitted by the controller and
  * appends them to the per-operator entry in `ExecutionStateStore.breakpointStore`.
  *
  * The store was previously initialized but had no writers. With this service
  * wired up, the failing tuple captured at `DataProcessor.handleExecutorException`
  * reaches the controller's web-layer state — the frontend Debugger panel
  * (added in a later step) reads from the same store.
  */
class ExecutionBreakpointService(
    client: AmberClient,
    stateStore: ExecutionStateStore,
    wsInput: WebsocketInput
) extends SubscriptionManager
    with LazyLogging {

  addSubscription(
    client.registerCallback[BreakpointFault]((evt: BreakpointFault) => {
      stateStore.breakpointStore.updateState { store =>
        BreakpointFaultProcessor.appendFault(store, evt)
      }
    })
  )

  addSubscription(
    stateStore.breakpointStore.registerDiffHandler((oldState, newState) =>
      BreakpointFaultProcessor.diffFaults(oldState, newState)
    )
  )

  // Frontend Retry button → engine retryWorkflow. The existing empty
  // subscription in ExecutionConsoleService remains as a no-op for
  // compatibility; this one does the real work.
  addSubscription(wsInput.subscribe((req: RetryRequest, _) => {
    if (req.workers.nonEmpty) {
      val workers = req.workers.map(ActorVirtualIdentity(_))
      // The controller's RetryWorkflowHandler dispatches retry_current_tuple
      // to each worker and resumes the workflow.
      client.controllerInterface.retryWorkflow(
        RetryWorkflowRequest(workers = workers),
        ()
      )
      // Clear faults for operators whose workers we're retrying. If the
      // retry hits the same exception, the controller→bridge→panel chain
      // re-emits a fresh BreakpointFaultEvent.
      stateStore.breakpointStore.updateState { store =>
        val targetOpIds = workers
          .map(w => BreakpointFaultProcessor.operatorIdOf(w.name))
          .toSet
        store.copy(operatorInfo = store.operatorInfo.filterNot {
          case (opId, _) => targetOpIds.contains(opId)
        })
      }
    }
  }))
}
