# Java 21 concurrency features — review

Where the Java ≤21 concurrency toolbox stands against this codebase, recorded after
the [Java 21 migration](java21-migration.md). Everything here was verified against
the code rather than assumed. The design is deliberately small and lock-free, so
most of the toolbox is a better fit elsewhere; what is not used is recorded here
with the reason, so the decision does not get re-litigated on a whim.

Companion documents: [`java21-migration.md`](java21-migration.md) (what changed in
17 → 21) and [`profiling-issues-fixing-progress.md`](profiling-issues-fixing-progress.md)
(the measurements that decide where parallelism pays).

## In use

| Feature | JDK | Where | Why |
| --- | --- | --- | --- |
| `ForkJoinPool` (dedicated, parallelism = cores) | 7 | `SimulationEngine` band workers | Superseded the virtual-thread executor in 2026-09. Work stealing suits uneven bands and asymmetric cores, and per-worker deques cost less than one contended queue at ~50k dispatches/s. Measured 12–19% faster at 201/4 partitions, parity at 801. Parallelism is fixed at construction, so the old pool-sizing apparatus does **not** return |
| `Thread.ofVirtual()` | 21 | Simulation actor thread (`SimulationEngine.runLoop`) | The actor's life is `take()`/`get()`/park; a virtual thread unmounts and costs nothing idle |
| `BlockingQueue<Command>` + actor loop | — | `SimulationEngine` | Single-writer actor; steps are queued, never dropped; pausing blocks on `take()` |
| One `volatile EngineSnapshot` publication point | — | `SimulationEngine.publish()` | Generation + stats observed together; no lock on the render path |
| `Future.get()` happens-before join | — | `LifeRules.next()` | Bands write to disjoint buffer slices; joining supplies visibility; retains the `InterruptedException` contract |
| `CountDownLatch` | — | `Command.Barrier`, `close()` | One-shot blocking for `sync()`; a single rendezvous, not a reusable barrier |
| `CopyOnWriteArrayList` | — | engine listeners | Low-frequency, few listeners; reads never block |
| Thread factories gone | — | — | `Thread.ofPlatform()/ofVirtual()` builders replaced `NamedThreadFactory` |
| `ExecutorService` as `AutoCloseable` | 19 | benchmark, tests, engine `close()` | Replaces manual `shutdown`/`awaitTermination` blocks |

## Considered and rejected / not applicable

| Feature | JDK | Disposition | Rationale |
| --- | --- | --- | --- |
| `Executors.newVirtualThreadPerTaskExecutor` | 21 | ⬅️ Superseded | Used for band workers from the Java 21 migration until 2026-09, on the correct reasoning that it matched a platform pool and deleted the pool-sizing apparatus. Replaced by a dedicated `ForkJoinPool`, which keeps that simplicity *and* is measurably faster. The band work never blocks, which is the one thing virtual threads exist to make cheap — so they were always a neutral choice here, not a beneficial one |
| `ForkJoinPool` as a *recursive decomposition* (`RecursiveTask`) | 7 | ❌ Rejected | This is the proposal the earlier rejection was really about: it replaces the band model `PartitionEquivalenceTest` pins, for a few percent. Using `ForkJoinPool` merely as the executor behind the existing band model is a one-line change and is now in use above — the two were conflated |
| `Phaser` with persistent band loops | 7 | ❌ Rejected | Workers become engine-owned, `setPartitions` becomes a reconfiguration, spin phases burn CPU when idle; a few percent at best. Same source. |
| `CompletableFuture` | 8 | ❌ N/A | Band work is synchronous fork-join; there is no async composition to chain |
| `ReentrantLock` / `StampedLock` / RW locks | 5/8 | ❌ N/A | The design is lock-free by construction (single writer + immutable snapshot); a monitor would only add a path that can leak |
| `LongAdder` / accumulators | 8 | ❌ N/A | Counters are simulation-thread-confined |
| Java 19 `Future.state() / resultNow() / exceptionNow()` | 19 | ❌ Rejected | None of them waits, so they cannot replace `get()` where a running band may have to be joined; the interrupt contract is `InterruptedException`, which they do not carry. `get()` stays. |
| `VarHandle` | 9 | ❌ N/A | One `volatile` field is the correct tool for a single writer + many readers |
| Flow API | 9 | ❌ N/A | Actor + snapshot polling already decouples producer from consumer with no backpressure knobs to turn |
| `StructuredTaskScope` (JEP 428 / 453) | 21 preview | 📌 Tracked | Closing the scope cancels all subtasks, which would make band-failure cleanup automatic. Gated on the project's no-preview policy: preview forces `--enable-preview` on the compiler **and** surefire, and the shaded jar runs only on JDK 21 (this toolchain is JDK 25). Best revisited with `release=23/25`, where the API is final, as part of a deliberate upgrade; the `ForkJoinPool`/`Phaser` notes are the counterweight if anyone reopens the band model. |
| `ScopedValue` | 21 preview | ❌ N/A | No `ThreadLocal`s exist in the codebase to replace |

## One gap found and fixed (2026-08): band-failure hygiene

`LifeRules.next()` dispatches bands 1..N−1 to the virtual-thread executor, computes
band 0 itself, then joins. When a band failed, the surviving siblings were simply
abandoned: they ran to completion *unseen* while the caller unwound with an error.
On failure it now marks the remaining futures cancelled (`cancelAll`), so a failed
generation stops scheduling new work and stragglers finish marked rather than
silently. `computeBand` never blocks, so the interrupt flag is best-effort &mdash;
the win is intent and hygiene, not early termination &mdash; and the success path is
untouched, so there is no performance cost.

Pinned by `LifeRulesTest.failingDispatchedBandSurfacesWrapped`: a crashed worker
escapes as a `SimulationException` whose cause is the worker's own exception, which
is exactly the wrapped form the engine's `State.FAILED` / `onError` path expects.

## Design invariants worth restating

- **One writer, many readers, zero locks.** The simulation thread alone mutates state;
  readers observe an immutable snapshot through one volatile.
- **No `synchronized` blocks on the hot path.** Virtually nothing can pin a carrier in
  practice; a JFR recording found zero `VirtualThreadPinned` events.
- **Parallelism is a work-distribution choice.** The resulting board is identical at
  every partition count; `PartitionEquivalenceTest` pins that property.