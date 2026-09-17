# Java 27 Migration

What changes in the move from Java 25 to Java 27, and why. The focus stays
where the previous migrations put it: code simplicity, concurrency (virtual
threads where they genuinely help), JMM best practices, and GC optimizations.

> **Status.** Completed 2026-09-15, the day JDK 27 went GA. Unlike the previous
> two migrations this one is **not** toolchain-and-docs only: it adopts two
> preview APIs, which pins the build to exactly JDK 27 and adds
> `--enable-preview` to every launch command. Everything was verified on the
> shipped JDK (Azul Zulu 27.28+101, `openjdk version "27" 2026-09-15`), not
> against the JEP text.

This line went 25 → 27 directly. JDK 26 was a six-month feature release whose
only item that touched this codebase was JEP 525 (structured concurrency, sixth
preview); the `java26` branch records that adoption separately, and what the
seventh preview changed relative to it is in
[`structured-concurrency.md`](structured-concurrency.md).

## The release

JDK 27 ships nine JEPs. Four are final, four are previews, one is an incubator.
The disposition for this codebase:

| JEP | Title | Status | Disposition |
| --- | --- | --- | --- |
| 533 | Structured Concurrency | 7th preview | ✅ **Adopted** — `LifeRules` runs every generation in one scope. [Details](structured-concurrency.md) |
| 531 | Lazy Constants | 3rd preview | ✅ **Adopted** — `PatternLoader.bundledGliderGun()` |
| 534 | Compact Object Headers by Default | Final | ✅ Inherited; measured as no-change here. Flag removed from docs |
| 523 | Make G1 the Default GC in All Environments | Final | ✅ Inherited; `-XX:+UseG1GC` is now redundant |
| 536 | JFR In-Process Data Redaction | Final | ➖ Available to the profiling workflow; nothing to redact today |
| 537 | Vector API | 12th incubator | ❌ Declined — [reason](#vector-api-jep-537--declined) |
| 532 | Primitive Types in Patterns | 5th preview | ❌ N/A — no pattern match in the codebase switches on a primitive |
| 527 | Post-Quantum Hybrid Key Exchange for TLS 1.3 | Final | ❌ N/A — no TLS; the application opens no sockets |
| 538 | PEM Encodings of Cryptographic Objects | 3rd preview | ❌ N/A — no cryptographic objects |

## Build

`maven.compiler.release` moves from 25 to 27, and the compiler plugin gains
`<enablePreview>true</enablePreview>` with `-Xlint:-preview` (every
`StructuredTaskScope` and `LazyConstant` call site would otherwise warn; the
dependency is recorded in the pom comment and here instead). Surefire gains
`<argLine>--enable-preview</argLine>`.

Consequences, all deliberate:

- **the build requires exactly JDK 27** — preview class files carry minor
  version `65535` and refuse to load on any other release, older or newer;
- **every launch command carries `--enable-preview`**, because the flag cannot be
  recorded in the jar manifest. The README's run, flags and benchmark commands
  were all updated. The failure mode without it is legible:
  `UnsupportedClassVersionError: Preview features are not enabled for
  com/conway/model/PatternLoader (class file version 71.65535)`.

One XML gotcha worth recording: an XML comment cannot contain `--`, so
`--enable-preview` cannot be written literally in a pom comment. Maven fails
with `Non-parseable POM … in comment after two dashes (--) next character must
be >`.

### Toolchain availability

JDK 27 went GA on 2026-09-15 and Homebrew's `openjdk` formula was still at
26.0.2.1 that day. Two casks already carried 27: `zulu` (27.0.0, a plain
OpenJDK build) and `oracle-jdk` (27). This migration used
`brew install --cask zulu`, registered with jenv:

```bash
jenv add /Library/Java/JavaVirtualMachines/zulu-27.jdk/Contents/Home
jenv local zulu64-27
```

Note the vendor change from the Homebrew `openjdk` builds used for the JDK 21
and 25 profiling arms. It does not affect any number in this document — every
comparison here was run on Zulu 27 against Zulu 27 — but a cross-JDK comparison
against `PROFILING-LOG.md`'s figures would be confounded by it. Switch to the
Homebrew formula once it catches up if that comparison is ever wanted.

## Concurrency: what JDK 27 changes for this codebase

### Structured Concurrency (JEP 533) — adopted

The standing "not until it finalises" policy was reversed. `LifeRules.next()`
now opens one `StructuredTaskScope` per generation, forks bands 1..N−1 into it,
computes band zero on the calling thread, and joins. `cancelAll` and both
`ExecutionException` catch paths are deleted; `SimulationEngine` owns no
executor at all and hands `LifeRules` a `ThreadFactory` instead. No
`ExecutorService` or `Future` remains anywhere in the codebase.

The seventh preview's new exception-supplying joiner overload is what makes the
call site read well:

```java
Joiner.<BandResult, SimulationException>allSuccessfulOrThrow(
        cause -> new SimulationException(FAILURE_MESSAGE, cause))
```

A band failure therefore reaches `join()` already wrapped as the exception the
engine's `State.FAILED` / `onError` path expects. On JDK 25 and 26 the joiner
threw a fixed `FailedException` that the call site had to catch and unwrap;
`FailedException` no longer exists in JDK 27.

Measured at **parity** on the 201 and 401 boards against the pre-port build
recompiled on the same JVM — full tables, the control row, and the failure
semantics are in [`structured-concurrency.md`](structured-concurrency.md).

### Lazy Constants (JEP 531) — adopted

JEP 531 is the third preview of the line that was `StableValue` in JDK 25
(JEP 502) and JDK 26 (JEP 526). The Java 25 migration recorded it as N/A —
"no lazily-initialised constants to speed up" — which was true of the code as it
stood, but `PatternLoader.fromResource` re-read and re-parsed the bundled
Gosper glider gun on every call, once from `SimulationSeeder` and once from
`GenerationBenchmark`. That is now a lazy constant:

```java
private static final LazyConstant<Pattern> BUNDLED_GLIDER_GUN =
        LazyConstant.of(() -> fromResource(GOSPER_GLIDER_GUN));
```

Honest accounting of what this buys: **the idiom, not throughput.** The resource
is a few hundred bytes parsed once or twice per JVM, so there was no measurable
cost to remove. What changes is that at-most-once initialisation now needs no
holder class and no double-checked lock, the field cannot be observed
half-built, and the JIT may treat the value as a genuine constant once set —
which a lazily assigned `static Pattern` field can never be. `Pattern` is
immutable and its cell array never escapes, so sharing one instance is safe for
any number of threads.

Two API details worth knowing, both verified on the shipped JDK:

- the accessor is `get()`, and the class is `java.lang.LazyConstant` — no
  import needed. The JDK 25/26 `StableValue` methods `orElseSet`, `trySet`,
  `setOrThrow`, `isInitialized` and `orElse` are all gone; `of(Supplier)` plus
  `get()` is the whole surface, alongside `List.ofLazy` / `Map.ofLazy` /
  `Set.ofLazy` for collections;
- `null` is not allowed, and a computing function that throws leaves the
  constant uninitialised and surfaces as `NoSuchElementException` wrapping the
  original. `bundledGliderGun()` unwraps that, so a missing or malformed
  resource still throws exactly what `fromResource` throws. Pinned by
  `PatternLoaderTest`.

### Everything else — unchanged

The actor loop, the single `volatile EngineSnapshot` publication point, the
band partitioning rule and the virtual-thread choices are untouched by JDK 27.
`ScopedValue` stays N/A (no `ThreadLocal`s to replace), and the per-feature
table in [`concurrency-features-review.md`](concurrency-features-review.md) was
re-scoped to "Java ≤ 27" with the executor rows retired.

## GC and runtime defaults

Both of JDK 27's final HotSpot JEPs *remove* flags rather than add them.

### G1 everywhere (JEP 523)

G1 is now the default collector in every environment, including the
small-machine and small-container cases that previously fell back to Serial GC.
Confirmed on the shipped JDK:

```console
$ java -XX:ActiveProcessorCount=1 -Xmx512m -XX:+PrintFlagsFinal -version | grep UseG1GC
     bool UseG1GC = true    {product} {ergonomic}
```

Before JDK 27 that configuration selected Serial GC. For this application it
means the README's pause figures no longer depend on the machine being
classified as a server, and `-XX:+UseG1GC` — which the profiling commands passed
explicitly — is redundant. The sub-millisecond-pause alternatives for very large
boards (`-XX:+UseZGC`, `-XX:+UseShenandoahGC`) are unaffected.

### Compact object headers by default (JEP 534)

Object headers shrink from 96 to 64 bits and the flag is on by default:

```console
$ java -XX:+PrintFlagsFinal -version | grep UseCompactObjectHeaders
     bool UseCompactObjectHeaders = true    {product lp64_product} {default}
```

`-XX:+UseCompactObjectHeaders` is therefore redundant, and
`-XX:-UseCompactObjectHeaders` — itself slated for deprecation and removal — is
the only remaining reason to name the flag. `UseCompressedClassPointers` is now
obsolete; the JVM always compresses class pointers.

**Measured effect here: none resolvable.** The JEP reports 10–20% smaller heaps
and 5–10% more throughput for applications holding millions of small objects.
This one holds two large `boolean[]` per generation, where an 8-byte saving per
*object* is nothing against 40 KB of cells, so no effect was expected. Five
interleaved rounds of `GenerationBenchmark` (201×201, 5000 generations, best of
5, headers on vs off on the same JVM and jar) agree:

| Partitions | Headers on | Headers off | Δ |
| --- | --- | --- | --- |
| 1 | 13,122 | 12,954 | +1.3% |
| 2 | 18,377 | 18,236 | +0.8% |
| 4 | 17,607 | 17,598 | +0.1% |
| 8 | 17,611 | 17,780 | −0.9% |
| 16 | 17,399 | 17,700 | −1.7% |
| 20 | 17,606 | 17,700 | −0.5% |

The sign changes with partition count and every delta is inside the session's
own drift: the headers-off 1-partition slot climbed monotonically from 12,711 to
13,189 across the five rounds, a 3.8% spread on an unchanged configuration.

**A near-miss worth recording.** The first two rounds alone showed a consistent
+3.1% for compact headers at 1 partition and +3.2% at 2, in the same direction
both times — a tidy result that would have gone into this document as a real
win. It did not survive rounds 3–5. Two rounds is not a measurement; the
[201-board artifact](structured-concurrency.md#a-measurement-artifact-worth-recording)
recorded during the JDK 26 adoption was the same lesson.

### JFR in-process data redaction (JEP 536)

New `-XX:FlightRecorderOptions:redact-key` and `redact-argument` options strip
sensitive values from a recording as it is written, and `jdk.SystemProcess` no
longer emits process command-line arguments at all. Relevant to the profiling
workflow in `PROFILING-LOG.md` only as a capability: this application's
recordings carry board sizes and partition counts, so there is nothing to
redact. Worth knowing before a recording from a machine with credentials in its
command lines is ever shared.

One JFR behaviour change to be aware of when comparing recordings across JDKs:
`jdk.OldObjectSample` is now disabled under generational ZGC.

## Considered and declined

### Vector API (JEP 537) — declined

The neighbour-count loop in `computeBand` is the one place in this codebase
where explicit SIMD could plausibly pay: eight loads and an add per cell, over a
contiguous row. It is declined for this migration, for reasons that are
structural rather than a guess about speed:

- **The board is `boolean[]`.** There is no `BooleanVector`, and a `ByteVector`
  cannot be loaded from a `boolean[]`. Vectorising means changing the board's
  representation to `byte[]` or a `MemorySegment`, which touches `Generation`,
  `BoardView`'s blit, the pattern model and every test — far past the blast
  radius of a JDK upgrade.
- **It is still an incubator module** after twelve rounds, so it would need
  `--add-modules jdk.incubator.vector` on top of `--enable-preview`, with no
  release in sight (the JEP's own stated gate is Valhalla).
- **The scalar loop is measured and tuned.** `profiling-issues-fixing-progress.md`
  records three narrowing attempts on the ever-alive pass that each cost 2–5% on
  a dense board; the current shape is deliberate. Replacing it belongs in its
  own change, with its own before/after, not bundled into a release bump.

Recorded here so the option is not re-derived: the prerequisite is a `byte[]` or
`MemorySegment` board, and that is the decision to make first.

### Primitive types in patterns (JEP 532) — N/A

The codebase's pattern matching is the `sealed interface Command` switch in
`SimulationEngine`, which matches record types, not primitives. Nothing to
convert.

## Docs

- [`structured-concurrency.md`](structured-concurrency.md) — new on this branch:
  the adoption, the seventh preview's API delta, failure semantics, and the
  before/after measurements.
- [`concurrency-features-review.md`](concurrency-features-review.md) — re-scoped
  to "Java ≤ 27". `StructuredTaskScope`, the exception-supplying joiner and
  `LazyConstant` move into the in-use table; the `ExecutorService` /
  `Future.get()` rows are retired; `Joiner.allUntil` and
  `Configuration.withTimeout` are recorded as N/A with reasons.
- [`README.md`](../README.md) — JDK requirement is now "exactly 27", every
  launch command carries `--enable-preview`, and the JVM-flags section gains the
  two JDK 27 defaults.
- The Java 25 documents keep their original text; their forward references to
  the structured-concurrency reversal now name the release it landed in on this
  line.

[`PROFILING-LOG.md`](../PROFILING-LOG.md) gained two JDK 27 rounds, run after this
migration landed:

- **Round 4** (headless) — the scope costs **1,137 B per generation**, the shipped engine
  uses **5.2% less CPU per generation**, and throughput is at parity once the compile
  target is held constant. It also found G1's young-gen sizing to be bimodal per JVM
  start in that environment, which invalidates single-recording heap comparisons.
- **Round 5** (GUI, repeating round 3's matched-generation design) — the 21 → 25 G1
  sizing trend **continues into 27**: committed heap 620 → 300 → 262 MiB and collections
  440 → 855 → 1,052, with throughput and bytes per generation flat. The scope's
  per-generation cost replicates here at **+1,154 B**, within 17 bytes of the headless
  figure.

Both carry the vendor caveat from the toolchain section: Zulu 27 against Homebrew
OpenJDK 21/25, so throughput differences under ~6% between rounds mean nothing, while the
heap and GC rows are policy and do carry. Round 3's own table was re-derived from its
snapshots in the process, correcting Java 25's pause max from 9.96 ms to 3.66 ms.

## Verification

- [x] `mvn -B clean test` on JDK 27 — **126 tests, 0 failures**
- [x] `mvn clean package` — shaded jar builds (1.07 MB)
- [x] `javap` on the shipped JDK to confirm the `StructuredTaskScope`, `Joiner`
      and `LazyConstant` signatures before writing against them
- [x] `GenerationBenchmark` re-run on the 201 and 401 boards, pre-port vs
      post-port, interleaved, both recompiled at `release=27`
- [x] Compact-object-headers on/off comparison, five interleaved rounds
- [x] Default-GC ergonomics checked at 1 CPU / 512 MB — G1, not Serial
- [x] JFR over a 401×3000 run: zero `jdk.VirtualThreadPinned` events, max G1
      pause 2.25 ms
- [x] Launch without `--enable-preview` fails with the flag named in the message
- [x] GUI soak from the shaded jar — 239,704 generations at 401×401/8 partitions,
      a live board-size change to 201, then 2,052,730 generations at
      201×201/4 partitions (~21,500 scopes per second for 95 s), plus steps,
      clear and quit; no error and no shutdown warning

## Known follow-ups

- **The `security` profile is missing from `pom.xml`.** The README documents
  `mvn -Psecurity verify` and the pom still carries the
  `dependency-check-maven.version` property, but the profile itself was removed
  in `d65c9b5` along with 30 other pom lines. Predates this migration and is
  left as found; either restore the profile or drop the README section and the
  property.
