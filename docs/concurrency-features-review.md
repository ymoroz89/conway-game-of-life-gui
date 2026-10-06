# Java ≤27 concurrency features — review

Where the Java ≤27 concurrency toolbox stands against this codebase, recorded after
the [Java 25 migration](java25-migration.md) and the [Java 21 migration](java21-migration.md).
Everything here was verified against the code rather than assumed. The design is
deliberately small and lock-free, so most of the toolbox is a better fit elsewhere;
what is not used is recorded here with the reason, so the decision does not get
re-litigated on a whim.

Last verified against the code on **2026-10-06**, when `StructuredTaskScope` was adopted
and the band executor was deleted — see [`structured-concurrency.md`](structured-concurrency.md)
for the decision, the price and the measurements. Before that, 2026-09-27. The review was first written after the
migration (2026-08-22) and updated when the band workers moved to a `ForkJoinPool`
(2026-09-24). The band-executor rows were re-verified on 2026-10-01, when the band workers
moved back to virtual threads: the `ForkJoinPool` had been chosen on a benchmark whose
caller is a platform thread, and was 23% slower in the application
([evidence](../README.md#what-the-application-measured)).

Companion documents: [`java25-migration.md`](java25-migration.md) (what changed in
21 → 25), [`java21-migration.md`](java21-migration.md) (what changed in 17 → 21),
and [`profiling-issues-fixing-progress.md`](profiling-issues-fixing-progress.md)


## In use

| Feature | JDK | Where | Why |
| --- | --- | --- | --- |
| `StructuredTaskScope` (JEP 533, seventh preview) | 27 **preview** | `LifeRules.computeBands()` — one scope per multi-band generation | Since 2026-10-06. A band cannot outlive the generation that forked it, and a band that throws cancels its siblings, so the hand-rolled `cancel(true)` loop is gone. Bands are still virtual threads on the common scheduler, so the 2026-10-01 scheduler finding below still holds. Costs ~3% at 201/4 and pins the build to exactly JDK 27 ([measurements](structured-concurrency.md#measurements)) |
| `Joiner.allSuccessfulOrThrow(Function<Throwable, R_X>)` | 27 preview | `LifeRules.computeBands()` | The mapper receives the failing band's own exception (verified 2026-10-06), so `join()` throws `SimulationException` with that cause directly. No `ExecutionException` is unwrapped anywhere; the failure contract the engine depends on is expressed in the Joiner rather than in catch blocks |
| `Configuration.withName` / `withThreadFactory` | 27 preview | `LifeRules.computeBands()` | A JSON thread dump shows the scope as a container labelled `conway-generation/…` **owned by** the `conway-simulation` thread, holding `conway-band-N` threads. Bands are grouped under the thread that forked them instead of scattered across a pool. Measured free: three A/B runs of named vs unnamed factories overlap and flip order (2026-10-06) |
| ~~`Executors.newVirtualThreadPerTaskExecutor`~~ | 21 | ⬅️ Superseded 2026-10-06 by the scope above | Was the band executor from 2026-10-01 to 2026-10-06. The reasoning in it survives the move and is why the scope is configured with a *virtual* thread factory: since 2026-10-01. The simulation thread that waits on the bands is virtual, so the bands share its scheduler: while it waits its carrier is free to run a band, and a finishing band wakes it through that carrier's local queue. 23% faster than a dedicated `ForkJoinPool` in the application ([evidence](../README.md#what-the-application-measured)). The virtual-thread scheduler still steals work, and nothing is sized. **Corrected 2026-10-01** — this row read: ~~`ForkJoinPool` (dedicated, parallelism `min(maxPartitions, cores)`). Superseded the virtual-thread executor in 2026-09. Work stealing suits uneven bands and asymmetric cores, and per-worker deques cost less than one contended queue at ~50k dispatches/s. Measured 12–19% faster at 201/4 partitions, parity at 801.~~ Those figures came from `GenerationBenchmark`, whose caller is a platform thread |
| `Thread.ofVirtual()` | 21 | Simulation actor thread (`SimulationEngine.runLoop`); band factory in `LifeRules` | The actor's life is `take()`/`join()`/park; a virtual thread unmounts and costs nothing idle |
| `BlockingQueue<Command>` + actor loop | — | `SimulationEngine` | Single-writer actor; steps are queued, never dropped; pausing blocks on `take()` |
| One `volatile EngineSnapshot` publication point | — | `SimulationEngine.publish()` | Generation + stats observed together; no lock on the render path |
| `scope.join()` happens-before edge | 27 preview | `LifeRules.computeBands()` | Bands write to disjoint buffer slices; joining supplies visibility; retains the `InterruptedException` contract. **Corrected 2026-10-06** — this row read ~~`Future.get()` happens-before join, in `LifeRules.joinDispatchedBands()`~~, a method that no longer exists |
| `CountDownLatch` | — | `Command.Barrier`, behind `sync()` | One-shot blocking for `sync()`; a single rendezvous, not a reusable barrier. `releasePendingBarriers` counts down any barrier still queued at shutdown, so `sync()` never waits forever |
| `CopyOnWriteArrayList` | — | engine listeners | Low-frequency, few listeners; reads never block |
| Thread factories, but builder-made | — | `LifeRules` band factory, simulation thread | `Thread.ofVirtual()` names and starts the simulation thread; the band factory is `Thread.ofVirtual().name("conway-band-", 0)` and is handed to the scope. Together they replaced `NamedThreadFactory`. **Updated 2026-10-06** — the row read ~~"Thread factories gone … the band executor starts *unnamed* virtual threads"~~; a factory is now an explicit constructor parameter, and the names are the point |
| ~~`ExecutorService` as `AutoCloseable`~~ | 19 | ➡️ Retired 2026-10-06 | Was how the benchmark, the tests and `SimulationEngine.close()` drained the pool. No `ExecutorService` remains in main or test code: scopes close themselves, and `close()` has nothing to drain |

## Considered and rejected / not applicable

| Feature | JDK | Disposition | Rationale |
| --- | --- | --- | --- |
| `Executors.newVirtualThreadPerTaskExecutor` | 21 | ~~⬅️ Superseded~~ ✅ In use again (2026-10-01) | Used for band workers from the Java 21 migration until 2026-09, on the correct reasoning that it matched a platform pool and deleted the pool-sizing apparatus. ~~Replaced by a dedicated `ForkJoinPool`, which keeps that simplicity *and* is measurably faster. The band work never blocks, which is the one thing virtual threads exist to make cheap — so they were always a neutral choice here, not a beneficial one~~. **Corrected 2026-10-01:** the bands not blocking was the wrong thing to look at. What the bands share with the virtual thread that waits on them is a scheduler, and that is worth 23% here; see the *In use* row |
| Dedicated `ForkJoinPool` as the band executor | 7 | ⬅️ Superseded (2026-10-01) | In use from 2026-09-24 to 2026-10-01. Measured faster in `GenerationBenchmark`, whose caller is a platform thread; 23% slower in the application, whose caller is virtual, because it puts the bands on a second scheduler ([why](../README.md#two-kinds-of-work-one-scheduler)) |
| `ForkJoinPool` as a *recursive decomposition* (`RecursiveTask`) | 7 | ❌ Rejected | This is the proposal the earlier rejection was really about: it replaces the band model `PartitionEquivalenceTest` pins, for a few percent. Using `ForkJoinPool` merely as the executor behind the existing band model is a one-line change ~~and is now in use above~~ (in use 2026-09-24 to 2026-10-01, see the row above) — the two were conflated |
| `Phaser` with persistent band loops | 7 | ❌ Rejected | Workers become engine-owned, `setPartitions` becomes a reconfiguration, spin phases burn CPU when idle; a few percent at best. Same source. |
| `CompletableFuture` | 8 | ❌ N/A | Band work is synchronous fork-join; there is no async composition to chain |
| `ReentrantLock` / `StampedLock` / RW locks | 5/8 | ❌ N/A | The design is lock-free by construction (single writer + immutable snapshot); a monitor would only add a path that can leak |
| `LongAdder` / accumulators | 8 | ❌ N/A | Counters are simulation-thread-confined |
| Java 19 `Future.state() / resultNow() / exceptionNow()` | 19 | ❌ N/A (2026-10-06) | ~~None of them waits, so they cannot replace `get()`…~~ Moot: there are no `Future`s left. The same question on a `Subtask` does not arise either — after `join()` the outcome is already decided, and `Subtask.get()` throws no checked exception |
| `VarHandle` | 9 | ❌ N/A | One `volatile` field is the correct tool for a single writer + many readers |
| Flow API | 9 | ❌ N/A | Actor + snapshot polling already decouples producer from consumer with no backpressure knobs to turn |
| `StructuredTaskScope` | 25–27 preview | ~~📌 Tracked~~ ✅ **In use since 2026-10-06** (see the *In use* table) | ~~Still preview in JDK 25 (a sixth preview, JEP 525, is filed for JDK 26), so the project's no-preview policy keeps it out.~~ **Decision reversed 2026-10-06.** The no-preview policy was the entire argument against it, and it was waived deliberately: still preview in 27 (JEP 533, seventh), still pins the toolchain, still costs ~3%. The adoption shape recorded in `java25-migration-plan.md` §4 is exactly what shipped — `open()`, fork bands 1..N−1, band 0 on the calling thread, `join()`, hand-rolled cancellation deleted. The reversal and its price: [`structured-concurrency.md`](structured-concurrency.md) |
| `forkjoin` mode in `GenerationBenchmark` | 7 | ➡️ Removed 2026-10-06 | A scope creates its own threads from a `ThreadFactory` and cannot fork into a `ForkJoinPool`, so the mode had nothing to select. It had already been dead for days: from 2026-10-01 the harness parsed the mode, printed it, and ignored it — `newExecutor()` was never called. The mode argument now picks the band `ThreadFactory` (`virtual` or `platform`) and is honoured |
| `ScopedValue` (JEP 506, final in JDK 25) | 25 final | ❌ N/A | No `ThreadLocal`s exist in the codebase to replace |
| `Stable Values` (JEP 502, preview) | 25 preview | ❌ N/A | No lazily-initialised constants to speed up |

## One gap, found 2026-08 and closed twice

`LifeRules.next()` dispatched bands 1..N−1 to a virtual-thread executor (a `ForkJoinPool` from
2026-09-24, virtual again from 2026-10-01), computed band 0 itself, then joined. When a band
failed, the surviving siblings were simply abandoned: they ran to completion *unseen* while the
caller unwound with an error.

**First fix, 2026-08: hand-rolled hygiene.** On failure it marked the remaining futures
cancelled (`cancelAll`), so a failed generation stopped scheduling new work and stragglers
finished marked rather than silently. `computeBand` never blocks, so the interrupt flag was
best-effort — the win was intent and hygiene, not early termination — and the success path was
untouched, so there was no performance cost.

**Closed structurally 2026-10-06.** The hand-rolled cleanup is gone: a band that throws
cancels its siblings because the scope says so, and the `Joiner`'s exception mapper produces
the `SimulationException` directly. One case the scope still cannot reach is band zero, which
runs on the owner thread — its failure is recorded, the scope is joined anyway (leaving a scope
unjoined is a structure violation that `close()` reports *instead of* the real failure), and the
inline exception then wins with the forked one suppressed onto it.

Pinned by two tests, `LifeRulesTest.failingForkedBandSurfacesWrapped` and
`failingInlineBandSurfacesWrapped`: however a band crashes, it escapes as a
`SimulationException` whose cause is the band's own exception, which is exactly the form the
engine's `State.FAILED` / `onError` path expects. Both drive the real dispatch path through the
`BandWork` seam. The second is new — band zero was unreachable while failures could only be
injected through an `ExecutorService`.

**A coverage gap worth recording, 2026-10-01 to 2026-10-06.** While the engine used the scope
and the tests injected an executor, `PartitionEquivalenceTest` — the central safety net — was
strung across a dispatch path nothing ran. It now uses the no-arg constructor, and the vacuity
check was re-taken against the real path: making every forked band throw fails nine of its ten
cases (verified 2026-10-06).

## Design invariants worth restating

- **One writer, many readers, zero locks.** The simulation thread alone mutates state;
  readers observe an immutable snapshot through one volatile.
- **Concurrency is scoped, not pooled.** Every band lives inside the generation that forked
  it. There is no executor in the engine, so nothing to size, drain, or leak work into.
- **No `synchronized` anywhere in the main code.** The simulation thread blocks only in
  `java.util.concurrent` primitives — `take()` waits on a `ReentrantLock` condition,
  `scope.join()` parks through `LockSupport` — which unmount a virtual thread without
  pinning, even on JDK 21. JFR found zero pins in the engine; JDK 25's richer event recorded
  one 12.8 µs pin inside the JDK's own class loading (see the README's JVM tuning section).
- **Parallelism is a work-distribution choice.** The resulting board is identical at
  every partition count; `PartitionEquivalenceTest` pins that property.

## Measurement tooling (no code impact)

JEP 509 / 518 / 520 (JDK 25) add JFR events for CPU-time profiling of virtual
threads, cooperative thread sampling, and method-level timing. These are optional
measurement tooling; nothing in the codebase depends on them.
