# Java 25 Migration

What changes in the move from Java 21 to Java 25, and why. The focus stays where
the [Java 21 migration](java21-migration.md) put it: code simplicity,
concurrency (virtual threads where they genuinely help), JMM best practices,
and GC optimizations.

> **Status.** Completed 2026-08. The checks below are ticked; measurements were
> re-baselined on JDK 25 (see [`java25-migration-plan.md`](java25-migration-plan.md)
> Phase 2 for the full table). Tracking lives alongside this document.

## Build

`maven.compiler.release` moves from 21 to 25. No preview features are used:
Structured Concurrency is *still* preview in 25 (its fifth preview, JEP 505; a
sixth, JEP 525, is already filed for JDK 26), so the policy of the previous
migration — adopt only stable features — decides unchanged.

The CI matrix drops the JDK 17 job. It had been broken since `release=21`:
`mvn` ran on a JDK 17 toolchain while `javac` mandated `--release 21`. The
matrix becomes `["21", "25"]`, and the scheduled dependency scan pins JDK 25.

## Concurrency: what JDK 25 changes for this codebase

### Virtual threads — unchanged

Virtual threads were finalised in JDK 21 (JEP 444) and are untouched by JDK 25.
The simulation actor thread and the band workers stay on virtual threads. This
migration is toolchain and docs, not a rewrite: nothing about the threading
model changes.

### `synchronized` can no longer pin a carrier (JEP 491, JDK 24)

JEP 491 arranges for a virtual thread blocked inside a `synchronized` block or
method to release its carrier instead of pinning it. This codebase already
measured zero `VirtualThreadPinned` events in the Java 21 JFR recording, and
the design is lock-free by construction, so the change appears only in the
docs: the caveat that writing `synchronized` on a hot path could pin a carrier
is retired, and the corresponding row in
[`concurrency-features-review.md`](concurrency-features-review.md) records the
feature as context rather than as a constraint.

### Scoped Values are final (JEP 506, JDK 25)

Scoped values finalised in JDK 25 after incubation (JEP 429) and four previews
(446, 464, 481, 487), with one small change — `ScopedValue.orElse` no longer
accepts `null`. The codebase has no `ThreadLocal`, so there is nothing to
replace; the disposition stays "not applicable" but the reason is now "final
feature, nothing to use it on" rather than "preview-gated".

### Structured Concurrency — still not final (JEP 505, JDK 25)

`LifeRules.next()` is the textbook use case for `StructuredTaskScope`: it
forks bands, joins them, and hand-rolls `cancelAll` cleanup so a failed band
does not leave stragglers running unseen. But JEP 505 is only the *fifth*
preview, the API keeps moving (subtasks replace `Future`; scopes now open via
static `open()` factories), and a sixth preview (JEP 525) is already in the
pipeline for JDK 26. Under this repo's no-preview policy it stays out, and the
plan records the exact adoption shape — `StructuredTaskScope.open()`, band 0
still computed on the calling thread, the two catch paths deleted — so the
decision can be made on evidence when the API finally settles rather than
re-derived from scratch. The counterweight on upside is unchanged: the
`ForkJoinPool`/`Phaser` analysis already bounded the removable cost at a few
percent.

### Stable Values — preview only (JEP 502, JDK 25)

Stable values (immutable data the JVM treats as constants, initialisable later
than a `final` field) preview in 25. The codebase has no lazily-initialised
constants a `stable` would speed up; not applicable.

## GC and JVM flags

JDK 24 removed the non-generational ZGC (JEP 490); JDK 25 adds Generational
Shenandoah (JEP 521), a second sub-millisecond-pause collector for large
boards. The README's JVM-flag line stays G1-first with the optional ZGC
alternative, and the option is worded against 25's collector set.

## Docs

`docs/concurrency-features-review.md` is re-scoped from "Java ≤ 21" to
"Java ≤ 25": the pinning caveat retires, scoped values move from
preview/N/A to final/N/A, structured concurrency is re-recorded as fifth
preview with the adoption shape deferred to the plan, and stable values get an
N/A row. The JMM reasoning that matters is unchanged: one volatile
publication point, one writer, many readers, zero locks.

## Verification

- [x] `mvn test` on JDK 25 — 124 tests, 0 failures
- [x] `mvn clean package` — shaded jar builds
- [x] `GenerationBenchmark` re-run on the 201 board; JDK 25 curve recorded
      next to the Java 21 baseline
- [x] JFR: GC negligible (max G1 pause ~1.69 ms), zero
      virtual-thread-pinning events
- [x] GUI smoke launch — app starts, run starts, exits on window close

### Measured on JDK 25 (best of 5 × 300 generations, virtual executor, 10 cores)

| Board | 1 | 2 | 4 | 8 | 16 | 20 |
| --- | --- | --- | --- | --- | --- | --- |
| 201 | 12,440 | 17,174 | 17,187 | 18,411 | **19,681** | 19,605 |
| 401 | 3,659 | 6,293 | 9,096 | 9,287 | **9,304** | 9,297 |
| 801 | 957 | 1,778 | 3,171 | 3,145 | **3,793** | 3,761 |

The curve is within a few percent of the Java 21 baseline at every partition
count, and the shape is unchanged: parallelism pays by the *board*, the optimum
sits near fifty rows per band, and the engine still runs two orders of magnitude
faster than the 60 fps display. Nothing about the threading model needed to
change for JDK 25 — the migration is toolchain and docs.