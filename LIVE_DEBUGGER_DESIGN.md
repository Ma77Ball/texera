# Live Run Debugger — Design Note

**Status:** Draft / discussion document
**Author:** mgball@uci.edu
**Date:** 2026-05-14
**Future goal:** an AI agent that watches the live run, diagnoses the upstream
cause of a failure, proposes a patch, and lets the user re-enter the data
stream at the last good tuple — without re-running the workflow from scratch.

---

## 1. The problem in one paragraph

Texera workflows are long-lived dataflow graphs. A run can pull tens of GB
through a chain of operators, ten of which may be Python UDFs the user is
actively iterating on. Today, when an operator three hops downstream throws
on tuple 4,217,512, the run is effectively dead: the user fixes the UDF,
hits **Run** again, and Texera replays the full ingest + every successful
upstream operator from tuple 0. On large pulls this is minutes-to-hours of
wasted compute, and the failure usually was not in the ingest — it was a
single bad row or a one-character typo in the UDF body. **Live Run Debugger**
makes failure non-terminal: pause at the fault, step back through the tuples
that produced it, edit the offending operator's logic in place, and resume
forward from where the engine paused.

Crucially, almost every primitive we need is **already in the engine** —
it's been built incrementally for fault tolerance, reconfiguration, and
breakpoints. The feature is mostly about wiring those primitives to a user-
facing debugger UI and giving them a coherent state machine.

---

## 2. What actually happens today when a workflow fails

This is the bit worth getting right, because the design hinges on a clear
picture of the existing failure path. File:line pointers are below — the
codebase is the source of truth, not this doc.

### 2.1 Execution model recap

```
                  ┌─────────────────────────────┐
                  │       Controller actor      │  amber/.../controller/Controller.scala
                  │ - schedules operators       │
                  │ - tracks per-worker state   │
                  │ - drives ECM markers        │
                  └──────────────┬──────────────┘
                                 │ DCM (direct control messages, gRPC-over-Pekko)
              ┌──────────────────┼──────────────────┐
              ▼                  ▼                  ▼
        ┌──────────┐       ┌──────────┐       ┌──────────┐
        │ Worker A │──────▶│ Worker B │──────▶│ Worker C │   workflow-worker actors
        │ DPThread │ tuples│ DPThread │ tuples│ DPThread │   amber/.../worker/WorkflowWorker.scala
        └──────────┘       └──────────┘       └──────────┘
             │                  │                  │
             ▼                  ▼                  ▼
        InputManager       PauseManager      OutputManager   amber/.../worker/*
        DataProcessor      ReplayLogger      executor.processTupleMultiPort()
```

Each `WorkflowWorker` runs a dedicated `DPThread` that pulls tuples off
`InputManager` and calls
`DataProcessor.processInputTuple()`
(`amber/src/main/scala/.../worker/DataProcessor.scala`, ~line 104). Every
single message a worker handles is timestamped with a monotonic
`ProcessingStepCursor` value — i.e. the engine already maintains a per-worker
"clock" of how many tuples/control messages it has processed.

### 2.2 The error path today

```
operator UDF throws
   │
   ▼
DataProcessor.processInputTuple()    ← caught by the safely{} macro
   │
   ├── handleExecutorException()  (DataProcessor.scala ~line 302)
   │       │
   │       ├── sends ConsoleMessageTriggeredRequest → Controller
   │       └── pauseManager.pause(OperatorLogicPause)     ← worker pauses LOCALLY
   │
   ▼
Controller receives error, fires WorkflowErrorEvent
   │
   ▼
Frontend (workflow-websocket.service) shows WorkflowFatalError
in the error-frame; result panel freezes; user clicks "Kill".
```

The local pause is the most important detail in this whole document. **The
worker that hit the exception is not dead. It is sitting in
`OperatorLogicPause`, holding its inputs, with the failing tuple still
available in its current iterator.** Today the UI does not expose this — the
only action presented to the user is *stop the run* — but the engine state
needed to do better is right there.

There is even a Python-side handler,
`replay_current_tuple_handler.py::retry_current_tuple()`, which **re-chains
the current tuple onto the front of the input iterator and resumes**. It
exists for exactly the use case this doc is about, but nothing in the UI
invokes it after a fatal.

