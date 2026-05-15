# Live Debugger — Code Changes Log

This is the **plain-English** companion to `LIVE_DEBUGGER_DESIGN.md`.

The design doc explains *why* and *what* in engineering terms. This doc
explains *which files actually change* and *what those changes do in plain
language*, with no architecture jargon. If you're not a Texera contributor
and want to understand what we're doing, read this one.

**Status legend:**

- 🟢 **Done** — code is merged
- 🟡 **In progress** — being worked on now
- ⚪ **Planned** — agreed to, not started
- 🔵 **Verifying** — reading code to confirm assumptions

**Last updated:** 2026-05-14
**Current status:** 🟡 **Steps 1, 1.5, and 2 implemented and tested** in the main checkout on branch `feat/liveDebugger`. NOT YET committed. Steps 3–8 not started.

> **Where to find the code:** all 13 step-1 file changes are now in **this
> main checkout** (`/home/matthew/Projects/Texera_Projects/texera/`),
> uncommitted. Run `git status` here to see them.
>
> A parallel worktree at
> `/home/matthew/Projects/Texera_Projects/texera-worktrees/feat-live-debugger-step1/`
> contains the same edits on a separate branch (`feat/live-debugger-step1`) —
> it was created earlier per AGENTS.md's worktree workflow. Either location
> has the same code; the main checkout is the one to edit going forward
> per the user's preference.

---

## How to read this doc

Each section below is **one area of the codebase**. Inside each area is a
table of changes. Every change has the same three columns:

| Where | What changes in plain English | Status |

- **Where** points to the actual file (verified to exist).
- **What** is one short sentence a non-engineer can follow.
- **Status** is one of the icons above.

Nothing in here describes how the code works in detail — that's the
design doc's job. This file is the "what is happening, in human words".

---

## Verified facts about the current code

Before changing anything, here is what is already true about Texera today.
These were confirmed by reading the actual source files; you can trust
them.

| Fact | File:line | Why it matters |
| --- | --- | --- |
| When a Python operator throws an error, the engine catches it and **pauses that worker** instead of crashing the run. | `amber/src/main/scala/org/apache/texera/amber/engine/architecture/worker/DataProcessor.scala:302` | The worker is already in a paused, recoverable state when the user sees the error. We are not adding pause-on-error — we are letting the user *do something* with that pause. |
| There is already a function that **puts a failing tuple back at the front of the queue** and resumes the worker. | `amber/src/main/python/core/architecture/handlers/control/replay_current_tuple_handler.py` (the `retry_current_tuple` method) | This is the "Retry" button's engine support. It already exists. |
| Workers can be paused for **four different reasons** (user click, slow downstream, exception, internal marker). | `amber/src/main/scala/org/apache/texera/amber/engine/architecture/worker/PauseManager.scala` + `PauseType.scala` | The pause/resume API is already rich enough for our debugger. We do not add new pause types in v1. |
| The engine already records **every message every worker handles** with a monotonic counter. | `amber/src/main/scala/org/apache/texera/amber/engine/architecture/logreplay/ReplayLogManager.scala` | This is what makes "step back one tuple" possible without inventing a new logging system. |
| There is already a system for **changing an operator's code or properties while the workflow is running**. | `amber/src/main/scala/org/apache/texera/amber/engine/common/FriesReconfigurationAlgorithm.scala` | This is the "Edit operator" button's engine support. Already exists. |
| One small skew exists: the Scala side pauses with `OperatorLogicPause`; the Python `retry_current_tuple` resumes `USER_PAUSE` and `EXCEPTION_PAUSE`. | (see files above) | Worth reconciling in v1 so retry definitely clears the right pause flag. |
| **The `BreakpointFault` proto messages and `breakpointStore` already exist** — but **nothing in the codebase populates the store**. It is initialized in `ExecutionStateStore` and propagated to the frontend like every other store, but no code path ever writes to it. | `executionruntimestate.proto:36-53`, `ExecutionStateStore.scala:56` | This is enormous. The "surface the failing tuple" change is just filling in an unused channel that already runs end-to-end. We don't add new infrastructure — we wire the error path into an existing store. **As of step 1 (in worktree), the store now has one writer.** |

**Bottom line:** most of the heavy machinery the debugger needs is
already there. The work is mostly wiring + a UI.

---

## Changes by area

