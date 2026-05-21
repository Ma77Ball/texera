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

package org.apache.texera.amber.engine.architecture.scheduling

import com.twitter.util.Future
import com.typesafe.scalalogging.LazyLogging
import org.apache.texera.amber.core.workflow.{GlobalPortIdentity, PhysicalLink}
import org.apache.texera.amber.engine.architecture.common.{
  PekkoActorRefMappingService,
  PekkoActorService
}
import org.apache.texera.amber.engine.architecture.controller.ControllerConfig
import org.apache.texera.amber.engine.architecture.controller.ExecutionStateUpdate
import org.apache.texera.amber.engine.architecture.controller.execution.WorkflowExecution
import org.apache.texera.amber.engine.common.rpc.AsyncRPCClient
import org.apache.texera.observability.{OtelInit, WorkflowMetrics, WorkflowTracing}
import io.opentelemetry.context.Context

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.collection.mutable

class WorkflowExecutionCoordinator(
    workflowExecution: WorkflowExecution,
    controllerConfig: ControllerConfig,
    asyncRPCClient: AsyncRPCClient
) extends LazyLogging {

  var schedule: Schedule = Schedule(Map.empty)

  private val executedRegions: mutable.ListBuffer[Set[Region]] = mutable.ListBuffer()

  private val regionExecutionCoordinators
      : mutable.HashMap[RegionIdentity, RegionExecutionCoordinator] =
    mutable.HashMap()
  private val completionNotified: AtomicBoolean = new AtomicBoolean(false)
  private val startNotified: AtomicBoolean = new AtomicBoolean(false)
  private val startNanos: AtomicLong = new AtomicLong(0L)
  private lazy val metrics: WorkflowMetrics = new WorkflowMetrics(OtelInit.openTelemetry)
  // Coarse, schedule-shaped kind for now. Per-workflow detail belongs in
  // traces / logs — never in metric labels.
  private val WorkflowKind = "batch"

  @transient var actorRefService: PekkoActorRefMappingService = _

  def setupActorRefService(actorRefService: PekkoActorRefMappingService): Unit = {
    this.actorRefService = actorRefService
  }

  /**
    * Each invocation first syncs the internal statuses of each exisiting `RegionExecutionCoordintor`, after which each
    * of the `RegionExecutionCoordintor`s will launch the corresponding next phase of whenever needed until it is
    * in `Completed` status (phase).
    *
    * After the syncs, if there are no running region(s), it will start new regions (if available).
    */
  def coordinateRegionExecutors(actorService: PekkoActorService): Future[Unit] = {
    val unfinishedRegionCoordinators =
      regionExecutionCoordinators.values.filter(!_.isCompleted).toSeq

    // Trigger sync for each unfinished region.
    unfinishedRegionCoordinators.foreach(_.syncStatusAndTransitionRegionExecutionPhase())

    // Wait only for region termination futures (kill path), then re-run coordination.
    val terminationFutures = unfinishedRegionCoordinators.flatMap(_.getTerminationFutureOpt)
    if (terminationFutures.nonEmpty) {
      return Future
        .collect(terminationFutures)
        .unit
        .flatMap(_ => coordinateRegionExecutors(actorService))
    }

    if (regionExecutionCoordinators.values.exists(!_.isCompleted)) {
      // Some regions are still not completed yet. Cannot start the new regions.
      return Future.Unit
    }

    // All existing regions are completed. Start the next region (if any).
    val nextRegions = if (!schedule.hasNext) Set.empty[Region] else schedule.next()
    if (nextRegions.isEmpty) {
      if (workflowExecution.isCompleted && completionNotified.compareAndSet(false, true)) {
        // Workflow has truly completed exactly once — emit terminal metrics
        // before the client notification so failure paths still see them.
        emitCompletionMetrics()
        asyncRPCClient.sendToClient(ExecutionStateUpdate(workflowExecution.getState))
      }
      return Future.Unit
    }

    if (startNotified.compareAndSet(false, true)) {
      startNanos.set(System.nanoTime())
      metrics.recordStart(WorkflowKind)
      metrics.recordActive(1)
    }
    executedRegions.append(nextRegions)
    Future
      .collect(
        nextRegions
          .map(region => {
            // Discrete "region.dispatch" span — covers init + coordinator
            // construction (synchronous). Async region work continues in the
            // returned RegionExecutionCoordinator and is not parented here
            // because Twitter Futures do not propagate OTel Context.
            WorkflowTracing.withOperatorSpan(
              OtelInit.openTelemetry,
              operatorId = region.id.id.toString,
              parentContext = Context.current()
            ) { _ =>
              val isRestart = workflowExecution.hasRegionExecution(region.id)
              if (isRestart) {
                workflowExecution.restartRegionExecution(region)
              } else {
                workflowExecution.initRegionExecution(region)
              }
              regionExecutionCoordinators(region.id) = new RegionExecutionCoordinator(
                region,
                isRestart,
                workflowExecution,
                asyncRPCClient,
                controllerConfig,
                actorService,
                actorRefService
              )
              regionExecutionCoordinators(region.id)
            }
          })
          .map(_.syncStatusAndTransitionRegionExecutionPhase())
          .toSeq
      )
      .unit
  }

  private def emitCompletionMetrics(): Unit = {
    import org.apache.texera.amber.engine.architecture.rpc.controlreturns.WorkflowAggregatedState._
    val state = workflowExecution.getState
    val outcome = state match {
      case COMPLETED              => "ok"
      case FAILED                 => "failed"
      case KILLED | TERMINATED    => "cancelled"
      case _                      => "cancelled"
    }
    val durationMs =
      if (startNanos.get() == 0L) 0L
      else (System.nanoTime() - startNanos.get()) / 1_000_000L
    metrics.recordCompletion(durationMs, outcome, WorkflowKind)
    if (state == FAILED) metrics.recordFailure(WorkflowKind)
    metrics.recordActive(-1)
  }

  def getRegionOfLink(link: PhysicalLink): Region = {
    getExecutingRegions.find(region => region.getLinks.contains(link)).get
  }

  def getRegionOfPortId(portId: GlobalPortIdentity): Option[Region] = {
    getExecutingRegions.find(region => region.getPorts.contains(portId))
  }

  def getExecutingRegions: Set[Region] = {
    executedRegions.flatten
      .filterNot(region => workflowExecution.getRegionExecution(region.id).isCompleted)
      .toSet
  }

  def hasUnfinishedRegionCoordinators: Boolean = {
    regionExecutionCoordinators.values.exists(!_.isCompleted)
  }

}
