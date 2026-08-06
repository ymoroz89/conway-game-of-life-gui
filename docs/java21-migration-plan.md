# Java 21 Migration Plan

Tracking document for migrating the repo to Java 21, focused on code simplicity,
concurrency (virtual threads where they genuinely help), JMM best practices, and GC
optimizations.

## Status

| Phase | Description | Status |
| --- | --- | --- |
| 1 | Build migration to Java 21 + baseline verification | ✅ Done |
| 2 | JMM: single volatile publication point (`EngineSnapshot`) | ✅ Done |
| 3 | Code simplicity: pattern-matching switch, `Math.clamp`, try-with-resources | ✅ Done |
| 4 | Concurrency: virtual threads (actor thread + band-worker A/B) | ✅ Done |
| 5 | GC optimizations: JVM flags documentation + JFR verification | ✅ Done |
| 6 | Docs, full test suite, benchmark + GUI smoke run | ✅ Done |

---

## Phase 1 — Build migration to Java 21

**Goal.** Move `maven.compiler.release` from 17 to 21 and verify the baseline is green
before any behavioral change.

### Steps
- [x] `pom.xml`: `maven.compiler.release=17 → 21`
- [x] `mvn test` passes — 125 tests, 0 failures
- [x] `mvn clean package` produces the shaded jar (`target/GuiLife.jar`, ~1.07 MB)
- [x] Record `GenerationBenchmark` baseline numbers (201 board) for later A/B comparison

### Baseline (Java 21, 201×201, 300 generations, best of 5, 10 cores)
`1: 12666.3` · `2: 17005.0` · `4: 16975.6` · `8: 17770.1` · `16: 20456.0` · `20: 18083.0`

### Notes
- No preview features. Structured Concurrency is preview in 21; we use stable virtual
  threads only.
- Dependencies (slf4j 2.0.x, logback 1.5.x, JUnit 5.11.x) are all Java 21-ready.

---

## Phase 2 — JMM best practice: one volatile publication point

**Goal.** Today `current`, `stats`, and `state` are three separate `volatile` fields
written in sequence. A reader can observe a new generation with stale stats/state.
Collapse them into one immutable snapshot published through a single `volatile` field.

### Steps
- [x] Introduce immutable `EngineSnapshot` record: `(Generation, SimulationStats)`
- [x] Replace the three volatile fields with one `volatile EngineSnapshot snapshot`
- [x] `currentGeneration()`, `stats()`, `state()` become delegates over the snapshot
- [x] `LifeFrame.refresh()` reads one volatile instead of three
- [x] All existing tests still pass (public API unchanged)

### Rationale
Related state observed as one atomically-visible bundle is the classic safe-publication
idiom. The renderer can never see a new board with stale stats.

---

## Phase 3 — Code simplicity

**Goal.** Apply Java 21 language features where they genuinely simplify.

### Steps
- [x] `SimulationEngine.handle()`: replace the 9-way `instanceof` chain with an
      exhaustive pattern-matching switch over the sealed `Command` types
- [x] Use `Math.clamp` (new in 21) in `setPartitions`, `LifeRules.bandsFor`,
      `PartitionPlanner.recommend`
- [x] Use `ExecutorService` as `AutoCloseable` (19+) → try-with-resources in
      `GenerationBenchmark` and `PartitionEquivalenceTest`, deleting manual
      `shutdown/awaitTermination` blocks
- [x] Replace `NamedThreadFactory` + `new Thread(...)` + `setDaemon` with
      `Thread.ofPlatform()/ofVirtual()` builders

---

## Phase 4 — Concurrency: virtual threads

**Goal.** Convert the simulation actor thread to a virtual thread (zero-risk, it blocks
in `take()`/`Future.get()`). For band workers, run an A/B benchmark and adopt the winner.

### Steps
- [x] Convert the simulation thread to a virtual thread
- [x] Implement a virtual-thread-per-task `LifeRules` variant
- [x] Run `GenerationBenchmark` head-to-head: platform pool vs
      `newVirtualThreadPerTaskExecutor`, interleaved A/B
- [x] **If parity/better:** adopt virtual threads per band; delete the entire
      pool-sizing apparatus (`ThreadPoolExecutor`, `setCorePoolSize`, keepalive,
      `allowCoreThreadTimeOut`); replace the three pool-sizing tests with simpler
      invariants
- [ ] **If slower:** keep the platform pool; document the measurement-driven rationale
      in Javadoc + docs; still convert the simulation thread

### A/B result (201×201, 300 generations, best of 5, 10 cores)
Virtual threads measured at parity or better at every partition count, in both
interleaved rounds. Round 2: 8→18676 vs 16957, 16→19481 vs 18288, 20→21992 vs 18746.
Single-threaded is identical (runs inline). **Adopted virtual threads per band.**

### Notes
- The profiling docs already prove ~4.5% dispatch is the only removable cost for bands.
- Correctness across partition counts is pinned by `PartitionEquivalenceTest`.

---

## Phase 5 — GC optimizations

**Goal.** The code-level allocation story was already measured and correctly concluded
"don't touch" (buffer reuse breaks the immutable-snapshot contract `BoardView` relies
on). Instead, document JVM flags and verify GC stays negligible.

### Steps
- [x] Document recommended JVM flags in README (bounded `-Xms/-Xmx`; G1 pause target;
      optional `-XX:+UseZGC` for the interactive GUI)
- [x] Short JFR run to verify GC stays negligible post-migration
- [x] Verify no virtual-thread pinning appears

### JFR result (20 s, virtual executor, 201×201)
Max single GC pause ~1.58 ms; total a few ms over the run. **Zero** virtual-thread
pinning events.

---

## Phase 6 — Docs and verification

### Steps
- [x] Update README threading/design sections
- [x] Add `docs/java21-migration.md` with rationale, A/B benchmark results, GC/JMM notes
- [x] Full `mvn test` — 123 tests, 0 failures
- [x] `mvn clean package` — shaded jar builds
- [x] Re-run `GenerationBenchmark` (201 board) — virtual executor, 1.53× at 16 partitions
- [x] GUI smoke launch — app starts, simulation thread runs, GUI renders, run starts

---

## Tests affected

- Replace `workerPoolIsBoundedByPartitionCount`, `workerPoolGrowsWithPartitionCount`,
  `corePoolSizeTracksPartitionCount` (they pin `ThreadPoolExecutor` internals) with
  tests matching the new model.
- Tests that inject an `ExecutorService` (`LifeRulesTest`, `LifeRulesBandingTest`,
  `PartitionEquivalenceTest`) switch to a virtual-thread-per-task executor where that
  is the adopted path.