### Area 1 — The error path (let the user act on a paused worker)

| Where | What changes in plain English | Status |
| --- | --- | --- |
| `amber/.../worker/DataProcessor.scala` | When the operator throws, also send the **failing tuple** to the controller (not just the error message). The UI needs the tuple to show in the debugger panel. | 🟡 Implemented in worktree (uncommitted). `handleExecutorException` now takes `Option[Tuple]`; the input-tuple call site passes the tuple. |
| `amber/.../controller/Controller.scala` | When the controller receives an error, mark the run as **"paused for debugging"** instead of just "errored". This is a new run state. | ⚪ Deferred to step 2 — the engine already pauses the worker locally; surfacing a distinct UI-visible state is the panel's job. |
| `amber/.../worker/PauseManager.scala` | Make sure that `OperatorLogicPause` is the pause type cleared by a Retry. Either rename it or make Retry clear all three of `USER_PAUSE`, `EXCEPTION_PAUSE`, `OperatorLogicPause`. | ⚪ Deferred to step 2 — only matters once a Retry button exists. |

**In plain words:** when an operator crashes, the worker is paused and holding the bad tuple. Today the UI just shows the error text. We change the engine to also report the bad tuple, and to mark the run as "paused, awaiting debugger" so the UI can show the new panel instead of just an error.

---

### Area 2 — Stepping backward and forward

| Where | What changes in plain English | Status |
| --- | --- | --- |
| `amber/.../logreplay/ReplayLogManager.scala` | Add an index so the controller can quickly find "the closest checkpoint before step number K". This is what makes step-back fast. | ⚪ Planned |
| `amber/.../controller/GlobalReplayManager.scala` | Add a method called **"land worker at step K"** — given a target step, restore the nearest checkpoint and replay forward until the worker is at exactly step K. This is the single primitive that powers both step-back and step-forward. | ⚪ Planned |
| `amber/.../controller/GlobalReplayManager.scala` | Add a small **cache of recently reconstructed states** (a ring buffer). When the user rapidly clicks "back, back, back", we don't redo the work each time. | ⚪ Planned |
| New RPC message: `DebuggerStepRequest(target_step, granularity)` | New control message the frontend sends to ask "land the worker at this step". `granularity` is either `tuple` or `batch`. | ⚪ Planned |

**In plain words:** the engine already records a log of every tuple and every operator state every so often. We add a button that says "rewind to step number X" — internally that means "find the latest saved snapshot before X, restore it, then replay forward to X". We cache the last few results so the user can scrub back and forth without re-doing the work each time.

---

### Area 3 — Editing things (tuples, operator code, state)

| Where | What changes in plain English | Status |
| --- | --- | --- |
| Frontend operator editor (existing) | Allow the editor to open **while the run is paused for debugging**, not just at design time. No engine change. | ⚪ Planned |
| `amber/.../common/FriesReconfigurationAlgorithm.scala` | Call this from the new "Save edit & retry" flow. No change to FRIES itself — just a new caller. | ⚪ Planned |
| `amber/src/main/python/core/architecture/handlers/control/` (new handler) | New handler **`mutate_head_tuple`** — replaces the failing tuple with a user-edited version before retry. | ⚪ Planned |
| `amber/.../python/...` (new handler) | New handler **`mutate_operator_state`** — pause, write new state into the operator, resume. Reuses the snapshot/restore code path. | ⚪ Planned |
| `amber/.../logreplay/ReplayLogManager.scala` | Allow overwriting a tuple in the in-memory replay window, and **mark all log entries after step K as stale** so they get recomputed on the next replay. | ⚪ Planned |

**In plain words:** the engine can already change an operator's code while it's running. We add three new small helpers: (1) "replace this bad tuple with a fixed version", (2) "edit the operator's internal counters/state", and (3) when the user edits a tuple in the middle of the replay window, everything after that point gets marked as "needs to be recomputed".

---

### Area 4 — Frontend (new Debugger panel)

