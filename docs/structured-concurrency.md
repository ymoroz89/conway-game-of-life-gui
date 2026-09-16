# Structured Concurrency

What `LifeRules` looks like now that every generation runs inside a
`StructuredTaskScope`, why the standing "not until it finalises" decision was
reversed, and what the reversal costs.

> **Status.** Adopted 2026-09-15 on JDK 27. `StructuredTaskScope` is **JEP 533,
> the seventh preview**, so the build is pinned to exactly JDK 27. That trade is
> deliberate and is spelled out under [The price of preview](#the-price-of-preview).
>
> The same adoption was made on the `java26` branch against JEP 525 (sixth
> preview). This line went 25 → 27 directly, so the port landed here with the
> JDK 27 bump and against the seventh preview's API. What changed between the two
> is recorded under [What the seventh preview changed](#what-the-seventh-preview-changed).

Companion documents: [`java27-migration.md`](java27-migration.md) (the release as a
whole), [`concurrency-features-review.md`](concurrency-features-review.md)
(the per-feature disposition), [`java25-migration-plan.md`](java25-migration-plan.md)
Phase 4 (where the adoption shape was recorded before it was built), and
[`profiling-issues-fixing-progress.md`](profiling-issues-fixing-progress.md)
(the measurements that bound how much any of this can be worth).

## The decision that changed

Three migrations in a row recorded the same verdict: `LifeRules.next()` is the
textbook `StructuredTaskScope` case, but the API is preview, and the repo has a
no-preview policy, so it waits. Nothing about the *API* forced the reversal —
JEP 533 is a seventh preview and it is still not final. What changed is the
weighting: the structural guarantee is now judged worth the toolchain pin, on a
single-developer desktop application whose build target moves with the installed
JDK anyway.

This is a policy exception, made with open eyes, not a discovery that the
previous analysis was wrong. The earlier decision was correct under the earlier
constraint. Both are recorded so nobody has to re-derive either.

## What the scope buys

The band model is unchanged: a generation is split into contiguous row bands,
each writing to its own disjoint slice of the destination arrays, with band zero
computed on the calling thread so the simulation thread is not parked for the
whole generation. What changed is who owns the workers and what happens when one
of them fails.

**Before** — a long-lived `ExecutorService` owned by `SimulationEngine`, shared
with `LifeRules`, plus hand-rolled failure cleanup:

- bands were submitted to an executor whose lifetime was the engine's, so
  nothing in the type system said a band could not outlive the generation that
  submitted it. `SimulationEngine.close()` had to `close()` the pool to drain
  whatever was left;
- when a band failed, the siblings were cancelled by a hand-written `cancelAll`
  helper called from two separate `catch` blocks — one for the inline band's
  `RuntimeException`, one for `ExecutionException` from `Future.get()`, each
  cancelling a different sublist.

**After** — one scope per generation:

- every band is forked into a scope that is closed before `next()` returns.
  A band cannot outlive its generation; that is the guarantee, and it is
  structural rather than a convention;
- `allSuccessfulOrThrow` cancels the scope on the first band that throws, so the
  siblings are interrupted instead of running to completion unseen. `cancelAll`
  and both `ExecutionException` paths are gone;
- the joiner's **exception-supplying function** — new in the seventh preview —
  is handed the failed band's cause and returns the exception `join()` throws, so
  a band failure arrives already wrapped as the `SimulationException` the engine
  expects. There is no framework exception to unwrap;
- `SimulationEngine` owns no executor at all. It hands `LifeRules` a
  `ThreadFactory` (`Thread.ofVirtual().name("conway-band-", 0)`) and `close()`
  has nothing left to drain: a stopped simulation thread now *means* no band is
  running.

Net: roughly 30 lines of lifecycle and cleanup code deleted, and one class of
bug — "a band survived the thing that started it" — made unrepresentable. No
`ExecutorService` or `Future` remains anywhere in the codebase.

```java
try (StructuredTaskScope<BandResult, List<BandResult>, SimulationException> scope =
        StructuredTaskScope.open(
                Joiner.<BandResult, SimulationException>allSuccessfulOrThrow(
                        cause -> new SimulationException(FAILURE_MESSAGE, cause)),
                config -> config.withThreadFactory(threadFactory))) {
```

## What the seventh preview changed

The `java26` adoption listed six API points to re-check on JDK 27. Checked, with
the verdict for each — five were unchanged and one is a compile error:

| API point | JDK 26 (JEP 525) | JDK 27 (JEP 533) |
| --- | --- | --- |
| `StructuredTaskScope.open(Joiner, UnaryOperator<Configuration>)` | ✅ | unchanged |
| `Joiner.allSuccessfulOrThrow()` returning `List<T>` | ✅ | unchanged |
| `Configuration.withThreadFactory` | ✅ | unchanged |
| `scope.fork(Callable)` | ✅ | unchanged |
| `scope.join()` | `throws FailedException` | now `throws R_X`, the scope's third type parameter |
| `FailedException` | the fixed failure wrapper | **removed** — `ExecutionException` is the default, `CancelledByTimeoutException` the timeout cause |

So the seventh preview is one breaking change plus one new capability:

- **`StructuredTaskScope<T, R>` becomes `StructuredTaskScope<T, R, R_X extends Throwable>`.**
  The third parameter is the exception `join()` throws, which makes the failure
  type part of the scope's type rather than a fixed class.
- **Every `…OrThrow` joiner gains an overload taking `Function<Throwable, R_X>`.**
  `Joiner.allSuccessfulOrThrow(cause -> new SimulationException(msg, cause))`
  makes `R_X` = `SimulationException`. The JDK 26 code caught `FailedException`
  and re-wrapped `e.getCause()` at the call site; that catch block is now only
  about precedence, not about translation.
- `scope.isCancelled()` is new. Not used here: a generation has nothing to
  abandon early.

Verified against the shipped JDK rather than the JEP text:

```console
$ javap -public 'java.util.concurrent.StructuredTaskScope$Joiner' | grep allSuccessful
  public static <T, R_X extends java.lang.Throwable> …Joiner<T, java.util.List<T>, R_X>
      allSuccessfulOrThrow(java.util.function.Function<java.lang.Throwable, R_X>);
  public static <T> …Joiner<T, java.util.List<T>, java.util.concurrent.ExecutionException>
      allSuccessfulOrThrow();
```

## Failure semantics

Band zero is the one thing the scope does not manage: it runs on the owner
thread, so it is outside the scope's cancellation reach and its exception never
reaches the `Joiner`. The four cases and what the engine sees:

| Case | Path | What escapes `next()` |
| --- | --- | --- |
| A forked band throws | Joiner cancels the scope and applies the exception-supplying function; `join()` throws the result | `SimulationException` whose cause is the band's own exception |
| Band zero throws | caught inline, scope joined anyway, then wrapped | `SimulationException` whose cause is the band's own exception |
| Both throw | inline wins; the joiner's `SimulationException` is added as a *suppressed* exception | `SimulationException` with the inline cause, forked failure attached |
| Owner interrupted | `join()` throws `InterruptedException`; `close()` cancels and waits | `InterruptedException`, unchanged contract |

Two details worth keeping in mind when editing this code:

- **The scope must be joined even on the inline failure path.** Forking without
  joining is a structure violation, and `close()` reports *that* —
  `IllegalStateException: Owner did not join after forking` — in place of the
  real failure. The inline `catch` therefore records the exception and falls
  through to `join()` rather than rethrowing immediately.
- **Inline wins.** That is the precedence the previous hand-rolled cleanup had
  (band zero was checked first, and cancelled the rest), preserved deliberately
  so the diagnostic a user sees for a given fault did not change.

Both paths are pinned by tests: `LifeRulesTest.failingForkedBandSurfacesWrapped`
and `failingInlineBandSurfacesWrapped`. The second is new — the inline band was
unreachable under the old injection trick, which worked by substituting the task
an `ExecutorService` was handed.

## The test seam

The old failure test injected a custom `ExecutorService` that discarded the
submitted `Callable` and substituted a throwing one. A scope creates its threads
from a plain `ThreadFactory` and there is nothing to intercept between `fork`
and the task, so that trick has no equivalent.

The seam moved into `LifeRules`: a package-private constructor takes a
`BandComputation` (`(grid, fromRow, toRow) -> BandResult`), defaulting to
`LifeRules::computeBand`. It exists for the tests, and is documented as such.
Two things make it a fair price rather than a hole:

- `computeBand`'s nine positional parameters collapsed into one `BandGrid`
  record (the buffers, the dimensions, the padded active region), which is a
  readability win the seam only made necessary to formalise;
- the inline band became injectable for the first time, which is why there are
  now two failure tests where there was one.

## The price of preview

Stated plainly, because it is the whole reason this waited four migrations:

- **The build requires exactly JDK 27.** Preview class files carry a minor
  version of `65535` and refuse to load on any other JDK — not older, and not
  JDK 28 either without recompiling there. Any CI this repo grows can therefore
  only have a single-valued JDK matrix.
- **The application must be launched with `--enable-preview`.** The flag cannot
  be recorded in the jar manifest, so it belongs to every launch command in the
  README and to `mvn` via the compiler plugin's `enablePreview` and surefire's
  `argLine`. Without it the failure is at least legible:

  ```
  java.lang.UnsupportedClassVersionError: Preview features are not enabled for
  com/conway/model/PatternLoader (class file version 71.65535).
  Try running with '--enable-preview'
  ```

- **The API may move again.** An eighth preview is the base case until it
  finalises; the seventh already cost one compile error (`FailedException`). The
  surface this code touches is small and named in the table above, so the next
  bump is a known-size job.
- **No preview feature leaks past `LifeRules`.** Nothing else in the codebase
  imports `java.util.concurrent.StructuredTaskScope`; the engine sees a
  `ThreadFactory` and a `SimulationException`. Reverting is a one-file job.
  (`LazyConstant` in `PatternLoader` is a second, independent preview dependency
  — see [`java27-migration.md`](java27-migration.md).)

## Measurements

Adoption was gated on re-measuring the scope-per-generation cost rather than
assuming it. `GenerationBenchmark`, virtual thread factory, best of 5, 10 cores,
Zulu 27, same machine, the two builds run interleaved. "Before" is the
pre-port executor build (`d65c9b5`) recompiled with `release=27` and run on the
same JVM, so the only difference between the columns is the code.

The **1-partition row is a built-in control**: it computes inline and never
opens a scope, so whatever it moves by is machine drift plus the `BandGrid`
refactor, not the scope.

### 201×201, best of 5 × 5000 generations (mean of two interleaved runs)

| Partitions | Before | After | Δ |
| --- | --- | --- | --- |
| 1 *(control)* | 12,386 | 13,101 | +5.8% |
| 2 | 17,913 | 18,289 | +2.1% |
| 4 | 17,244 | 17,919 | +3.9% |
| 8 | 17,175 | 17,646 | +2.7% |
| 16 | 17,234 | 17,673 | +2.5% |
| 20 | 17,268 | 17,670 | +2.3% |

### 401×401, best of 5 × 3000 generations (mean of two interleaved runs)

| Partitions | Before | After | Δ |
| --- | --- | --- | --- |
| 1 *(control)* | 3,611 | 3,587 | −0.7% |
| 2 | 6,182 | 6,101 | −1.3% |
| 4 | 8,687 | 8,620 | −0.8% |
| 8 | 9,072 | 9,329 | +2.8% |
| 16 | 9,068 | 9,312 | +2.7% |
| 20 | 9,241 | 9,316 | +0.8% |

**Conclusion: parity**, the same verdict the JDK 26 adoption reached. On 201
every multi-band delta is *smaller* than the control row's +5.8%, so none of it
can be attributed to the scope. On 401 the deltas change sign by partition count
and the largest (+2.8%) sits well inside the spread between two rounds of the
*same* build (up to 7.0% — e.g. the after-build's 8-partition slot read 9,017
and 9,641). Opening and closing a scope per generation — roughly 17,000 of them
a second on the 201 board — is below this harness's resolution. It is not a win
either; nobody should quote the 201 column as one.

### A measurement artifact worth recording

The first attempt used the harness default of 300 generations per measurement
and showed the new build 11–14% *slower* at 4 and 8 partitions on the 201 board,
reproducibly across three interleaved runs. It was not real. Two things gave it
away: on that board `bandsFor` caps every count from 4 upwards to the same four
bands, so 4, 8, 16 and 20 do identical work and cannot legitimately differ; and
the 16/20 slots — measured later in the same JVM — were simultaneously *faster*
than the baseline. At 300 generations a measurement is about 20 ms, short
enough that the tail of JIT warm-up lands inside the early slots. At 5000
generations the dip disappears completely and the columns agree.

If these numbers are ever re-taken, use the long form:

```bash
java --enable-preview -cp target/GuiLife.jar com.conway.bench.GenerationBenchmark 201 5000 virtual
```

## Verification

- [x] `mvn -B clean test` on JDK 27 — **126 tests, 0 failures**
- [x] `mvn clean package` — shaded jar builds
- [x] `PartitionEquivalenceTest` green unchanged — the board is still identical
      at every partition count, which is the property the rewrite most risked
- [x] Both failure tests green — forked and inline bands still surface as
      `SimulationException` with the band's own cause, now via the joiner's
      exception-supplying function
- [x] Benchmark re-run on the 201 and 401 boards, before/after, interleaved
      (above)
- [x] JFR over a 401×3000 benchmark run: **zero** `jdk.VirtualThreadPinned`
      events, 234 young collections, max G1 pause **2.25 ms** — a scope does not
      introduce pinning
- [x] Launch without `--enable-preview` fails with the flag named in the message
- [x] GUI soak on JDK 27 from the shaded jar: a 401×401 run at 8 partitions
      (239,704 generations in 25.1 s) then a live board-size change to 201×201 —
      which tears down the engine and builds a new one — and a 201 run at 4
      partitions of **2,052,730 generations in 95.6 s**. That is roughly 21,500
      scopes opened and closed per second for a minute and a half, followed by
      single steps, clear and quit with no error, no exception and no shutdown
      warning. The scope-per-generation allocation does not accumulate anything

## When to revisit

- **JDK 28.** Recompile, re-run the two failure tests and
  `PartitionEquivalenceTest`, and re-check the API table above. Bump
  `maven.compiler.release` in the same commit.
- **When the API finalises.** Drop `enablePreview`, drop the surefire
  `argLine`, and drop `--enable-preview` from the README launch commands — but
  only once `LazyConstant` finalises too, since either one alone keeps the pin.
  Nothing in `LifeRules` should need to change.
- **If the pin becomes a real cost** — someone needs to run this on an LTS, or
  CI grows a second target — the revert is `LifeRules` alone: restore the
  executor, `cancelAll`, and the two `catch` blocks from the history at
  `d65c9b5`.