### 2.3 What we get "for free" from the existing engine

| Primitive | Where it lives | What we use it for |
| --- | --- | --- |
| Worker-local pause on exception | `DataProcessor.handleExecutorException` + `PauseManager` (4 pause variants incl. `OperatorLogicPause`) | Entering the debugger |
| Message-level replay log | `logreplay/ReplayLogManager.scala`, `ReplayLoggerImpl`, `ProcessingStepCursor` | Stepping BACKWARD across tuples |
| Global replay coordination | `controller/GlobalReplayManager.scala` (`onRecoveryStart` / `onRecoveryComplete`) | Coordinating multi-worker rewind |
| Retry-current-tuple | `replay_current_tuple_handler.py` | Stepping FORWARD one tuple after a fix |
| Live operator-logic swap | `FriesReconfigurationAlgorithm` + `ModifyLogicRequest` | Editing the UDF mid-run |
| Breakpoint store | `executionruntimestate.proto` (`BreakpointFault`, `BreakpointTuple`, `OperatorBreakpoints`), `ExecutionStateStore.breakpointStore` | Surfacing fault + paused tuple to UI |
| Console / error stream | `ConsoleMessageTriggeredRequest`, `ExecutionConsoleService` | Showing the exception + stack to the user |

Nothing in this list needs to be invented. Almost all of it is already
exercised by existing features (FRIES reconfiguration, fault-tolerant
recovery, the Python debug handler). The gap is **composition + a UI**.

---

## 3. Why a naive "just resume" doesn't work

If we just unpaused the worker after an exception, the exception would fire
again immediately on the same tuple. So the design must answer three
questions:

1. **What does "step back" mean across a parallel dataflow?**
   A single worker has a tuple-level history (`ProcessingStepCursor`), but
   stepping back across operators means rewinding *upstream* state too —
   because the offending tuple may have been produced by a stateful upstream
   operator (e.g. an aggregator). The engine's existing `GlobalReplayManager`
   already encodes the semantics: replay scope is delimited by ECM markers.
   The debugger surfaces those scopes as **safe rewind points** rather than
   inventing new ones.

2. **What is the user actually allowed to change before resuming?**
   - Trivial: tweak the failing operator's UDF body (already supported via
     `ModifyLogicRequest` — FRIES handles the wiring).
   - Harder: skip the offending tuple, or mutate it in place. The
     retry-current-tuple handler can do skip; mutate needs a small new
     control message that mutates the head of the input iterator before
     calling resume.
   - Out of scope for v1: changing operator topology (add/remove a node).
     FRIES can technically do it, but the UX implications (does the user
     expect the new operator to see history?) are large enough that we
     defer it.

3. **What happens to downstream workers that already saw the bad tuple?**
   In the failure case we care about — the exception happened *at* the
   downstream operator — downstream of that operator hasn't seen anything
   yet, so the answer is "nothing." For the harder case where the bad
   tuple silently corrupted state two hops down, the user has to pick a
   rewind point upstream of the corruption, and `GlobalReplayManager`
   coordinates the multi-worker rewind. The UI presents the eligible
   rewind points; the user picks one.

---

## 4. Design

### 4.1 User-visible state machine

```
        ┌─────────┐  run                 ┌─────────────┐
        │  IDLE   │ ───────────────────▶ │   RUNNING   │
        └─────────┘                      └──────┬──────┘
              ▲                                 │  exception
              │ stop                            ▼
              │                          ┌─────────────────┐
              │                          │ PAUSED_ON_FAULT │  ← NEW
              │                          └──────┬──────────┘
              │                                 │
              │              user picks one of: │
              │   ┌─────────────────────────────┼─────────────────────────────┐
              │   ▼                             ▼                             ▼
              │ step back (rewind to ECM)   edit operator + retry          skip tuple
              │   │                             │                             │
              │   └─────────────┬───────────────┴─────────────────┬───────────┘
              │                 ▼                                 ▼
              │           ┌──────────┐                      ┌──────────────┐
              │           │ REWINDING │ ─── GlobalReplay ──▶│ STEPPING_FWD │
              │           └──────────┘                      └──────┬───────┘
              │                                                    │ ok
              └────────────────────────────────────────────────────┘
                                                                   │
                                                                   ▼
                                                              RUNNING
```

