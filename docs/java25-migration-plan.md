# Java 25 Migration Plan

Tracking document for migrating the repo from Java 21 to Java 25, focused on
the same axes as the
[Java 21 migration](java21-migration-plan.md): code simplicity, concurrency
(virtual threads where they genuinely help, structured concurrency where it
*actually* finalises — and it has not, even by 25), JMM best practices, and GC
optimizations.

Companion documents: [`java25-migration.md`](java25-migration.md) (what changes
and why), [`java21-migration.md`](java21-migration.md) (the previous
migration), [`concurrency-features-review.md`](concurrency-features-review.md)
(the per-feature disposition for Java ≤25).

## Status

| Phase | Description | Status |
| --- | --- | --- |
| 1 | Toolchain migration: `release` 21 → 25, CI matrix, README JVM flags | ✅ Done |
| 2 | Re-baseline measurements on JDK 25 (benchmark + JFR) | ✅ Done |
| 3 | Concurrency review refresh: Java ≤25 feature set | ✅ Done |
| 4 | Structured Concurrency: decision on preview + recorded adoption shape | ✅ Done |
| 5 | Docs, full test suite, benchmark + GUI smoke run | ✅ Done |

---

## Phase 1 — Toolchain migration to Java 25

**Goal.** Make the repo build and verify on JDK 25 (the installed toolchain; an
LTS release) before any behavioural change, and make the CI matrix honest.

### Steps

- [x] `pom.xml`: `maven.compiler.release=21 -> 25`
- [x] `.github/workflows/build.yml`: matrix `["17", "21"]` → `["21", "25"]`;
      pin the scheduled dependency-check job to JDK 25
- [x] README: state JDK 25 as the build/runtime baseline; review the JVM-flag
      section against the JDK 24/25 GC changes (see Phase 1 notes)
- [x] `mvn -B verify` passes on JDK 25 — 124 tests, 0 failures

### Notes

- **The current CI matrix is already broken for the state the repo is in.** The
  `17` job runs `mvn` on a JDK 17 toolchain while the pom says `release=21`, so
  `javac` rejects the release. Nobody hit it because the practical gate has been
  the JDK 21 job. Replacing `17` with `25` makes the matrix honest.
- **JDK 25 GC changes relevant to the README:** JDK 24 removed the
  non-generational ZGC (JEP 490) and JDK 25 added Generational Shenandoah
  (JEP 521). On a very large board there are now two sub-millisecond-pause
  options (`-XX:+UseZGC`, `-XX:+UseShenandoahGC`); on the default board G1
  stays the right answer.

---

## Phase 2 — Re-baseline on JDK 25

**Goal.** Every throughput figure cited in the README, `PartitionPlanner` and
the Java 21 docs was measured on a JDK 21 toolchain. The migration must record
fresh numbers on 25 before any of them can be quoted again.

### Steps

- [x] `GenerationBenchmark` on the 201, 401 and 801 boards, virtual executor,
      best of 5 × 300 generations; record the curve
- [x] Compare against the Java 21 baseline below
- [x] 20-second JFR run on the virtual-thread path: max GC pause, and a
      `jdk.VirtualThreadPinned` check (expected: still zero)

### Java 21 baseline (201×201, best of 5, 10 cores) — for the A/B table

`1: 12666.3` · `2: 17005.0` · `4: 16975.6` · `8: 17770.1` · `16: 20456.0` · `20: 18083.0`

### Measured — JDK 25, virtual executor, best of 5 × 300, 10 cores

| Board | 1 | 2 | 4 | 8 | 16 | 20 | Best | vs Java 21 baseline (201) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 201 | 12,440 | 17,174 | 17,187 | 18,411 | **19,681** | 19,605 | ~parity at every count |
| 401 | 3,659 | 6,293 | 9,096 | 9,287 | **9,304** | 9,297 | — |
| 801 | 957 | 1,778 | 3,171 | 3,145 | **3,793** | 3,761 | — |

The shape is unchanged: the optimum sits at roughly fifty rows per band (more
bands stop helping once the active-region cap kicks in), and small boards gain
the least. JFR over the 401 run: max G1 pause **~1.69 ms**, **zero**
`jdk.VirtualThreadPinned` events.

### Notes

