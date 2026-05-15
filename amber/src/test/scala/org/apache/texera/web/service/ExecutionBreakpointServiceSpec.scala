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

import org.apache.texera.amber.engine.common.executionruntimestate.{
  BreakpointFault,
  ExecutionBreakpointStore,
  OperatorBreakpoints
}
import org.apache.texera.web.model.websocket.event.BreakpointFaultEvent
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ExecutionBreakpointServiceSpec extends AnyFlatSpec with Matchers {

  // Helper: build a BreakpointFault payload identical in shape to what the
  // controller-side BreakpointFaultHandler relays via sendToClient.
  private def mkFault(workerName: String, fields: Seq[String]): BreakpointFault =
    BreakpointFault(
      workerName = workerName,
      faultedTuple = Some(
        BreakpointFault.BreakpointTuple(
          id = 0L,
          isInput = true,
          tuple = fields
        )
      )
    )

  // The workflow→operator→worker actor name pattern that
  // VirtualIdentityUtils.getPhysicalOpId is shaped to parse.
  private val workerA = "Worker:WF1-E1-filter-main-0"
  private val workerB = "Worker:WF1-E1-filter-main-1"
  private val workerOtherOp = "Worker:WF1-E1-mapper-main-0"

  "operatorIdOf" should "extract the logical operator id from a well-formed worker name" in {
    // Pin: this is the same parsing used downstream when the frontend looks
    // up faults by logical operator id. If this changes shape, the frontend
    // panel breaks silently — keep the assertion exact.
    BreakpointFaultProcessor.operatorIdOf(workerA) shouldBe "E1-filter"
  }

  "appendFault" should "create a new OperatorBreakpoints entry when no fault has been recorded for the operator" in {
    val empty = ExecutionBreakpointStore()
    val fault = mkFault(workerA, Seq("ada", "42"))
    val updated = BreakpointFaultProcessor.appendFault(empty, fault)
    updated.operatorInfo.keys should contain("E1-filter")
    updated.operatorInfo("E1-filter").unresolvedBreakpoints shouldBe Seq(fault)
  }

  it should "append a second fault from the same worker in arrival order" in {
    val initial = ExecutionBreakpointStore()
    val first = mkFault(workerA, Seq("ada", "42"))
    val second = mkFault(workerA, Seq("bob", "7"))
    val afterFirst = BreakpointFaultProcessor.appendFault(initial, first)
    val afterSecond = BreakpointFaultProcessor.appendFault(afterFirst, second)
    afterSecond.operatorInfo("E1-filter").unresolvedBreakpoints shouldBe Seq(first, second)
  }

  it should "group faults from different workers under the same operator id" in {
    // Two workers of the same operator (parallelism > 1) both fault.
    // The store keeps both under one operator entry, in arrival order.
    val store0 = ExecutionBreakpointStore()
    val fault1 = mkFault(workerA, Seq("x"))
    val fault2 = mkFault(workerB, Seq("y"))
    val store1 = BreakpointFaultProcessor.appendFault(store0, fault1)
    val store2 = BreakpointFaultProcessor.appendFault(store1, fault2)
    store2.operatorInfo.keys should contain only "E1-filter"
    store2.operatorInfo("E1-filter").unresolvedBreakpoints shouldBe Seq(fault1, fault2)
  }

  it should "keep faults from different operators under separate entries" in {
    val store0 = ExecutionBreakpointStore()
    val filterFault = mkFault(workerA, Seq("x"))
    val mapperFault = mkFault(workerOtherOp, Seq("y"))
    val store1 = BreakpointFaultProcessor.appendFault(store0, filterFault)
    val store2 = BreakpointFaultProcessor.appendFault(store1, mapperFault)
    store2.operatorInfo.keys should contain allOf ("E1-filter", "E1-mapper")
    store2.operatorInfo("E1-filter").unresolvedBreakpoints shouldBe Seq(filterFault)
    store2.operatorInfo("E1-mapper").unresolvedBreakpoints shouldBe Seq(mapperFault)
  }

  it should "leave unrelated operator entries untouched when appending a new fault" in {
    val existingMapper = OperatorBreakpoints(
      unresolvedBreakpoints = Seq(mkFault(workerOtherOp, Seq("old")))
    )
    val seeded = ExecutionBreakpointStore(operatorInfo = Map("E1-mapper" -> existingMapper))
    val newFilterFault = mkFault(workerA, Seq("new"))
    val after = BreakpointFaultProcessor.appendFault(seeded, newFilterFault)
    after.operatorInfo("E1-mapper") shouldBe existingMapper
    after.operatorInfo("E1-filter").unresolvedBreakpoints shouldBe Seq(newFilterFault)
  }

  // ----- diffFaults (websocket bridge) -----

  "diffFaults" should "emit nothing when neither store changed" in {
    val emptyStore = ExecutionBreakpointStore()
    BreakpointFaultProcessor.diffFaults(emptyStore, emptyStore).toSeq shouldBe empty
  }

  it should "emit a single event carrying only the new fault on first addition" in {
    val before = ExecutionBreakpointStore()
    val fault = mkFault(workerA, Seq("ada", "42"))
    val after = BreakpointFaultProcessor.appendFault(before, fault)
    val events = BreakpointFaultProcessor.diffFaults(before, after).toSeq
    events should have size 1
    events.head shouldBe BreakpointFaultEvent("E1-filter", Seq(fault))
  }

  it should "emit only the delta when a second fault arrives on the same operator" in {
    // The panel appends new faults to its in-memory list; the bridge must
    // therefore send *only* the new ones, never the full list each time.
    val first = mkFault(workerA, Seq("ada"))
    val second = mkFault(workerA, Seq("bob"))
    val afterFirst = BreakpointFaultProcessor.appendFault(ExecutionBreakpointStore(), first)
    val afterSecond = BreakpointFaultProcessor.appendFault(afterFirst, second)
    val events = BreakpointFaultProcessor.diffFaults(afterFirst, afterSecond).toSeq
    events should have size 1
    events.head shouldBe BreakpointFaultEvent("E1-filter", Seq(second))
  }

  it should "emit one event per operator when multiple operators gain faults at the same tick" in {
    val before = ExecutionBreakpointStore()
    val filterFault = mkFault(workerA, Seq("x"))
    val mapperFault = mkFault(workerOtherOp, Seq("y"))
    val afterFilter = BreakpointFaultProcessor.appendFault(before, filterFault)
    val afterBoth = BreakpointFaultProcessor.appendFault(afterFilter, mapperFault)
    val events = BreakpointFaultProcessor
      .diffFaults(before, afterBoth)
      .toSeq
      .collect { case e: BreakpointFaultEvent => e }
      .sortBy(_.operatorId)
    events should have size 2
    events.head shouldBe BreakpointFaultEvent("E1-filter", Seq(filterFault))
    events(1) shouldBe BreakpointFaultEvent("E1-mapper", Seq(mapperFault))
  }

  it should "ignore operators whose fault list shrank (resolution path is out of scope here)" in {
    // Defensive: if a future code path removes faults (e.g. step 2's
    // resolution flow), the bridge should not panic — it just emits no
    // event for that operator and lets the explicit resolution event
    // handle UI cleanup.
    val fault = mkFault(workerA, Seq("ada"))
    val withFault = BreakpointFaultProcessor.appendFault(ExecutionBreakpointStore(), fault)
    val emptyStore = ExecutionBreakpointStore()
    BreakpointFaultProcessor.diffFaults(withFault, emptyStore).toSeq shouldBe empty
  }

  // Bridge an open assertion-style requirement: the BreakpointFault payload
  // inside the emitted event must round-trip identically — the panel
  // reconstructs the failing tuple from it byte-for-byte.

  it should "pass through fault payloads byte-for-byte without mutation" in {
    val fault = mkFault(workerA, Seq("with comma, embedded", "null", ""))
    val after = BreakpointFaultProcessor.appendFault(ExecutionBreakpointStore(), fault)
    val event = BreakpointFaultProcessor.diffFaults(ExecutionBreakpointStore(), after).head
        .asInstanceOf[BreakpointFaultEvent]
    event.newFaults.head shouldBe fault
    event.newFaults.head.faultedTuple.get.tuple shouldBe Seq(
      "with comma, embedded",
      "null",
      ""
    )
  }
}
