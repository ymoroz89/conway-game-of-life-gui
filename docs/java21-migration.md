# Java 21 Migration

What changed in the move from Java 17 to Java 21, and why. The focus was code
simplicity, concurrency (virtual threads where they genuinely help), JMM best
practices, and GC optimizations.

> **Historical record.** This describes the code as the migration left it (2026-08-06,
> with the band-failure hardening of 2026-08-22). Since then:
>
> - **Band workers.** On 2026-09-24 they moved from virtual threads to a dedicated
>   `ForkJoinPool` with parallelism `min(maxPartitions, cores)`, ~~measured 12–19% faster at
>   201×201 with four partitions~~; see the
>   [concurrency features review](concurrency-features-review.md). The simulation thread is
>   still a virtual thread. **Corrected 2026-10-01:** the 12–19% came from
>   `GenerationBenchmark`, whose caller is a platform thread; in the application the
>   `ForkJoinPool` was 23% slower, and the band workers are virtual threads again, as this
>   document describes ([evidence](../README.md#what-the-application-measured)).
> - **Code layout.** `LifeRules` moved to `com.conway.engine.rules` the same day. The
>   per-band kernel is now `BandKernel`, `bandsFor` is `PartitionPlanner.bandsFor`, and
>   `Thread.ofPlatform()` is no longer used anywhere.
> - **GC figures.** Longer runs in [`jvm-options.md`](jvm-options.md) put G1's worst pause on
>   the default board at 7.28 ms (median 0.51 ms), not the ~1.58 ms of the 20-second
>   recording below.
> - **Structured Concurrency** was still a preview API in JDK 25 (JEP 505).
> - **Build target.** On `main`, `maven.compiler.release` is 25 and JDK 25 is the minimum;
>   see the [Java 25 migration](java25-migration.md). The Java 25 work was merged into `main`
>   on 2026-10-01; the last JDK 21 release, with every change listed above, is the tag
>   `java-21`.

## Build

`maven.compiler.release` moved from 17 to 21. No preview features are used:
Structured Concurrency is preview in 21, so only stable virtual threads were adopted.
Dependencies (slf4j 2.0.x, logback 1.5.x, JUnit 5.11.x) were already Java 21-ready.

## JMM: one volatile publication point

**Before.** `SimulationEngine` published three separate `volatile` fields — `current`,
`stats`, and `state` — written in sequence. A reader could observe a new generation
with stale statistics.

**After.** The board and its statistics are bundled into one immutable
`EngineSnapshot` record and published through a single `volatile` field. A reader
always sees generation N or N+1 in full, with the throughput numbers that belong to
it. This is the classic safe-publication idiom: related state observed as one
atomically-visible bundle.

`state` stays a separate volatile because it is written by two threads (the
simulation thread and `close()` on the caller's thread); bundling it would let a
concurrent close overwrite a newer generation. Momentary skew between a low-frequency
latch and the board is benign; the incoherence that matters — generation number versus
its population — is what `EngineSnapshot` eliminates.

## Code simplicity

- **Pattern-matching switch.** `SimulationEngine.handle()`'s nine-way `instanceof`
  chain became an exhaustive pattern-matching switch over the sealed `Command` types.
- **`Math.clamp`** (new in 21) replaced `Math.max(1, Math.min(...))` in
  `setPartitions`, `LifeRules.bandsFor`, and `PartitionPlanner.recommend`. One
  subtlety: `Math.clamp` requires `min <= max`, so `bandsFor` floors the useful-band
  count to 1 before clamping (a region too small to fill one band would otherwise
  throw).
- **`ExecutorService` as `AutoCloseable`** (19+) replaced manual
  `shutdown`/`awaitTermination` blocks in `GenerationBenchmark` and
  `PartitionEquivalenceTest`.
- **`Thread.ofVirtual()`/`ofPlatform()` builders** replaced `NamedThreadFactory` +
  `new Thread(...)` + `setDaemon`. `NamedThreadFactory` was deleted.

## Concurrency: virtual threads

The migration moved both the simulation actor thread and the band workers onto virtual
threads.

### Simulation actor thread

The simulation thread spends its life blocked in `take()`/`Future.get()` and parks
between generations. A virtual thread unmounts from any carrier while blocked and
costs nothing while idle — the ideal fit for a coordinator that blocks a lot. Virtual
threads are daemon by default, so the JVM can still exit if a shutdown path is missed.

### Band workers: measured, not assumed

The band workers are pure CPU, which is the case where virtual threads are *not*
automatically better than a platform pool. So the decision was made by measurement,
not by fiat. `GenerationBenchmark` gained a `platform`/`virtual` executor-mode
argument, and the two were compared head-to-head, interleaved, on the 201×201 board:

| Partitions | Platform (round 1) | Virtual (round 1) | Platform (round 2) | Virtual (round 2) |
| --- | --- | --- | --- | --- |
| 1 | 12619 | 12643 | 12414 | 12232 |
| 2 | 17067 | 16934 | 17128 | 17561 |
| 4 | 15672 | 16211 | 16497 | 16324 |
| 8 | 16154 | 18575 | 16957 | 18676 |
| 16 | 18067 | 18210 | 18288 | 19481 |
| 20 | 18185 | 19196 | 18746 | 21992 |

Virtual threads measured at parity or better at every partition count, in both
rounds. Single-threaded is identical because a single band runs inline and never
touches the executor.

**Adopted virtual threads per band.** This deleted the entire pool-sizing apparatus:
`ThreadPoolExecutor`, `setCorePoolSize`, keepalive, `allowCoreThreadTimeOut`, and the
unbounded-accumulation bug class that P1-3 in `profiling-findings.md` documented. The
three pool-sizing tests that pinned `ThreadPoolExecutor` internals were replaced by a
single test confirming partition counts are honored and the engine does not fail.

## GC optimizations

The code-level allocation story was already measured and correctly concluded "don't
touch": two `boolean[]` per generation (~1.5 GB/s at full throughput) are absorbed by
G1 with negligible pause, and buffer reuse would break the immutable-snapshot contract
`BoardView` relies on. So the migration did not change the allocation profile.

Instead, the README documented recommended JVM flags (bounded `-Xms/-Xmx`, a G1
pause target, and optional ZGC for very large boards; they now live in
[`jvm-options.md`](jvm-options.md)), and a 20-second JFR recording
against the virtual-thread benchmark confirmed:

- **GC stays negligible.** Max single pause ~1.58 ms; total a few ms over the run.
- **No virtual-thread pinning.** Zero `jdk.VirtualThreadPinned` events. The band
  workers never enter a `synchronized` block or native call that would pin a carrier.

## Band-failure hardening

A post-migration review of the Java ≤21 concurrency toolbox
([`concurrency-features-review.md`](concurrency-features-review.md)) found one
robustness gap. When a band failed, `LifeRules.next()` abandoned the survivors:
they were dispatched, then ran to completion *unseen* while the caller unwound with
an error. Band futures are now canceled on any failure — whether the fault hit
band zero on the calling thread or surfaced as `ExecutionException` from a worker —
so a failed generation stops scheduling new work. The cancellation is hygiene
rather than a hard stop: band computation never blocks, so the interrupt flag
cannot cut it short, and the success path is untouched, so there is no performance
cost.

The Java 19/21 `Future` accessors (`state()`, `resultNow()`, `exceptionNow()`) were
considered for the join loop and deliberately not adopted: none of them waits, so
`get()` remains the only tool that joins a running band while preserving the
`InterruptedException` contract. The per-feature disposition of the whole toolbox —
including why a `RecursiveTask` decomposition and a `Phaser` band redesign stay
rejected — is recorded in the review document. (A `ForkJoinPool` was later adopted as
the executor behind the unchanged band model, which is a different change, on 2026-09-24;
it was reverted on 2026-10-01.) Structured
Concurrency stayed preview-gated in 21 and is tracked there; it was still in preview in
JDK 25.

## Verification

- `mvn test`: 124 tests, 0 failures (123 after the migration; the review added one
  band-failure test).
- `mvn clean package`: shaded jar builds.
- `GenerationBenchmark` re-run on the 201×201 board with the virtual executor.
- GUI smoke launch.