- JDK 24's JEP 491 removed the last common pinning source (`synchronized`).
  This codebase did not exercise it in the Java 21 JFR recording, so the
  expected result was a re-record, not a behaviour change — confirmed.

---

## Phase 3 — Concurrency review refresh (Java ≤ 25)

**Goal.** Bring `docs/concurrency-features-review.md` in line with what
JDK 21 → 25 actually shipped, and retire every disposition that is now stale.

### Steps

- [x] **JEP 491 — Synchronize without pinning (final, JDK 24).** New row:
      in use only as context; the lock-free-by-construction rationale survives,
      and the "write `synchronized` can pin a carrier" caveat is retired.
- [x] **JEP 506 — Scoped Values (final, JDK 25).** Row updated from
      "21 preview / N/A" to "final"; disposition stays N/A — there is no
      `ThreadLocal` in the codebase to replace.
- [x] **JEP 505 — Structured Concurrency (fifth preview, JDK 25).** Row
      updated; still preview, still out under the no-preview policy; note the
      sixth preview (JEP 525) is already filed for JDK 26.
- [x] **JEP 502 — Stable Values (preview, JDK 25).** New N/A row:
      no lazily-initialised constants to make stable.
- [x] JEP 509/518/520 (JFR CPU-time profiling, cooperative sampling, method
      timing) as an optional measurement-tooling note; no code impact.
- [x] Refresh the cross-referencing claims in the README and the Java 21 docs
      that the review doc is now the single source of truth for.

---

## Phase 4 — Structured Concurrency: decision and recorded adoption shape

> **Superseded 2026-08-24.** The decision below ("not adopted, still preview")
> was reversed and `StructuredTaskScope` was adopted on JDK 26. The adoption
> shape recorded here is exactly what shipped, so the phase is kept as written
> rather than rewritten. See [`structured-concurrency.md`](structured-concurrency.md).

**Goal.** Decide on paper — not in code — whether the fifth preview of
Structured Concurrency (JEP 505) changes anything for this codebase, and record
the exact shape an adoption would take so the decision does not have to be
re-derived from scratch when the feature actually finalises.

### Steps

- [x] Record the decision: **not adopted on 25** (still preview; API already
      moved once since 21 — `fork` returns a `Subtask`, scopes open via static
      `open()` factories — and a sixth preview exists for 26)
- [x] Record the adoption shape for the future: `LifeRules.next()` is the
      textbook case. The rewrite would be `StructuredTaskScope.open()`, fork
      bands 1..N−1, **keep band 0 on the calling thread** (preserving the
      measured caller-computes-a-band win), `join()`, and delete the
      hand-rolled `cancelAll` + both `ExecutionException` catch paths.
      Correctness stays pinned by `PartitionEquivalenceTest` and
      `LifeRulesTest.failingDispatchedBandSurfacesWrapped`.
- [x] Note the counterweight: the `ForkJoinPool`/`Phaser` analysis in
      `profiling-issues-fixing-progress.md` bounds the upside; the costs the
      scope removes are the same few percent as the `Phaser` option.

### Decision criteria (for when the API preview completes)

1. `StructuredTaskScope` is final in some future JDK that is the build target.
2. The scope-per-generation cost is re-measured, not assumed.
3. `PartitionEquivalenceTest` and the band-failure test stay green unchanged
   (or their replacements are agreed first).

---

## Phase 5 — Docs and verification

### Steps

- [x] `docs/java25-migration.md` written up from this plan with the A/B
      benchmark table filled in
- [x] Full `mvn test` on JDK 25 — 124 tests, 0 failures
- [x] `mvn clean package` — shaded jar builds
- [x] `GenerationBenchmark` re-run (201 board), figures recorded in the
      migration doc and the README where they supersede the Java 21 ones
- [x] GUI smoke launch on JDK 25 (app starts, run starts, toggle while
      running)

---

## Tests affected

- The CI matrix changes (Phase 1); `java.awt.headless=true` stays.
- No test pins a JDK version or threading internals (the pool-sizing tests were
  already replaced in the Java 21 migration), so **no test-source changes are
  expected** unless Phase 4 ships, which it does not.
- The benchmark keeps its `platform`/`virtual` comparator; the virtual mode is
  the adopted path and its numbers are the record.