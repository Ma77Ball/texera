# Live Debugger — GitHub Issue Drafts

These are **drafts**. Nothing has been filed. Review them, edit as needed,
then file on `apache/texera` either manually on the website or via
`gh issue create --repo apache/texera ...`.

AGENTS.md notes that apply:

- File against `apache/texera`, never a fork.
- Issue titles are **plain prose** — no Conventional Commits prefix.
- Pick the right template **and** set the GitHub Issue Type explicitly.
- Both title and any branch name should be ≤ ~60 chars.

---

## Issue 1 — Parent feature issue

**Title:** Live run debugger: pause, edit, and step through workflows on failure
**Type:** Feature
**Template:** `feature-template.yaml`
**Affected Area:** Workflow Engine (Amber), Workflow UI

### Feature Summary

When a Texera workflow fails partway through a run (typically a Python UDF
throwing on an unexpected row), the only available action today is to kill
the run and restart from the beginning. For long-running ingests this
wastes substantial compute on what is usually a one-line bug.

Proposal: a **live run debugger** that, on failure, pauses the workflow in
place, surfaces the failing tuple and operator state to the user, and lets
them edit the operator, edit the tuple, or step backward/forward through
the recently processed data before resuming. The faulted worker never
restarts; upstream state is preserved.

### Proposed Solution or Design

Full design in [`LIVE_DEBUGGER_DESIGN.md`](LIVE_DEBUGGER_DESIGN.md) and
the implementation log in [`LIVE_DEBUGGER_CHANGES.md`](LIVE_DEBUGGER_CHANGES.md).
Key points:

- The engine **already pauses workers on exception** (`DataProcessor.handleExecutorException` → `pauseManager.pause(OperatorLogicPause)`); the worker is already in a recoverable state when the user sees the error.
- `BreakpointFault` proto messages and `breakpointStore` are already wired through from controller to frontend — they just have no writers today.
- `retry_current_tuple` handler already exists on the Python side as the resume primitive.
- `FriesReconfigurationAlgorithm` already supports live operator-logic swaps.

The feature primarily **composes existing primitives** behind a new UI panel.

Backward/forward stepping uses sparse state snapshots + a dense replay log
(rr / Flink ABS style), so steady-state overhead is in the single-digit
percent range when the debugger is available but not invoked.

Future v2: an AI debug agent in `agent-service` that consumes the same
four inputs the debugger surfaces (failing tuple, operator code, exception,
recent good tuples) and proposes a patch.

### Sub-tasks (linked separately)

- [ ] Step 1 — surface the failing tuple on the error path (this issue's first child)
- [ ] Step 2 — Debugger panel with Retry button (end-to-end minimal UX)
- [ ] Step 3 — Edit operator & retry
- [ ] Step 4 — Skip / edit tuple
- [ ] Step 5 — Coarse rewind to nearest snapshot
- [ ] Step 6 — Fine-grained tuple-level rewind
- [ ] Step 7 — Operator state editing
- [ ] Step 8 (v2) — AI debug assist

---

## Issue 2 — Step 1 task issue (first child)

**Title:** Surface failing tuple to controller on operator exception
**Type:** Task
**Template:** `task-template.yaml`
**Task Type:** Other (foundational wiring)
**Parent:** Issue #(parent feature issue number once filed)

### Task Summary

Today when an operator throws, `DataProcessor.handleExecutorException`
sends a `ConsoleMessageTriggeredRequest` carrying just the error text. The
failing tuple itself, which is in scope at the throw site, is discarded.

The `BreakpointFault` / `BreakpointTuple` proto messages and the
`ExecutionStateStore.breakpointStore` are already defined and already
propagated to the frontend over the websocket, but **nothing currently
populates the store**.

This task wires up that unused path:

1. Add a new RPC `BreakpointFaultTriggered` (sibling to
   `ConsoleMessageTriggered`) carrying a `BreakpointFault`.
2. Add `ErrorUtils.mkBreakpointFault(actorId, tuple, isInput)` to convert
   a `Tuple` to the proto message.
3. Extend `handleExecutorException` to accept the failing `Tuple`; emit
   the new RPC in addition to the existing console message.
4. Add a controller-side handler that writes the `BreakpointFault` into
   `executionStateStore.breakpointStore`.

After this change, the failing tuple is visible in the breakpoint store
on the controller and reaches the frontend over the existing websocket
channel. No UI change in this task — that lands in step 2.

### Test plan

- **Unit:** extend `ErrorUtilsSpec.scala` with a `mkBreakpointFault` test asserting a `Tuple` is rendered as a `BreakpointTuple` with stringified fields and correct `isInput` flag.
- **Integration:** new test under `amber/src/test/scala/.../worker/` that runs an operator that throws on a known tuple and asserts `breakpointStore` contains exactly one `BreakpointFault` with the expected worker name and tuple values after the exception.
- **Manual:** load a sample workflow with a Python UDF that throws on row N; open the websocket message stream; confirm a `BreakpointFault` event arrives with the offending row payload.

### Out of scope

- Any frontend changes — the UI doesn't render the new data yet.
- Any new pause logic — reuses the existing `OperatorLogicPause`.
- Skip / edit / rewind — those are later steps.