`PAUSED_ON_FAULT` is the only genuinely new state. It is what the worker is
already in — we are just naming it and giving the user actions.

### 4.2 New control messages

Two new RPC requests, both small wrappers over things the engine can already
do:

| Request | Sent by | Effect |
| --- | --- | --- |
| `DebuggerRewindRequest(workflowId, anchor)` | Frontend → Controller | `GlobalReplayManager` replays workers back to the chosen ECM anchor; `ExecutionStateStore` enters `PAUSED_AT_ANCHOR` |
| `DebuggerStepForwardRequest(workflowId, mode)` | Frontend → Controller | `mode ∈ {RETRY, SKIP, MUTATE(tuple)}`; controller forwards to the faulted worker which calls the existing retry-current-tuple path with the chosen mode |

`ModifyLogicRequest` already exists for the "edit operator + retry" flow, so
no new message is needed for that; the debugger just calls it before
`DebuggerStepForwardRequest(RETRY)`.

### 4.3 Frontend surface

A new **Debugger panel** that mounts when `ExecutionStateStore` reports a
fault. It is a sibling of `result-panel/console-frame` so it shares the
existing console + error stream.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Workflow paused on fault in operator: filter_python_3               │
│  Exception: KeyError: 'user_id' (worker w-7, step #4,217,512)        │
│                                                                      │
│  Failing tuple:                                                      │
│    { "id": 481923, "name": "ada", "country": "GB" }                  │
│                                                                      │
│  Upstream rewind anchors:                                            │
│    ◉  step #4,217,000   (this operator, ECM marker)                  │
│    ○  step #4,200,000   (csv_source, ECM marker)                     │
│    ○  step #0           (start of run)                               │
│                                                                      │
│  [ ◀ Step back ]   [ Edit operator… ]   [ Skip tuple ]   [ Retry ▶ ] │
└──────────────────────────────────────────────────────────────────────┘
```

- **Failing tuple** comes from the existing `BreakpointTuple` payload that
  `ExecutionStateStore.breakpointStore` already carries.
- **Upstream rewind anchors** are the ECM markers the controller has emitted
  since the run began; `GlobalReplayManager` already knows about these.
- **Edit operator…** opens the existing operator-property panel scoped to
  the faulted operator. On Save it issues `ModifyLogicRequest`. No new edit
  flow.

### 4.4 Worker-level sequence on "Edit + Retry"

```
UI                Controller            FaultedWorker            UpstreamWorker
 │ ModifyLogic       │                       │                          │
 ├──────────────────▶│ FRIES: compute MCS    │                          │
 │                   ├──────────────────────▶│ swap executor logic      │
 │ DebuggerStepFwd   │                       │ (PauseManager still held)│
 ├──────────────────▶│                       │                          │
 │                   ├── retry_current_tuple ▶│                          │
 │                   │                       │ re-chain head tuple       │
 │                   │                       │ pauseManager.resume()    │
 │                   │                       │ DPThread loops           │
 │                   │                       │ → either OK → RUNNING    │
 │                   │                       │ → or new exception →     │
 │                   │                       │   back to PAUSED_ON_FAULT│
 │◀──── state ───────┤                       │                          │
```

The faulted worker never restarts. It does not lose its open file handles,
its in-memory hash table, its DB connection. That's the whole point.

### 4.5 What is in scope vs. out of scope for v1

| In scope | Out of scope (v2+) |
| --- | --- |
| Pause on exception (already works), surfacing in UI | Pause on user-defined predicate (true breakpoints) |
| Edit Python UDF + retry current tuple | Editing Scala operators (recompile cost) |
| Skip / mutate the failing tuple | Editing tuples mid-iterator below the head |
| Rewind to ECM anchors | Arbitrary-step rewind (would need denser markers) |
| Single-fault workflows | Multiple concurrent faults across workers |
| Local single-node deploys | k8s deploy (need to verify replay log storage path) |

### 4.6 Risks / things to verify before building

1. **Replay log storage cost.** `ReplayLogManager` writes asynchronously to
   `SequentialRecordStorage`. For long runs this can be large. We may need
   a TTL / size cap on the live debugger's replay window.
2. **ECM marker density.** Rewind granularity is bounded by how often the
   controller emits ECMs. If they are too sparse, "step back" feels coarse.
   Probably tunable; needs measurement.
3. **Stateful operators across rewind.** Confirming `GlobalReplayManager`
   actually restores in-memory operator state (e.g. an aggregator's hash
   table) and not just the input queue. Read the existing recovery tests
   in `amber/src/test/scala/.../logreplay/` before committing to the
   design.
4. **Determinism of UDFs.** If a user's Python UDF calls `random()` or
   `time.now()`, replay won't be byte-identical. The existing
   `DeterminantLogger` may already address this; needs a look.

---

## 5. Future: AI-assisted live debugging

This is the bit that motivates the whole feature. Once we have **(a)** a
captured failing tuple, **(b)** the operator source, **(c)** the live
exception + stack, and **(d)** a safe resume primitive, an LLM agent can:

```
                ┌──────────────────────────────────────────────┐
                │ AI Debug Agent (agent-service)               │
                │                                              │
                │  inputs:  failing tuple, operator source,    │
                │           exception+stack, last N OK tuples  │
                │  output:  { fix: <patch>, rationale: <md>,   │
                │             confidence: 0..1 }               │
                └──────────────────────────────────────────────┘
                          ▲                       │
                          │ context               │ proposed patch
                          │                       ▼
                ┌──────────────────────────────────────────────┐
                │ Debugger panel (frontend)                    │
                │   [✓ Apply patch + retry]  [Edit manually]   │
                └──────────────────────────────────────────────┘
                          │
                          ▼
                ModifyLogicRequest → FRIES → retry_current_tuple
```

Texera already has the `agent-service` (Bun/TS, LLM-backed agents). The
hooks the agent needs are exactly the four bullets above, which the live
debugger has to expose anyway. **The AI feature is essentially free once
the debugger exists**, which is why it's worth building the debugger
first rather than the other way around.

Concretely, the v2 deliverable is a single new agent route in
`agent-service` that consumes a `DebugRequest` payload (the four inputs
above), returns a candidate patch + rationale, and the UI gates application
behind an explicit user click. No autonomous patching in v2 — the user is
always the decider, the AI just removes the "stare at the stack trace,
re-type the fix, restart from scratch" loop.

---

## 6. Open questions for reviewers

- Is rewinding to ECM anchors enough, or do we need denser checkpoints
  specifically for the debugger? (Implies new instrumentation in
  `ReplayLogManager`.)
- Should "Skip tuple" be allowed silently, or always logged into the run's
  artifact metadata so downstream consumers know data was dropped?
- For multi-worker operators (parallelism > 1), do we pause only the
  faulting worker or all siblings? Pausing only the faulter risks
  ordering anomalies for stateful downstream ops; pausing all siblings
  stalls a healthy run. Default proposal: pause all siblings of the
  faulted operator, leave the rest of the DAG running until backpressure
  catches up.
- Where do we store the AI agent's proposed patches for auditability?
  (Probably a new table joined to the execution record; out of scope here.)

---

## 7. Concrete next steps

1. Read the two test suites under
   `amber/src/test/scala/.../logreplay/` and
   `amber/src/test/scala/.../controller/` to confirm the assumed semantics
   of `GlobalReplayManager`.
2. Prototype `DebuggerStepForwardRequest(RETRY)` end-to-end against a
   single-worker Python UDF that throws on a known row. No UI yet — drive
   it from a unit test that asserts the run completes after the retry.
3. Add the Debugger panel as a hidden tab behind a feature flag in
   `frontend/src/app/workspace/.../result-panel/` and wire it to the
   existing `WorkflowErrorEvent`.
4. Iterate on ECM-anchor rewind once (2) and (3) are landing; this is the
   one piece that may require engine changes.
5. Stand up the agent-service route for the AI assist last.

---

## 8. Prior art and efficiency techniques

The hardest part of this feature is **not** "pause + edit + resume" — Texera
already has those primitives. The hardest part is doing it without making
every run expensive even when no fault occurs. The industry has converged
on a small set of techniques for exactly this trade-off, and most of them
are directly portable.

### 8.1 Three systems worth copying from

| System | What it does | Lesson for us |
| --- | --- | --- |
| **Apache Flink — ABS (Asynchronous Barrier Snapshotting)** | Chandy-Lamport markers flow through the DAG as ordinary data; when an operator has seen the barrier on all input channels it asynchronously snapshots its state, then forwards the barrier. Pipeline never blocks. ([Carbone et al., 2015](https://arxiv.org/abs/1506.08603)) | Texera's ECM markers are already shaped like ABS barriers. The debugger's "rewind anchors" should *be* ABS-style checkpoints, not a parallel mechanism. Async means cadence can be aggressive without stalling the run. |
| **Apache Spark — RDD lineage** | Doesn't checkpoint state at all by default; instead records the *operations* (the lineage). On failure, recomputes only the lost partition by replaying its lineage from the last shuffle boundary. ([Zaharia et al., 2012](https://www.usenix.org/conference/nsdi12/technical-sessions/presentation/zaharia)) | For pure / stateless operators, we don't need to snapshot state at all — just remember the lineage and recompute the failing partition. The debugger should detect stateless operator chains and skip checkpointing them entirely. |
| **rr (Mozilla record-replay debugger)** | Only logs *non-deterministic* inputs (syscalls, signals, scheduling). Replay is byte-identical because everything else is deterministic re-execution. Recording overhead ≈ 1.2×, not 10×. ([O'Callahan et al., 2017](https://arxiv.org/abs/1705.05937)) | This is the single biggest efficiency win available. Don't log every tuple — log only the nondeterminism (`random()`, `time.now()`, network reads). Texera's `DeterminantLogger` is already the right shape for this; verify it covers the common Python UDF sources of nondeterminism. |

### 8.2 The classical algorithms underneath

- **Chandy-Lamport (1985)** — distributed snapshot protocol. Markers flow with the data; receivers snapshot on first marker, then record in-flight messages until they've seen the marker on every input. This is the basis of Flink ABS and is almost certainly the basis of Texera's ECM. Re-reading the original paper before tuning marker cadence is worth the hour.
- **ARIES (Mohan et al., 1992)** — the canonical database crash-recovery algorithm. Three-phase: **analysis** (find the failed transaction set), **redo** (replay all WAL records from the last checkpoint forward to restore state), **undo** (roll back unfinished transactions). Texera's case is simpler — we only need redo, not undo, because the dataflow is append-only — but the *LSN (log sequence number)* discipline maps directly onto `ProcessingStepCursor`.
- **Copy-on-write (COW) state snapshots** — the trick used by RocksDB (Flink's default state backend), MongoDB, and etcd. To snapshot a large state object without stopping writes: don't copy it; freeze it logically and let subsequent writes allocate new pages. Snapshot cost becomes O(size of writes between snapshots) instead of O(state size). Critical when an aggregator's hash table is multi-GB.
- **Persistent / functional data structures (HAMTs, finger trees)** — Clojure, Scala's `immutable.Map`, Immer.js. Cheap structural sharing across snapshots; reading "the state at step #N" becomes O(log N) without ever copying. Worth considering for the operator-state representation if we want fine-grained backward stepping.

### 8.3 Specific efficiency techniques and where they apply

Eight techniques, ordered by impact/effort ratio for our case:

1. **Deterministic-replay logging (only log nondeterminism).** Don't write every tuple to the replay log. Tuples are deterministic given upstream + operator code; only the nondeterministic interactions need logging. ~10× reduction in log volume. **Effort: low (DeterminantLogger exists).**

2. **Sparse checkpoints + dense replay log.** Take a state snapshot every N seconds OR M tuples, whichever first; between snapshots, rely on the replay log. To rewind to step K, find the nearest snapshot ≤ K and replay forward. This is exactly the ARIES / Flink pattern. **Effort: medium.**

3. **Selective replay by lineage.** When the user rewinds, only re-execute operators *downstream* of the rewind point. Operators upstream of, or in a sibling branch from, the fault are untouched. Spark does this implicitly via DAG stage isolation. **Effort: low — the DAG is already known to the controller.**

4. **Async checkpointing (Flink ABS).** The barrier flows through the DAG without blocking; each operator snapshots its state on a background thread while continuing to process. Pipeline throughput drop ≈ 0–5%. **Effort: medium — need to verify Texera's snapshot path is already non-blocking.**

5. **Incremental / delta checkpoints.** Snapshot only the state pages that changed since the last checkpoint, RocksDB-style. Critical for large stateful operators. **Effort: high — depends on state backend.**

6. **Log compaction with anchor retention.** Replay log entries older than the oldest live rewind anchor are dropped. Anchors are GC'd when the user dismisses them or the run completes. Bounds log size to (cadence × longest expected debug session). **Effort: low.**

7. **Two-tier replay log: in-memory ring buffer + spilled segments.** The most recent K minutes of tuples stay in memory for instant rewind; older segments spill to disk. The common case ("I just hit an error, let me step back a few seconds") never touches disk. Used by Honeycomb, Lightstep, eBPF tracers. **Effort: medium.**

8. **Adaptive checkpoint cadence.** Increase snapshot frequency when operator state is small or when the user is actively iterating on a UDF; decrease when state is large or the run looks stable. Flink doesn't do this; rr-style debuggers do. **Effort: high; v3 territory.**

### 8.4 Reactive notebook prior art (for the UX side)

The closest UX analog isn't another stream processor — it's a reactive
notebook. Both **Observable** ([observablehq.com](https://observablehq.com))
and **Pluto.jl** maintain a dependency graph of cells; when one cell
changes, only its descendants re-run, and the runtime preserves the values
of unrelated cells. **Jupyter Lab's "Restart and run from here"** is the
crude version of the same idea. The mental model we want users to have is
"this operator's logic changed; only re-run from this operator forward" —
which is functionally identical to Observable's cell-graph re-execution.
The implementation differs (we have parallel workers + state), but the UX
metaphor is borrowed.

### 8.5 What this means for the v1 design

Concretely, the prior art tells us:

- **Use the existing ECM mechanism as our ABS-equivalent checkpoint trigger.** Don't invent a parallel marker stream.
- **Lean on `DeterminantLogger`, not full tuple logging, for replay.** Verify it covers Python's `random`/`time`/`os.urandom`/network. Add coverage if not.
- **Drop dense replay log entries beyond the oldest live rewind anchor.** Anchors expire when the user dismisses them.
- **Restrict v1 rewind to operators downstream of the fault.** This is the Spark-stage-isolation trick. Cheap, correct, and matches user intent ("the bug is in this operator; everything upstream is fine").
- **Two-tier (memory + disk) replay log.** Optimize for the common case of stepping back a few seconds, not hours.
- **Don't snapshot stateless operator chains at all.** Detect them via the operator metadata and rely on pure lineage replay, Spark-style.

If we adopt the top four of these in v1, the steady-state overhead of
having the debugger *available* (but not invoked) should be in the same
ballpark as Flink's ABS — single-digit percent throughput cost, with no
extra latency on the happy path. That's the bar to hit; anything worse and
users will turn the feature off and we're back to the rerun-from-row-0
status quo.

---

## 9. Expanded scope: full editing + fine-grained stepping

The v1 sketch in §4 framed the debugger as *fault recovery*: pause on
exception, fix, resume. The real ambition is broader — it should also be a
**time-travel + live-edit environment** that the user can enter at any
point in the run, fault or no fault, to inspect and modify the dataflow.
This section expands the scope on two axes: **what you can edit**, and
**how finely you can step**.

### 9.1 What the user can edit (full surface)

Everything in this table is reachable from the Debugger panel:

| Editable thing | How it's edited | Engine mechanism | New work? |
| --- | --- | --- | --- |
| **Operator logic (Python UDF body)** | Code editor in the panel | `ModifyLogicRequest` → FRIES reconfigures | No |
| **Operator properties / parameters** (e.g. filter threshold, join key, model name) | Property form, same form as build-time | `ModifyLogicRequest` carries new property values | No |
| **The failing tuple** | Tuple shown as editable JSON; Save replaces head of input iterator | New `MutateHeadTupleRequest` to faulted worker | Small: a few lines on the Python side, the iterator-rechain code already exists |
| **Tuples in the in-memory replay window** (recent N batches) | Tuple list view in panel; click → edit JSON | New `MutateLoggedTupleRequest(step_id, new_payload)`; replay log entry is overwritten in place, subsequent forward replay sees the edit | Medium: replay log needs to support in-place edit + invalidation of downstream cached state |
| **Operator state** (e.g. aggregator's running counter, dedup hash set) | "State" tab in panel shows operator state as editable JSON; Save writes back via the same checkpoint-restore path | New `MutateOperatorStateRequest` — piggybacks on the existing snapshot-restore path: stop worker, write state, resume | Medium: requires operators to expose serializable state (Python via `__getstate__`/`__setstate__`) |
| **Skip a tuple** | Skip button on any tuple in the replay window | Mark tuple as tombstoned; forward replay ignores it | Low |
| **Inject a tuple** | "Add tuple" button at any step | Insert into replay log at step K with synthetic provenance | Medium |
| **Connections (re-wire the DAG)** | Drag in the workflow editor while paused | FRIES already supports it; just allow it in paused state | Low — UI gating only |

Two design rules that keep this from sprawling:

1. **Every edit goes through the same primitive: pause → mutate → resume.**
   The engine never needs special-case logic for "is this an edit during a
   debug session vs. a normal reconfiguration?" — it's all just
   pause/mutate/resume, the same way FRIES already works.

2. **Every edit is logged as a debug-session event** alongside the replay
   log. This gives an audit trail ("at step #4,217,000 the user replaced
   the aggregator's count from 8,193 to 8,194") and lets us reconstruct
   the run later. Important once the AI agent starts proposing edits.

### 9.2 Tuple-by-tuple and batch-by-batch stepping

The original "rewind to nearest ECM anchor" was coarse. The user wants
**arbitrary-step rewind**: back one tuple, or one batch, or 1,000 tuples.
This is the hardest engineering ask in the doc, and the answer is the
**rr trick**: do not store one snapshot per step. Store sparse snapshots
plus a deterministic replay log, and synthesize "state at step K" on demand
by snapshot + replay-forward.

```
time →
                                                          fault
worker timeline:   ●─────●─────●─────●─────●─────●─────●─────✗
                   │     │     │     │     │     │     │
checkpoints:       ▲                       ▲                       ← sparse: every N steps or T sec
replay log:        ════════════════════════════════════════         ← dense: every tuple/message

to "view state at step K":
   1. find nearest checkpoint ≤ K  (constant-time index)
   2. restore worker to that checkpoint
   3. replay log forward from checkpoint to step K   (deterministic)
   4. pause; show state to user
```

Two granularity levels exposed in the UI:

| Granularity | What it means | Cost |
| --- | --- | --- |
| **Batch** | Step back one network batch (a `DataPayload`, ~hundreds of tuples). Default mode. | Cheap — replay log already at batch granularity for data messages. |
| **Tuple** | Step back one tuple within a batch. | More expensive — requires replay-forward from the snapshot up to tuple K, but bounded by checkpoint cadence. ~ms-to-second for sane cadences. |

**Bidirectional stepping primitive.** Both directions are implemented by
the same engine operation: *land the worker at step K*. Forward = K+1,
backward = K-1, jump = arbitrary K. The user never sees a difference; the
engine always does "find snapshot ≤ K → restore → replay forward to K".

### 9.3 The trick that makes this efficient

Three layered techniques, each handling one cost dimension:

1. **Sparse checkpoints, dense log (§8.3 #2).** Snapshot every N seconds
   or M tuples. Between snapshots, only the replay log exists. To rewind
   one step from the most recent state, you replay forward from the latest
   snapshot — this is at most N seconds / M tuples of work, which we tune
   to keep typical-case rewind in the tens of ms.

2. **Caching the last K reconstructed states (rr-style).** When the user
   is rapidly stepping backward, we don't re-replay from a snapshot for
   every single step. The engine caches the last ~64 reconstructed states
   in a ring buffer; consecutive "step back" clicks are O(1). When the
   user jumps far back, the cache misses and we fall back to
   snapshot+replay.

3. **Per-operator scope.** Stepping back in one operator does not require
   rewinding the entire DAG. Only the faulted operator and any
   stateful operators upstream of the rewind point need to be restored.
   Stateless upstream operators are not touched. Spark stage isolation
   applied to the debug session.

The combined effect: stepping back one batch is nearly free (cached);
stepping back one tuple within a recent batch is tens of ms; stepping back
hours into the run hits cold disk and is a one-shot multi-second cost the
user explicitly opted into.

### 9.4 Updated UI mockup

```
┌──────────────────────────────────────────────────────────────────────┐
│  Debugger — operator: filter_python_3        [▶ Resume]  [■ Stop]    │
│  Paused at step #4,217,512  (fault: KeyError 'user_id')              │
│                                                                      │
│  ◀◀ batch    ◀ tuple    [step #4,217,512 ▼]    tuple ▶    batch ▶▶   │
│                                                                      │
│  ┌─ Tuple (editable) ─────────────────────────────────────────────┐  │
│  │ { "id": 481923, "name": "ada", "country": "GB" }              │  │
│  │ [Save edit]  [Skip this tuple]  [Inject after]                │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌─ Operator ────────────────────┐ ┌─ Operator state ────────────┐  │
│  │ def filter(t):                │ │ seen_ids: { ... 8193 items }│  │
│  │   return t["user_id"] > 0  ←  │ │ count:    8193              │  │
│  │ [Save & retry]                │ │ [Edit state…]               │  │
│  └───────────────────────────────┘ └─────────────────────────────┘  │
│                                                                      │
│  Recent batches (replay window):                                     │
│   #4,217,000  ────  500 tuples  ✓                                    │
│   #4,217,500  ────  12 tuples   ✗ fault                              │
│   [Load older…]                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

Top bar: bidirectional stepping at two granularities + jump-to-step. Center
left: the failing tuple, editable. Center right: operator code (editable)
and operator state (editable). Bottom: replay-window inspector showing
which batches succeeded and where the fault is.

### 9.5 Updated state machine

```
                          ┌───────┐
                          │ IDLE  │
                          └───┬───┘
                              │ run
                              ▼
                       ┌─────────────┐
                  ┌───▶│   RUNNING   │
                  │    └──────┬──────┘
                  │           │  user enters debugger  OR  exception
                  │           ▼
                  │  ┌────────────────┐
                  │  │  DEBUG_PAUSED  │  ← unified pause state
                  │  └────────┬───────┘
                  │           │
                  │  any of:  │
                  │   step (±tuple, ±batch, jump)
                  │   edit (tuple / state / operator / params / topology)
                  │   resume
                  │           │
                  └───────────┘
```

Collapses the previous `PAUSED_ON_FAULT` / `REWINDING` / `STEPPING_FWD`
into a single `DEBUG_PAUSED` state. Fault is just one way to enter it;
clicking a Pause button is another. Step and edit are commands you issue
from inside it. Resume exits back to `RUNNING`.

### 9.6 Updated in-scope / out-of-scope

| In scope (v1) | Deferred (v2+) |
| --- | --- |
| Pause on exception **and** on user request | Predicate breakpoints ("pause when tuple.age < 0") |
| Step ±1 tuple, ±1 batch, jump to step K | Time-based jump ("go back 30 seconds") |
| Edit failing tuple / any tuple in replay window | Editing tuples below the on-disk spill horizon (would force re-fetch from source) |
| Edit operator code, properties, state | Editing tuples that have already been emitted downstream of a stateful sink |
| Skip / inject tuples | Editing tuples that already crossed an ECM boundary into a downstream stateful op (would need cascading rewind — possible but ux is hard) |
| Re-wire DAG while paused | Multi-user concurrent debug sessions on the same run |
| Audit log of every debug-session edit | Sharing/exporting a debug session as a repro bundle |

### 9.7 Risks specific to this expansion

- **State editability.** Some operators have state that isn't safely
  user-editable (open file handles, native objects, JDBC connections).
  The "Edit state" surface needs an allowlist of editable field types
  per operator, not raw `__dict__` access.
- **Replay log writeability.** Editing a logged tuple invalidates all
  downstream cached state derived from it. The replay log needs a
  "dirty from step K" marker so downstream caches are evicted lazily.
- **Determinism preservation.** rr-style replay assumes recorded
  nondeterminism. If the user edits a tuple, the replay diverges from
  the original — which is fine, but the engine must clearly distinguish
  "replay" (must match log) from "fork" (new branch, log is reference
  only). Probably modeled as: editing forks the run; the original is
  retained until the user discards it.
- **UI complexity.** A debugger that can do all of this risks becoming
  a Photoshop of dataflow tools. Keep the panel deliberately minimal in
  v1 (4 buttons + the editors); add discoverability for advanced
  operations behind disclosure triangles.