| Where | What changes in plain English | Status |
| --- | --- | --- |
| `frontend/src/app/workspace/component/result-panel/` (new sub-component) | New **Debugger panel** that appears in the result area when a fault happens or the user pauses. Has step-back/step-forward, edit tuple, edit operator, edit state, skip, inject. | ⚪ Planned |
| `frontend/.../workflow-websocket.service.ts` | Subscribe to new events: `DebuggerStateUpdate`, `ReplayWindowUpdate`. | ⚪ Planned |
| `frontend/.../execute-workflow.service.ts` | Add methods: `stepBack(granularity)`, `stepForward(granularity)`, `jumpTo(step)`, `retryCurrentTuple()`, `skipCurrentTuple()`, `mutateCurrentTuple(json)`, `mutateOperatorState(json)`. | ⚪ Planned |
| Feature flag in frontend config | Hide the panel behind a flag until v1 is stable. Default off in production. | ⚪ Planned |

**In plain words:** in the result area at the bottom of the workspace, we add a new tab called "Debugger" that only shows up when the run is paused. It looks like the mockup in section 9.4 of the design doc — a few buttons, an editable tuple, an editable operator window, and a list of recent batches.

---

### Area 5 — Web protocol (messages between browser and server)

| Where | What changes in plain English | Status |
| --- | --- | --- |
| `proto/.../controlcommands.proto` | Step 1: added one new request — `BreakpointFaultTriggeredRequest` — carrying worker_name, tuple_id, is_input, and the stringified tuple fields. Wired into the `ControlRequest` oneof at slot 12. | 🟡 Implemented in worktree |
| `proto/.../controllerservice.proto` | Step 1: added `rpc BreakpointFaultTriggered(BreakpointFaultTriggeredRequest) returns (EmptyReturn);` next to the existing `ConsoleMessageTriggered` RPC. | 🟡 Implemented in worktree |
| `proto/.../executionruntimestate.proto` | Step 1: marked existing `BreakpointFault` message with the scalapb pragma so it extends `ClientEvent` and can ride the existing controller→web event bus (same trick the `ConsoleMessage` proto already uses). | 🟡 Implemented in worktree |
| New: `BreakpointFaultHandler.scala` (controller-side handler) | Step 1: receives the new RPC, rebuilds the domain `BreakpointFault`, calls `sendToClient(fault)` exactly like `ConsoleMessageHandler` does for console messages. | 🟡 Implemented in worktree |
| New: `ExecutionBreakpointService.scala` (web-side service) | Step 1: registers a callback on `BreakpointFault` client events; appends each fault into `executionStateStore.breakpointStore` under the operator's id. The pure store-update logic is extracted as a static `BreakpointFaultProcessor` so it's testable without an `AmberClient`. | 🟡 Implemented in worktree |
| `WorkflowExecutionService.scala` | Step 1: instantiate the new `ExecutionBreakpointService` alongside the other execution services; unsubscribe on shutdown. | 🟡 Implemented in worktree |
| Future steps: `DebuggerStepRequest`, `DebuggerEditTupleRequest`, etc. | Not started. Will be added in steps 2–7 as the panel grows. | ⚪ Planned |

**In plain words:** the browser and the server already talk over a websocket. Step 1 wires up one new RPC end-to-end — `BreakpointFaultTriggered` — so when an operator throws, the failing tuple lands in the controller's web-layer store. The frontend doesn't yet read from it (step 2's job), but the data is there and unit-tested.

---

### Area 6 — AI debug assist (v2 — after v1 ships)

| Where | What changes in plain English | Status |
| --- | --- | --- |
| `agent-service/src/` (new route) | New endpoint `POST /debug/diagnose`. Takes the failing tuple, the operator code, the exception/stack, and recent good tuples. Returns a proposed patch and a one-paragraph explanation. | ⚪ Planned |
| Debugger panel (frontend) | "🤖 Ask AI to fix this" button. Sends the four inputs to the agent service, shows the patch in a diff view, gates application behind an explicit user click. | ⚪ Planned |
| Audit table (new SQL table) | Records every AI-proposed patch + whether it was accepted. So we can measure how often the AI is right. | ⚪ Planned |

**In plain words:** once the debugger panel exists, all the information an AI needs to diagnose a failure is right there. We add one new server endpoint that sends those four things to an LLM and gets back a suggested fix. The user sees the suggestion in a normal code-diff view and decides whether to accept it. The AI never changes code on its own — it just removes the "stare at the stack trace, retype the fix, restart from row 0" loop.

---

## Things we are explicitly NOT changing (v1)

These are tempting but out of scope. Listed here so reviewers don't ask.

- The replay log's **storage format** (`SequentialRecordStorage`).
- The **ECM marker** mechanism. We reuse it untouched.
- Any **operator code** itself. We don't change how operators work.
- The **Pekko actor model**. The debugger is built on top of existing actors, not as a new actor.
- **k8s deploy** — single-node only in v1; we will figure out replay-log storage on k8s in v2.

---

## Risks worth knowing about (translated to plain English)

| Risk | What it means for the user | What we'll do |
| --- | --- | --- |
| Replay log can get big on long runs | Hours-long runs could consume disk space for the replay log | Cap the log to (cadence × longest expected debug session), drop older entries automatically |
| Rewind points only happen at marker boundaries | "Step back" might jump further than the user expected | Show the user where the available rewind points are; allow finer-grained step-back via replay-forward from a snapshot |
| Operator UDFs that use `random()` or `time.now()` won't replay identically | After a rewind, the data might look slightly different than the first time | Detect non-deterministic calls and either record them or warn the user |
| Some operator state isn't safely user-editable (e.g. open DB connections) | "Edit state" can't expose every field | Allowlist of editable field types per operator; everything else is read-only |

---

## Step 1 — Concrete plan (ready to implement)

**Goal:** when an operator throws, the failing tuple shows up in
`ExecutionStateStore.breakpointStore` and propagates to the frontend over
the existing websocket. No new UI yet — just verify the data lands.

### Files that change (≤4 edits, all tiny)

| File | Change in one sentence |
| --- | --- |
| `amber/.../worker/DataProcessor.scala` (around lines 104–122 and 302–310) | `handleExecutorException` takes an optional `Tuple` parameter; `processInputTuple` passes the failing `tuple` when it catches. |
| `amber/.../controller/promisehandlers/ConsoleMessageHandler.scala` (or a new sibling handler) | When the error console message arrives, also write a `BreakpointFault` into `executionStateStore.breakpointStore`. |
| `amber/.../error/ErrorUtils.scala` | New helper `mkBreakpointFault(actorId, tuple)` that converts a `Tuple` to the existing `BreakpointFault` proto (using the `repeated string tuple` field already defined). |
| `amber/.../rpc/controlcommands.proto` (optional) | If we want to carry the tuple alongside the existing `ConsoleMessageTriggeredRequest`, add an optional `BreakpointFault failing_tuple = 2;`. Alternative: add a new RPC `BreakpointFaultTriggered` and keep `ConsoleMessageTriggered` unchanged. Decision pending. |

### Test that gets written first (TDD)

Extend `ErrorUtilsSpec.scala` (already exists at
`amber/src/test/scala/org/apache/texera/amber/error/ErrorUtilsSpec.scala`)
with:

```
"mkBreakpointFault" should "render a Tuple as a BreakpointTuple with stringified fields" in {
  val t = Tuple.builder(schema).addSequentially(Array("ada", 42)).build()
  val fault = ErrorUtils.mkBreakpointFault(ActorVirtualIdentity("worker-A"), t, isInput = true)
  fault.workerName shouldBe "worker-A"
  fault.faultedTuple.get.isInput shouldBe true
  fault.faultedTuple.get.tuple shouldBe Seq("ada", "42")
}
```

Plus an integration test under `amber/src/test/scala/.../worker/` that
fires an exception inside a fake executor and asserts
`breakpointStore` has one entry afterwards. (Test file likely needs to
be created — to verify what's available, check existing
`DataProcessorSpec.scala` if it exists.)

### What the user will see after step 1

Nothing visible. The data is in the store, the websocket is delivering
it to the frontend, but no panel renders it. This is **deliberate** —
step 1 verifies the plumbing; step 2 (Debugger panel with Retry button)
is what becomes user-visible.

### Open decision before starting

Two ways to carry the tuple alongside the error:

- **A. Extend `ConsoleMessageTriggeredRequest`** — add `optional BreakpointFault failing_tuple = 2;`. One proto change, one new field on a single existing message. Minimal.
- **B. Add a new RPC `BreakpointFaultTriggered`** — sibling of `ConsoleMessageTriggered`. Cleaner separation of concerns; the console message stays purely about console output.

Recommend **B** — `ConsoleMessageTriggered` fires for any console output, not just errors, so polluting it with breakpoint info is wrong-shaped. Worth ~10 extra lines of code to keep the semantics clean.

---

## What changes when (rough order)

A natural implementation order — each step is independently testable.

1. 🟡 **Surface the failing tuple to the controller and frontend.** Smallest possible change; gives us the data we'll need everywhere else. **Implemented; 28/28 backend tests pass.**
1.5. 🟡 **Bridge: push faults from breakpointStore to the browser over the websocket.** Diff handler on the store emits `BreakpointFaultEvent` whenever new faults are appended. **Implemented; 6 new diff-handler tests, total 34/34 backend tests pass.**
2. 🟡 **Add the new Debugger panel with just a "Retry" button.** Frontend scaffold (component + template + spec); backend wires `RetryRequest` → `retryWorkflow` RPC; clears the operator's faults on retry. **Backend implemented + 34/34 pass; frontend code written but specs not run locally (Node version mismatch).** Still pending: integrating the component as a tab in `result-panel.component`.
3. ⚪ **Add "Edit operator & retry."** Reuses the existing FRIES path. Should be a small wiring change.
4. ⚪ **Add "Skip tuple" and "Edit tuple."** New small handlers, mostly Python-side.
5. ⚪ **Add step-back to the nearest snapshot (coarse rewind).** Uses `GlobalReplayManager` mostly as-is.
6. ⚪ **Add fine-grained step-back (tuple-by-tuple) using the snapshot + replay-forward trick.** This is the biggest item.
7. ⚪ **Add operator state editing.** Last v1 item.
8. ⚪ **v2: AI debug assist.** After v1 is stable.

### Step 1 — concrete file list (in this main checkout)

10 files modified, 3 files added. Run `git status` and `git diff upstream/main` here to see the full diff.

**Modified:**
- `amber/src/main/protobuf/.../controlcommands.proto`
- `amber/src/main/protobuf/.../controllerservice.proto`
- `amber/src/main/protobuf/.../executionruntimestate.proto`
- `amber/.../engine/architecture/controller/ControllerAsyncRPCHandlerInitializer.scala`
- `amber/.../engine/architecture/worker/DataProcessor.scala`
- `amber/.../engine/architecture/worker/promisehandlers/EndChannelHandler.scala`
- `amber/.../engine/architecture/worker/promisehandlers/StartChannelHandler.scala`
- `amber/.../amber/error/ErrorUtils.scala`
- `amber/.../web/service/WorkflowExecutionService.scala`
- `amber/src/test/scala/.../amber/error/ErrorUtilsSpec.scala` (+5 tests)

**Added (step 1 + 1.5 + 2):**
- `amber/.../engine/architecture/controller/promisehandlers/BreakpointFaultHandler.scala`
- `amber/.../web/service/ExecutionBreakpointService.scala` (extended in step 1.5 + step 2)
- `amber/.../web/model/websocket/event/BreakpointFaultEvent.scala` (step 1.5)
- `amber/src/test/scala/.../web/service/ExecutionBreakpointServiceSpec.scala` (now 12 tests)
- `frontend/.../result-panel/debugger-frame/debugger-frame.component.ts` (step 2)
- `frontend/.../result-panel/debugger-frame/debugger-frame.component.html`
- `frontend/.../result-panel/debugger-frame/debugger-frame.component.scss`
- `frontend/.../result-panel/debugger-frame/debugger-frame.component.spec.ts` (6 tests)
- modified `frontend/.../types/workflow-websocket.interface.ts` (new `BreakpointFaultEvent` + 2 interfaces)

**Test command (in this main checkout):**

```
cd /home/matthew/Projects/Texera_Projects/texera
sbt 'WorkflowExecutionService/testOnly \
     org.apache.texera.amber.error.ErrorUtilsSpec \
     org.apache.texera.web.service.ExecutionBreakpointServiceSpec'
```

Latest backend run: **34/34 pass in 7s** (incremental — initial full build is ~45s).
Frontend: spec written but not executed here — system Node is 18; AGENTS.md requires Node 24. Run `yarn test` in `frontend/` with the right Node version to verify.

---

## How to update this doc

When a change starts: flip its row from ⚪ to 🟡 and add a date.
When a change merges: flip to 🟢 and add the PR number.
If a change turns out to be wrong: strike it through and add a one-line note explaining why.

Keep the language in this doc readable by someone who has never touched
Texera. If a change can't be explained in one short sentence, that's a
signal the change is too big and should be split.
