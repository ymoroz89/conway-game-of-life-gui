# Profiling log — JDK 21 vs JDK 25

One controlled comparison of the Conway GUI on JDK 21 and JDK 25, run on 2026-10-01. Raw
data was written to `profiling/run-2M-20261001-jdk/`, which is gitignored, so the numbers in
this file are the record. Every result below was derived from that data in a single pass;
the preflight figures come from the checks described under Preflight.

*2026-10-26: the whole comparison was rerun in one session, with memory measured both with
and without JFR and the threshold test repeated ([the rerun](#2026-10-26--full-rerun)). Before
it, the raw data of 2026-10-10 and 2026-10-25 was archived outside the repository and removed
from `profiling/`.*

## Result at a glance

| Question | Answer |
| --- | --- |
| Is JDK 25 faster? | **No resolvable difference.** Two identical JDK 21 arms differ by 3.90%, about as much as JDK 21 and 25 do (+4.5% to +5.1%), and every range overlaps. **2026-10-26:** in the rerun the twins differ by 0.10% and JDK 25 is ~3% faster with no overlap; not yet attributed ([rerun](#throughput-c1-1)) |
| Does it use less memory? | ~~**Yes: half the committed heap**, 296 against 620 MiB, with the same live set.~~ **2026-10-25: No.** The halving appears only while JFR is recording: the classes recording loads push JDK 25 over the metaspace threshold ([why](#2026-10-25--why-half-the-metaspace-threshold)). Without JFR, JDK 25 commits *more* heap, 620 against 516 MiB, and its process is ~110 MiB larger ([without JFR](#2026-10-25--without-jfr-the-result-reverses)). **2026-10-26:** confirmed at 2M generations, five runs per arm: 620 against 516 MiB in all ten runs per JDK, RSS 694 against 588 MiB ([rerun](#2026-10-26--full-rerun)) |
| What does that cost? | Twice the young collections. GC pause share rises from 0.30% to 0.47% of run time — negligible on both. **2026-10-25:** with JFR only; without it JDK 25 runs fewer collections, 111 against 136 per 500,000 generations |
| Are pauses better or worse? | Steady-state p99 is ~17% lower on 25. Warmup has fewer long pauses on 25, but 3 of 10 runs had one 9.6–12.5 ms pause, against 4.0 ms at worst on 21. **2026-10-25:** measured with JFR; not re-measured without it |
| Does compiling for release 25 matter? | **No.** A release-25 jar and a release-21 jar on JDK 25 overlap on every metric |
| Is the upgrade worth it? | ~~**For memory, not for speed** — and the memory gain holds at default heap settings on an application like this one; explicit `-Xms`/`-Xmx` removes it~~ **2026-10-25: Not for performance.** Speed shows no resolvable difference, and without the profiler JDK 25 uses more memory, not less ([without JFR](#2026-10-25--without-jfr-the-result-reverses)). A case for upgrading this application rests on something this log does not measure. **2026-10-26:** the rerun adds ~3% throughput on JDK 25, for ~105 MiB more RSS without the profiler ([rerun](#2026-10-26--full-rerun)) |

## What was measured

**Code.** The band workers are virtual threads in both arms' code: `a29e9fd` (then `main`,
now tagged `java-21`) and `3492ab9` (then the `java25` branch, since merged into `main` and
deleted). The two have identical `src/` and differ only in `maven.compiler.release`. The tag
`java-25` (`20770f7`) has the same `src/` and `pom.xml` as `3492ab9`, so the two tags
reproduce the two builds exactly.

**JDKs.** Homebrew OpenJDK 21.0.12.1 and 25.0.4.1, selected through jenv as
`openjdk64-21.0.11` and `openjdk64-25.0.3`. The jenv labels lag the real patch level, so the
version was read back from every run and every recording rather than trusted from the name.

**Workload.** `drive.GuiDrive 2000000 3`: the real application — window, event dispatch
thread, incremental repaint — for 2,000,000 generations per run: about 92 s without JFR, 99 s
with it (medians). Apple M2 Pro,
6 performance + 4 efficiency cores.

**Arms.** Four, interleaved by repetition (`21 21b 25 25s`, then again), five repetitions each:

| Arm | Artifact | JVM | Question |
| --- | --- | --- | --- |
| `21` | release-21 jar built from `a29e9fd` (tag `java-21`) by JDK 21 | 21.0.12.1 | Baseline |
| `21b` | the same file | 21.0.12.1 | A/A twin: any delta against `21` is noise by construction |
| `25` | the same file | 25.0.4.1 | The runtime alone (`21` → `25`) |
| `25s` | release-25 jar built from `3492ab9` (reproduced by tag `java-25`) by JDK 25 | 25.0.4.1 | The upgrade as shipped (`21` → `25s`) |

Arms `21`, `21b` and `25` ran a sha256-identical jar (`52d3429ff6a34ffa…`); `25s` ran
`07cf17ace8febda2…`. `25` against `25s` isolates the compile target with the JVM held fixed.

**Rounds.**

| Round | JVM options | Answers |
| --- | --- | --- |
| C1 | `-XX:+UseG1GC` | Throughput, free of profiling overhead |
| C3 | `-XX:+UseG1GC` plus JFR `settings=profile` | Heap, GC, pauses and allocation, at each JDK's default sizing |

No heap flags anywhere: how each JDK sizes the heap on its own is part of what is measured.

## Preflight

**G1 ergonomic defaults are identical** on both JDKs, so any heap difference is runtime
behavior, not configuration:

| Flag | JDK 21 | JDK 25 |
| --- | --- | --- |
| `InitialHeapSize` / `MaxHeapSize` | 512 MiB / 8 GiB | 512 MiB / 8 GiB |
| `G1HeapRegionSize` | 4 MiB | 4 MiB |
| `G1NewSizePercent` / `G1MaxNewSizePercent` | 5 / 60 | 5 / 60 |
| `MaxGCPauseMillis` | 200 | 200 |
| `GCTimeRatio` | 12 | 12 |
| `MinHeapFreeRatio` / `MaxHeapFreeRatio` | 40 / 70 | 40 / 70 |
| `MetaspaceSize` | 21 MiB | 21 MiB |

**Determinism gate.** `drive.GateCheck` steps the same seeded board exactly 50,000
generations and hashes it. JDK 21 with the release-21 jar, JDK 25 with the release-21 jar and
JDK 25 with the release-25 jar all end on the same board: population 346, alive hash
−183858688, ever-alive hash −1980827136.

**Integrity.** 40 of 40 runs exited 0, none without a throughput line. The JVM read back from
every C3 recording matches its arm, every derived file is SHA-linked to its run, no metric is
missing, and the recorded `jvmArguments` carry no heap flags. Branch, `jenv global` and
`.java-version` were restored afterwards.

## Throughput (C1)

| Arm | Median generations/s | Range | All five runs |
| --- | --- | --- | --- |
| `21` | 21,087.9 | 18,926.6–22,173.0 | 18,927 · 20,976 · 21,088 · 21,589 · 22,173 |
| `21b` | 21,909.9 | 21,394.6–22,314.1 | 21,395 · 21,625 · 21,910 · 22,068 · 22,314 |
| `25` | 22,162.1 | 21,074.6–22,588.6 | 21,075 · 21,830 · 22,162 · 22,373 · 22,589 |
| `25s` | 22,044.1 | 19,439.3–22,746.6 | 19,439 · 21,418 · 22,044 · 22,286 · 22,747 |

| Comparison | Delta | Ranges |
| --- | --- | --- |
| A/A: `21` → `21b` (true value 0) | **+3.90%** | overlap |
| Runtime alone: `21` → `25` | +5.09% | overlap |
| Upgrade as shipped: `21` → `25s` | +4.53% | overlap |
| Compile target: `25` → `25s` | −0.53% | overlap |

**No throughput difference between JDK 21 and 25 is resolvable.** The two identical arms
differ by about as much as the JDKs do, and every pair of ranges overlaps. Against both JDK 21
arms pooled (n = 10, median 21,606.8, standard deviation 4.57% of the mean), JDK 25 is +2.57%
with the release-21 jar and +2.02% with the release-25 jar — inside the noise.

The noise is wide this session mostly because of two low runs, `21` rep 4 (18,927) and `25s`
rep 3 (19,439). Both were kept: no identity check failed, and nothing indicates external load.

## Memory and GC (C3)

*2026-10-25: every number in this section and the next was measured with JFR recording, which
is what C3 means. The committed-heap, young-capacity, collection and pause comparisons do not
carry over to runs without JFR, where JDK 25 commits more heap than JDK 21
([without JFR](#2026-10-25--without-jfr-the-result-reverses)). The other rows were not
re-measured without it.*

Medians, with ranges where the five runs differ:

| Metric | `21` | `21b` | `25` | `25s` |
| --- | --- | --- | --- | --- |
| Committed heap, MiB | 620.0 | 620.0 [620.0–744.0] | 296.0 [288.0–296.0] | 296.0 [288.0–296.0] |
| Young capacity, MiB | 371.8 | 371.8 [371.8–443.8] | 175.8 [171.8–175.8] | 175.8 [171.8–175.8] |
| Collections | 441 [440–441] | 441 [371–441] | 934 [934–957] | 934 [934–956] |
| Total GC pause, s | 0.291 | 0.289 | 0.487 | 0.470 |
| GC pause share of run time | 0.298% | 0.295% | 0.475% | 0.474% |
| Pause p50, ms | 0.58 | 0.59 | 0.48 | 0.48 |
| Live set after GC, MiB | 13.0 | 13.1 | 12.9 | 12.8 |
| Bytes allocated per generation | 82,917 | 82,912 | 82,988 | 82,984 |
| JVM CPU per generation, µs | 142.7 | 141.2 | 141.9 | 141.7 |
| `VirtualThreadPinned` events | 0 | 0 | 0 | 0 |

- **JDK 25 commits half the heap:** −52.3% committed, −52.7% young capacity, no overlap
  between the JDKs and none between the A/A twins. Young capacity is ~60% of committed heap on
  every arm (60.0 / 60.0 / 59.4 / 59.4%), so this is one G1 sizing variable, and with the
  defaults identical it is adaptive behavior at runtime. **2026-10-25:** the adaptive step is
  one metaspace-triggered concurrent cycle whose Remark shrinks the heap ([why](#2026-10-25--why-half-the-metaspace-threshold)).
- **Twice the collections** is the direct cost of the smaller young generation. GC pause share
  rises from 0.30% to 0.47% of run time, far under G1's own goal of 1/(1+`GCTimeRatio`) ≈ 7.7%.
  Even removing GC entirely would buy less than the A/A noise.
- **One startup old-generation cycle per JDK 25 run, none on JDK 21.** Every JDK 25 run had
  exactly one `G1Old` cycle at 3.9–4.1 s, caused by `Metadata GC Threshold` — metaspace
  reaching its initial high-water mark during startup class loading — lasting 5–7 ms with
  1.9–2.3 ms of pause. JDK 21 never reached the threshold, although `MetaspaceSize` is 21 MiB
  on both; ~~why JDK 25 crosses it is not established~~ **2026-10-25:** it loads about 420 more
  classes ([why](#2026-10-25--why-half-the-metaspace-threshold)). Every other collection on both JDKs was
  young, and the 934 collections above include that one cycle.
- **GCLocker-initiated collections exist only on JDK 21:** 78 across its ten runs, about 8 per
  run, and none on JDK 25. They occur when a JNI critical section holds up a collection. Their
  absence on 25 is consistent with JEP 423 (region pinning for G1, JDK 22), which replaced
  G1's GCLocker; it was not tested directly.
- **JDK 21's heap sizing is bimodal across starts.** `21b` rep 2 committed 744.0 MiB with 371
  collections, against 620.0 MiB and ~441 for the other nine JDK 21 runs. No JDK 25 run did
  anything comparable: all ten sat at 288 or 296 MiB. Medians absorb the outlier.
- **Live set, allocation and CPU per generation are equal.** JDK 25 allocates 71 bytes more
  per generation (+0.1%): consistent across runs and negligible. 98.4–99.1% of allocation is
  `boolean[]` on every arm. The JVM's CPU *busy fraction* reads 3.1% lower on 25 (28.5 against
  29.4%), but per generation the CPU cost is identical: the fraction is lower only because JFR
  stretches JDK 25's runs (see profiling overhead).
- **Zero pinned events means none of 20 ms or longer**, the `settings=profile` threshold — not
  zero pins.
- **The compile target changes nothing measurable.** `25` against `25s` overlaps on every
  metric, so on this application the upgrade as shipped is the runtime change alone.

## GC pauses

The parsed p99 reads 2.59 / 2.65 / 0.95 / 0.91 ms, which looks like −63% on JDK 25. **Do not
quote it** — it is mostly an artifact of counting. Counted from
`jdk.GarbageCollection.longestPause`, the event the percentiles are computed from, with time
measured from each recording's first collection:

| | `21` | `21b` | `25` | `25s` |
| --- | --- | --- | --- | --- |
| Pauses over 1 ms per run (median) | 17 | 17 | 10 | 9 |
| …in the first 10 s | 17 | 17 | 9 | 9 |
| Pauses ranked above p99 | 4.4 | 4.4 | 9.3 | 9.3 |
| Steady-state p99 (t ≥ 10 s), ms | 0.67 | 0.68 | 0.57 | 0.55 |
| Worst pause in five runs, ms | 4.00 | 3.55 | 11.20 | 12.50 |

Almost every pause over 1 ms is a warmup event: across all 20 runs only six came after 10 s,
the largest 3.58 ms. With 441 collections, p99 sits ~4 pauses from the top, inside JDK 21's
warmup cluster; with 934, it sits ~9 from the top, past JDK 25's smaller one. Two real
differences remain:

- **Steady-state p99 is ~17% lower on JDK 25** (0.56 against 0.67 ms), matching p50: a smaller
  young generation leaves less to copy per collection.
- **JDK 25 has fewer long warmup pauses** (9–10 over 1 ms, against 17), **but three of its ten
  runs had one warmup pause of 9.6–12.5 ms**, where JDK 21's worst was 4.0 ms. All three were
  collections 4–8 of 934, 3.8–4.1 s into the run: two evacuation pauses (9.63 and 11.20 ms) and
  one `Metadata GC Threshold` collection (12.50 ms). Each fell within 0.3 s of that run's
  metadata-threshold cycle, but the same cycle ran in the seven runs without a large pause, so
  this is coincidence in time, not a proven cause.

For a GUI the frame budget is what matters: at 60 fps a frame is 16.7 ms. Steady-state pauses
of ~0.5 ms are invisible, and even the worst warmup pause fits inside one frame.

## Profiling overhead

| Arm | C1 → C3 (median throughput) |
| --- | --- |
| `21` | −3.15% |
| `21b` | −5.43% |
| `25` | −9.10% |
| `25s` | −8.57% |

JFR costs JDK 25 more than JDK 21. C3's own throughput — 20,424 on `21` against 20,146 on
`25`, JDK 21 ~1.4% ahead — is that asymmetry, not a JDK difference, which is why throughput is
read from C1 only.

## 2026-10-25 — why half: the metaspace threshold

*Added 2026-10-25. It explains the memory result above; no number above changes.*

*Correction, later on 2026-10-25: every run in this section recorded with JFR, and the effect
depends on it. Recording adds 500–550 loaded classes on either JDK, and 2.1–2.6 MiB of used
metaspace; only with them does JDK 25 reach the threshold. Without JFR neither JDK runs a concurrent cycle,
and JDK 25 commits more heap than JDK 21
([without JFR](#2026-10-25--without-jfr-the-result-reverses)). The mechanism below holds as
written for runs with JFR.*

**JDK 25's halved heap is a startup side effect, not a sizing improvement.** JDK 25 loads about
420 more classes than JDK 21 for the same application, so its metaspace reaches the 21 MiB
`MetaspaceSize` threshold that JDK 21 stays just under. Reaching it triggers one concurrent
cycle (cause `Metadata GC Threshold`), and that cycle's Remark pause shrinks the committed heap
to 56 MiB; young collections then grow it back only as far as needed, to 288–296 MiB. JDK 21
never runs a concurrent cycle on this application, and before JDK 26 G1 gives heap back outside
a full GC only at Remark — so JDK 21 keeps the 620 MiB it expanded to during warmup. Moving the
threshold moves the result: lowered, JDK 21 settles at exactly JDK 25's size; raised, JDK 25
keeps *more* heap than JDK 21.

### What changed in G1 between 21 and 25

From Thomas Schatzl's per-release G1 change posts and the OpenJDK bug tracker:

- **JDK 22:** [JDK-8314573](https://bugs.openjdk.org/browse/JDK-8314573), "G1: Heap resizing at
  Remark does not take existing eden regions into account" (fix version 22), changes *how much*
  Remark shrinks the heap. It is not the difference here: given a cycle, both JDKs shrink to the
  same 56 MiB (see the test below).
- **JDK 23–25:** no change to Java-heap sizing; the G1 work in these releases is remembered-set
  memory, pause times and write barriers
  ([23](https://tschatzl.github.io/2024/07/22/jdk23-g1-serial-parallel-gc-changes.html),
  [24](https://tschatzl.github.io/2025/04/01/jdk24-g1-serial-parallel-gc-changes.html),
  [25](https://tschatzl.github.io/2025/08/12/jdk25-g1-serial-parallel-gc-changes.html)).
- **JDK 26:** [JDK-8238687](https://bugs.openjdk.org/browse/JDK-8238687), "Investigate memory
  uncommit during young collections in G1" (fix version 26, resolved 2025-07-08), adds resizing
  during young collections. Its description records the behavior until then: G1 uncommits
  memory in the Remark pause only.

### What the recordings show

Control round of the test below (JVM defaults, three runs per JDK, identical jar):

| | JDK 21 | JDK 25 |
| --- | --- | --- |
| Classes loaded | 4,575–4,583 | 4,992–4,995 |
| Metaspace used, peak (threshold 21.0 MiB on both) | 19.9 MiB | 20.8–20.9 MiB |
| Concurrent cycles | none | exactly one, `Metadata GC Threshold`, at ~3.9 s |
| Committed heap | 516 → 620 MiB at warmup, never below 516 | 516 → **56 MiB at that cycle**, then 288 MiB |
| Collections | 113 | 241 |

The class counts agree with the longer 2026-10-10 smoke run: 4,559 and 4,571 on JDK 21's two
recordings, 4,988 and 4,988 on JDK 25's. JFR's *used* metaspace sits just below the threshold
when JDK 25's cycle fires; what matters is that JDK 25 triggers it and JDK 21, about 1 MiB
lower, never does. ~~*Which* additional classes JDK 25 loads, and why, is not established.~~
**2026-10-25:** mostly the ClassFile API and the FFM API, both used by the JDK itself
([which classes](#which-classes-and-why)).

### The causal test

The timeline shows the cycle and the shrink happening together. To show cause, the threshold
was moved on both JDKs with `-XX:MetaspaceSize`, which sets the metaspace level that triggers the
first metadata GC.

**Design.** Arms `21` and `25` ran the same release-21 jar, built from `java-21` (`a29e9fd`),
sha256 `00caae1f57ae4adb…` on both. Three rounds, all with JFR `settings=profile` and no heap
flags: `control` (defaults), `m16` (`-XX:MetaspaceSize=16m`) and `m64`
(`-XX:MetaspaceSize=64m`), verified with `-XX:+PrintFlagsFinal` to resolve identically on both
JDKs. Three runs per arm per round, interleaved, 500,000 generations each (500,126–500,273;
23.3–26.2 s). That is long enough for the heap to settle — it does within the first ~5 s — but
too short for the GC-rate rows, and throughput was not compared. The machine was quiet:
external CPU 6–12% in every run.

| Round | JDK | Cycles (×3 runs) | Committed heap, min | Committed heap, final | Collections | Metaspace peak |
| --- | --- | --- | --- | --- | --- | --- |
| `control` | 21 | 0, 0, 0 | 516 | 620, 620, 620 | 113 | 19.9 |
| `control` | 25 | 1, 1, 1 | 56 | 288, 288, 288 | 241 | 20.8–20.9 |
| `m16` | 21 | 1, 1, 1 | 56 | 288, 288, 288 | 246 | 19.8 |
| `m16` | 25 | 1, 1, 1 | 56 | 292, 288, 288 | 246 | 20.8 |
| `m64` | 21 | 0, 0, 0 | 516 | 620, 620, 620 | 113 | 19.8–19.9 |
| `m64` | 25 | 0, 0, 0 | 516 | 744, 744, 744 | 96 | 20.9 |

Heap in MiB; every cycle's cause was `Metadata GC Threshold`.

- **Lowered to 16 MiB,** JDK 21 runs the cycle too, shrinks to the same 56 MiB, and settles at
  288 MiB — JDK 25's size. The Remark shrink is identical on both JDKs, which is why
  JDK-8314573 is not the difference.
- **Raised to 64 MiB,** JDK 25 skips the cycle and keeps 744 MiB — more than JDK 21's 620, with
  fewer collections (96 against 113). The prediction was that it would stay near 620; 744 MiB
  is the next expansion step, the same size JDK 21 reached in one of ten starts on 2026-10-01.
- **Collections follow heap size,** not the JDK: 241–246 in every run with a cycle (~290 MiB),
  96–113 in every run without (620–744 MiB).

In all 18 runs the cycle happened exactly where predicted, and every run with a cycle ended at
288–292 MiB.

### What follows

- **Setting `-Xms`/`-Xmx` removes the effect** — the heap size becomes a decision rather than a
  side effect of how many classes the libraries load.
- **Applications whose old generation fills will run concurrent cycles on JDK 21 as well,** and
  get the same chance to shrink, so the difference may not appear for them. Not tested.
- **`-XX:MetaspaceSize` is not a heap-tuning knob;** the test uses it to demonstrate the
  mechanism, not as a recommendation.
- **JDK 26 changes the mechanism itself** (resizing during young collections), so the
  comparison is worth repeating there.

### Reproducing this test

The rig and staging are those in [Reproducing](#reproducing), with two arms on the same
release-21 jar and three extra rounds appended to a copy of `bench.conf.example`:

```bash
ROUND_control='{JFR}'
ROUND_m16='-XX:MetaspaceSize=16m {JFR}'
ROUND_m64='-XX:MetaspaceSize=64m {JFR}'
```

```text
21   openjdk64-21.0.11   java-21
25   openjdk64-25.0.3    java-21
```

```bash
.claude/skills/jdk-performance-comparison/scripts/run-matrix.sh \
    --rounds control,m16,m64 --reps 3 --units 500000 \
    --jdks <jdks file> --bench <bench file> \
    --out profiling/run-500k-20261025-metaspace
```

Each figure above comes from the recordings, with GC events joined by `gcId`: committed heap
from `jdk.GCHeapSummary` (`After GC`, `heapSpace.committedSize`); cycles from
`jdk.GarbageCollection` (`name` = `G1Old`, and `cause`); metaspace from `jdk.MetaspaceSummary`
(`metaspace.used`, `gcThreshold`); classes from `jdk.ClassLoadingStatistics`
(`loadedClassCount`); external CPU from `jdk.CPULoad` as `machineTotal − jvmUser − jvmSystem`.
Raw data was written to `profiling/run-500k-20261025-metaspace/`, which is gitignored.

The jar staged for this test, built from `java-21` on 2026-10-25, was byte-identical to the one
built from the same tag on 2026-10-10 (`00caae1f…`): Maven resolves a fixed
`project.build.outputTimestamp` of 1980-02-01, and all 61 `com.conway` entries carry it. The
2026-10-01 build differed (`52d3429f…`), for a reason not established.

## 2026-10-25 — without JFR, the result reverses

*Added 2026-10-25, after the threshold test. It overturns the memory result at the top of this
log; the throughput result is unaffected, since C1 never recorded.*

**Without JFR, JDK 25 uses more memory than JDK 21, not less.** Every heap figure above was
measured while JFR was recording: the C3 round, and all three rounds of the threshold test.
Without recording, neither JDK reaches the 21 MiB metaspace threshold, neither runs a concurrent
cycle, and no Remark shrinks the heap. JDK 25 then commits 620 MiB of heap against JDK 21's 516,
and its process is about 113 MiB (19%) larger.

### Design

Arms `21` and `25` ran the threshold test's jar — release 21, built from `java-21`, sha256
`00caae1f57ae4adb…` on both — with each JDK called by its full path. 500,000 generations per run
(`drive.GuiDrive 500000 3`), three runs per JDK per pass, interleaved, `-XX:+UseG1GC` and no
other flags beyond the instrumentation. Two passes, because Native Memory Tracking adds
bookkeeping of its own:

- **Pass A:** unified logging of every collection and every class load, and the process's RSS
  sampled once a second with `ps`.
- **Pass B:** Native Memory Tracking, summary printed at exit.

Throughput was not compared: three short runs per JDK, with logging on, resolve nothing.

### Heap, classes and process memory (pass A)

| | JDK 21 | JDK 25 |
| --- | --- | --- |
| Concurrent cycles | 0, 0, 0 | 0, 0, 0 |
| Committed heap at exit, MiB | 516, 516, 516 | 620, 620, 620 |
| Collections, all young | 136, 136, 136 | 111, 111, 111 |
| Classes loaded | 4,073, 4,073, 4,073 | 4,468, 4,468, 4,450 |
| Metaspace at exit, used / committed, MiB | 17.3 / 17.7 | 18.6–18.7 / 19.1–19.2 |
| RSS, median of the last 10 s, MiB | 577, 582, 584 | 695, 691, 699 |
| RSS, peak, MiB | 578–584 | 696–710 |

Against the threshold test's control round, recording adds 502–510 classes on JDK 21 and
524–545 on JDK 25, and 2.6 and 2.1–2.3 MiB of used metaspace — the difference between staying
under 21 MiB and reaching it for JDK 25, and not enough for JDK 21. JDK 25's heap grows from 516
to 620 MiB at its sixth collection, about 5 s in, and stays there; JDK 21's stays at the 516 MiB
it starts with.

### Process memory by category (pass B)

Committed at exit, MiB, median of three runs:

| NMT category | JDK 21 | JDK 25 | Difference |
| --- | --- | --- | --- |
| Java Heap | 516.0 | 620.0 | +104.0 |
| Arena Chunk | 1.7 | 5.0 | +3.3 |
| Code | 12.9 | 16.0 | +3.1 |
| GC | 61.6 | 63.6 | +2.0 |
| Metaspace | 15.6 | 16.7 | +1.1 |
| Shared class space | 12.6 | 13.6 | +1.0 |
| Class | 2.7 | 3.0 | +0.3 |
| Symbol | 4.8 | 3.3 | −1.5 |
| Thread | 80.5 | 2.0 | not comparable |

- **The heap is most of the difference.** The categories other than Thread sum to about
  +113 MiB, matching the RSS gap in pass A (582 against 695 MiB, medians).
- **The Thread row is a reporting difference, not memory.** JDK 21 counts every thread stack as
  fully committed (stack committed = reserved = 84,312,064 bytes for 36 threads); JDK 25 reports
  1,982,464 bytes committed for 37, in every run. The other categories already account for the
  whole RSS gap, so the Thread row does not reflect resident memory. For the same reason NMT's
  totals, 720.1 and 746.1 MiB, are not comparable.
- **Arena Chunk is noisy:** 0.0–6.9 MiB across JDK 21's runs, 2.8–5.0 across JDK 25's.
- **JDK 21's heap sizing is bimodal here too:** pass B's third JDK 21 run committed 620 MiB, so
  JDK 21 ended at 516 MiB in five of its six runs without JFR. Every JDK 25 run ended at 620.

### Which classes, and why

Class names present in all three of a JDK's pass A runs, with hidden-class suffixes stripped
and lambdas collapsed per call site: 3,531 on JDK 21, 3,813 on JDK 25; 526 appear only on
JDK 25 and 244 only on JDK 21. Two JDK changes account for most of the JDK 25 side, and the JDK
source shows each one:

- **The ClassFile API, ~175 names** (`java.lang.classfile` 77, `jdk.internal.classfile` 98).
  JDK 25 spins lambda classes with it: `java.lang.invoke.InnerClassLambdaMetafactory` imports
  `java.lang.classfile.ClassFile` in JDK 25 and `jdk.internal.org.objectweb.asm.*` in JDK 21.
  It loads at 0.021 s, after `drive.GuiDrive` and before the driver's first lambda, so it comes
  with lambdas, not with this application. The ASM classes it replaced are among the JDK 21-only
  names (`jdk.internal.org`, 20).
- **The Foreign Function & Memory API, ~155 names** (`jdk.internal.foreign` 129,
  `java.lang.foreign` 27). They load from 0.435 s, immediately after the Metal pipeline loads
  `sun.java2d.pipe.RenderBuffer`. JDK 25's `RenderBuffer` allocates its native buffer with
  `Arena.global().allocate(...)`; JDK 21's calls `jdk.internal.misc.Unsafe.allocateMemory`. This
  half comes from the GUI's rendering path.

The rest is not traced: on JDK 25 `java.lang.invoke` 52, `java.util` 42 and `jdk.xml.internal`
17 among others; on JDK 21 `java.util` 38, `java.lang.reflect` 20, `java.awt` 17 and
`java.security` 15.

### What follows

- **The memory result at the top of this log is a profiler effect.** It holds for this
  application running under JFR at default heap settings, which is how it was measured, and not
  for the same application running without it.
- **Without JFR, JDK 25 costs this application about 113 MiB more process memory,** 104 MiB of
  it a larger committed heap. Why JDK 25 expands one step further than JDK 21 is not
  established.
- **Measure memory without the profiler too.** This log measured speed without JFR and memory
  with it, on the assumption that recording barely disturbs memory. Here it moved a threshold.
  A memory conclusion needs at least one round without the profiler, read from GC logging, NMT
  or RSS.

### Reproducing this test

Staging as in [Reproducing](#reproducing), with `/tmp/jdkcmp-21` and `/tmp/jdkcmp-25` both
holding the release-21 jar:

```bash
J21="$(jenv prefix openjdk64-21.0.11)"; J25="$(jenv prefix openjdk64-25.0.3)"
OUT=profiling/run-500k-<date>-nojfr; mkdir -p "$OUT"
for rep in 1 2 3; do
  for jdk in 21 25; do
    if [ "$jdk" = 21 ]; then J="$J21"; else J="$J25"; fi
    t="A-$jdk-rep$rep"
    "$J/bin/java" -XX:+UseG1GC \
        "-Xlog:gc*=info:file=$OUT/$t.gc.log:uptime,level,tags" \
        "-Xlog:class+load=info:file=$OUT/$t.classes.log:uptime" \
        -cp "tools/bench/out:/tmp/jdkcmp-$jdk/bench-jar.jar" drive.GuiDrive 500000 3 \
        > "$OUT/$t.console.log" 2>&1 &
    pid=$!
    while kill -0 "$pid" 2>/dev/null; do
      echo "$(date +%s) $(ps -o rss= -p "$pid")" >> "$OUT/$t.rss"; sleep 1
    done
  done
done
```

Pass B is the same loop with `t="B-$jdk-rep$rep"` and the two `-Xlog` options replaced by
`-XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics`.

Committed heap at exit is the `gc,heap,exit` line (`garbage-first heap total …K` on JDK 21,
`… committed …K` on JDK 25). Collections are distinct `GC(n)` ids, since each pause logs a start
line and an end line; cycles are `Concurrent Mark Cycle` lines; classes are lines of the class
log. Raw data was written to `profiling/run-500k-20261025-nojfr/`, which is gitignored.

## 2026-10-26 — full rerun

*Added 2026-10-26. The comparison above, rerun in one session from the release tags, with memory
measured with and without JFR and the threshold test repeated at five runs per arm. Every table
below was generated from this session's data; the 2026-10-01 tables above stay as measured.*

- **Throughput: JDK 25 is ~3% faster, and this session resolves it.** The identical twins differ
  by −0.10%, every JDK 25 run is faster than every JDK 21 run, and the compile target changes
  nothing measurable. What causes the 3% is not established.
- **Memory depends on the profiler.** Without JFR, JDK 25 commits 620 MiB of heap against 516 in
  all ten runs per JDK, and its process is ~105 MiB larger (RSS 694 against 588 MiB, medians).
  With JFR, JDK 25 runs one metadata-threshold cycle and commits 288–296 MiB against JDK 21's
  620–744, and its process is ~200 MiB smaller (522 against 723 MiB).
- **The cause is JDK 25's own extra classes, shown without the profiler.** JDK 25 loads ~395
  more classes than JDK 21 without JFR and 428 more with it. With the metaspace threshold
  placed between the two JDKs' own metaspace sizes and no JFR, JDK 25 alone runs the cycle and
  ends at 288 MiB, in all five runs.

### Design

- **Arms** as on 2026-10-01: `21`, `21b` (A/A twin), `25` and `25s`. The release-21 jar was
  built from `java-21` (`a29e9fd`) by JDK 21, sha256 `00caae1f57ae4adb…`, byte-identical to
  the builds of 2026-10-10 and 2026-10-25, and staged for `21`, `21b` and `25`. The release-25
  jar was built from `java-25` (`20770f7`) by JDK 25, sha256 `7e9eb5e8b0ff0166…`, for `25s`; it
  differs from 2026-10-01's `07cf17ac…`, for a reason not established.
- **JDKs** 21.0.12.1 and 25.0.4.1, read back from every run and every recording. The G1 and
  metaspace defaults in [Preflight](#preflight) were re-checked and are unchanged and identical.
  The determinism gate passed on `21`, `25` and `25s`: population 346, alive hash −183858688,
  ever-alive hash −1980827136.
- **Part 1**, all four arms, 2,000,000 generations, five runs each, interleaved:

  | Round | JVM options beyond `-XX:+UseG1GC` | Answers |
  | --- | --- | --- |
  | C1 | none | Throughput |
  | C3 | JFR `settings=profile`, class-load log | Heap and GC with JFR, and which classes JFR adds |
  | M | GC log, class-load log, NMT summary at exit | Heap, classes and process memory without JFR |

- **Part 2**, arms `21` and `25` on the same release-21 jar, 500,000 generations
  (500,125–500,339; 21.9–26.5 s), five runs each: `m16` and `m64` with JFR as on 2026-10-25, and
  `n18` without JFR, `-XX:MetaspaceSize=18560k` (18.1 MiB, resolved to 19,005,440 bytes on
  both). 18.1 MiB lies between the two JDKs' committed metaspace without JFR, 17.7 and
  19.1–19.2 MiB on 2026-10-25.
- **RSS** of every run sampled once a second by `tools/bench/sample-rss.sh`, outside the JVM.
- **Integrity.** 90 of 90 runs exited 0 with a throughput line, each arm's `java -version` and
  each recording's `jvmVersion` match the arm, and no derived metric is null. 12 runs ended
  with the window unfocused (one in C1, nine in M, two in part 2); within an arm of C1 or M their
  effect on throughput ranges from −4.1% to +0.1%, signs mixed, so all were kept. External CPU had a
  median of 6.0–7.2% in every C3 run. `jenv global` and `.java-version` were restored.

### Throughput (C1)

| Arm | Median generations/s | Range | All five runs |
| --- | --- | --- | --- |
| `21` | 22,591.8 | 21,770.2–22,872.7 | 22,592 · 21,770 · 22,770 · 22,873 · 22,176 |
| `21b` | 22,568.7 | 21,729.0–22,773.9 | 22,569 · 22,727 · 21,729 · 21,864 · 22,774 |
| `25` | 23,289.4 | 22,912.0–23,372.4 | 22,912 · 23,372 · 23,325 · 23,289 · 22,995 |
| `25s` | 23,192.7 | 23,006.5–23,272.7 | 23,260 · 23,006 · 23,273 · 23,143 · 23,193 |

| Comparison | Delta | Ranges |
| --- | --- | --- |
| A/A: `21` → `21b` (true value 0) | −0.10% | overlap |
| Runtime alone: `21` → `25` | +3.09% | **no overlap** |
| Upgrade as shipped: `21` → `25s` | +2.66% | **no overlap** |
| Compile target: `25` → `25s` | −0.42% | overlap |

- **JDK 25 is faster, by about 3%.** Against both JDK 21 arms pooled (n = 10, median 22,580.2,
  standard deviation 2.03% of the mean) it is +3.14% with the release-21 jar and +2.71% with
  the release-25 jar. The separation is narrow: the slowest JDK 25 run, 22,912, against the
  fastest JDK 21 run, 22,873. Drift was at most 0.2% per repetition in any arm.
- **It agrees in direction with 2026-10-01** (+5.09% and +4.53%), which could not resolve it
  because that session's A/A twins differed by 3.90%.
- **The cause is not established.** Without JFR, JDK 25 also runs a larger heap (620 against
  516 MiB) with fewer collections (440 against 538, round M), and GC pauses take 0.39% of its
  run time against 0.43% on JDK 21 (round M, from the GC log), far less than 3%. Effects of the
  larger young generation beyond pause time are not excluded. A pinned-heap round without JFR
  (C2) would separate code generation from GC sizing.

| Arm | C1 → C3 (median throughput) |
| --- | --- |
| `21` | −7.09% |
| `21b` | −6.78% |
| `25` | −13.23% |
| `25s` | −12.47% |

JFR, with the class-load log, again costs JDK 25 more than JDK 21, so C3's throughput (JDK 21
~4% ahead) is that asymmetry and not a JDK difference.

### Memory and GC with JFR (C3)

Medians, with ranges where the five runs differ:

| Metric | `21` | `21b` | `25` | `25s` |
| --- | --- | --- | --- | --- |
| Committed heap, MiB | 620.0 [620.0–744.0] | 620.0 | 296.0 [288.0–296.0] | 288.0 [288.0–296.0] |
| Young capacity, MiB | 371.8 [371.8–443.8] | 371.8 | 175.8 [171.8–175.8] | 171.8 [171.8–175.8] |
| Collections | 441 [371–441] | 441 | 937 [935–957] | 956 [936–958] |
| Metadata-threshold cycles, per run | 0 · 0 · 0 · 0 · 0 | 0 · 0 · 0 · 0 · 0 | 1 · 1 · 1 · 1 · 1 | 1 · 1 · 1 · 1 · 1 |
| Total GC pause, s | 0.286 | 0.286 | 0.463 | 0.448 |
| GC pause share of run time | 0.287% | 0.301% | 0.464% | 0.454% |
| Pause p50, ms | 0.58 | 0.58 | 0.47 | 0.44 |
| Live set after GC, MiB | 13.1 | 13.0 | 12.9 | 12.8 |
| Bytes allocated per generation | 82,911 | 82,912 | 83,008 | 82,982 |
| JVM CPU per generation, µs | 140.0 | 139.9 | 141.0 | 140.4 |
| Classes loaded | 4,570 | 4,570 | 4,998 [4,997–4,998] | 4,998 [4,980–4,998] |
| Metaspace committed, peak, MiB | 20.25 | 20.25 | 21.31 [21.31–21.38] | 21.31 |
| `VirtualThreadPinned` events | 0 | 0 | 0 | 0 |

Committed heap per run: `21` 620 · 620 · 620 · 744 · 744, `21b` 620 in all five, `25`
296 · 288 · 296 · 296 · 296, `25s` 296 · 288 · 288 · 288 · 296.

- **The 2026-10-01 memory result replicates with JFR.** JDK 25 runs exactly one `G1Old` cycle,
  cause `Metadata GC Threshold`, at 3.99–4.17 s in all ten runs, and JDK 21 none. Peak committed
  metaspace is 21.31–21.38 MiB on JDK 25 against 20.25 on JDK 21, with the threshold at 21.0 on
  both.
- **JDK 21's sizing is bimodal again:** two of arm `21`'s runs settled at 744 MiB.
- **GCLocker-initiated collections, again only on JDK 21:** 2–9 per run, none on JDK 25.
- **Pauses.** The parsed p99 reads 2.50 / 2.55 / 1.45 / 1.31 ms, which looks like −42% on
  JDK 25, but almost every pause over 1 ms is warmup: 17 per run on JDK 21 and 10 on JDK 25, and
  across the ten runs per JDK only three such JDK 21 pauses and one JDK 25 pause came after 10 s. Steady-state p99
  (t ≥ 10 s) is 0.66 / 0.65 / 0.54 / 0.52 ms, ~18% lower on JDK 25. The worst pause was 4.75 ms
  on JDK 21 and 6.34 ms on JDK 25; the 9.6–12.5 ms warmup pauses of 2026-10-01 did not recur.

### Memory without JFR (M, and RSS)

| Metric, no JFR | `21` | `21b` | `25` | `25s` |
| --- | --- | --- | --- | --- |
| Committed heap at exit, MiB | 516 | 516 | 620 | 620 |
| Collections | 538 | 538 | 440 | 440 |
| Metadata-threshold cycles | 0 | 0 | 0 | 0 |
| Classes loaded | 4,075 [4,075–4,077] | 4,075 [4,065–4,077] | 4,470 [4,453–4,471] | 4,471 [4,452–4,471] |
| Metaspace committed at exit, MiB | 17.75 [17.69–17.75] | 17.75 [17.69–17.75] | 19.12 [19.12–19.19] | 19.12 [19.12–19.19] |

All five runs of every arm agree on committed heap, collections and cycles. GC-thread CPU,
summed from the GC log's `gc,cpu` lines (User + Sys), is 1.34 / 1.37 / 1.06 / 1.06 s per run
(medians), against roughly 280 s of total JVM CPU per run in C3; total process CPU was not
recorded without JFR.

| RSS, MiB, median of the last 10 s of each run | `21` | `21b` | `25` | `25s` |
| --- | --- | --- | --- | --- |
| C1, no JFR | 584 · 588 · 585 · 696 · 588 | 583 · 585 · 586 · 580 · 585 | 688 · 708 · 708 · 690 · 694 | 688 · 689 · 688 · 706 · 714 |
| C3, JFR | 716 · 723 · 720 · 845 · 848 | 711 · 715 · 715 · 713 · 713 | 522 · 522 · 522 · 522 · 525 | 520 · 509 · 507 · 515 · 521 |
| M, no JFR, NMT on | 593 · 593 · 589 · 590 · 589 | 588 · 596 · 591 · 591 · 590 | 731 · 722 · 708 · 704 · 708 | 736 · 728 · 719 · 708 · 704 |

| NMT category, committed at exit, MiB, median of five | `21` | `21b` | `25` | `25s` |
| --- | --- | --- | --- | --- |
| Java Heap | 516.0 | 516.0 | 620.0 | 620.0 |
| Code | 13.4 | 13.4 | 16.4 | 16.4 |
| GC | 61.6 | 61.6 | 63.6 | 63.6 |
| Metaspace | 15.6 | 15.6 | 16.7 | 16.7 |
| Shared class space | 12.6 | 12.6 | 13.6 | 13.6 |
| Class | 2.8 | 2.8 | 3.0 | 3.0 |
| Symbol | 4.8 | 4.8 | 3.3 | 3.3 |
| Native Memory Tracking | 0.9 | 0.9 | 1.3 | 1.3 |
| Arena Chunk | 4.2 | 4.2 | 7.1 | 2.4 |
| Thread | 80.5 | 80.5 | 2.0 | 2.0 |

- **Without JFR, JDK 25's process is ~105 MiB (18%) larger:** RSS medians 588 and 585 MiB on
  JDK 21, 694 and 689 on JDK 25, from C1, which has neither JFR nor NMT. The one JDK 21 run at
  696 MiB fits the larger of JDK 21's two heap sizes, but C1 logs no heap to confirm it.
- **With JFR it is ~200 MiB (28%) smaller:** 723 and 713 against 522 and 515. Arm `21`'s two
  runs at 845–848 MiB are its two 744-MiB heaps.
- **The heap is most of the no-JFR difference:** +104 MiB, with code cache +3.0, GC structures
  +2.0, metaspace +1.1, the CDS archive +1.0, and symbols −1.5.
- **Thread and the NMT totals are not comparable,** as on 2026-10-25: JDK 21 counts every stack
  as committed (84,312,064 bytes for 36 threads), JDK 25 counts 1,982,464 bytes for 37.
  **Arena Chunk is noisy,** 0.2–14.1 MiB across runs.

### Which classes JDK 25 adds, and which JFR adds

Class names from round M's class-load logs (no JFR), present in all five runs of arms `21` and
`25`, with hidden-class suffixes stripped and lambdas collapsed per call site: 3,533 on JDK 21,
3,806 on JDK 25; 526 only on JDK 25 and 253 only on JDK 21. The 2026-10-25 attribution holds:

- **ClassFile API, 175 names** (`jdk.internal.classfile` 98, `java.lang.classfile` 77). In the
  first JDK 25 run it loads at 0.018 s, after `drive.GuiDrive` (0.017 s) and before the driver's first lambda
  (0.021 s); JDK 25's `InnerClassLambdaMetafactory` generates lambda classes with it, where
  JDK 21's uses ASM. The ASM classes are 20 of the JDK 21-only names.
- **FFM API, 156 names** (`jdk.internal.foreign` 129, `java.lang.foreign` 27), from 0.379 s in that run,
  right after `sun.java2d.pipe.RenderQueue`, `sun.java2d.metal.MTLRenderQueue` and
  `sun.java2d.pipe.RenderBuffer`, which allocates its buffer with `Arena` on JDK 25 and
  `Unsafe` on JDK 21.
- **The other 195 JDK 25-only names** are not traced (`java.lang.invoke` 52, `java.util` 42,
  `jdk.xml.internal` 17 and smaller groups).

Comparing each JDK's C3 class-load logs with its round M logs gives the classes JFR adds: **398
names on JDK 21 and 487 on JDK 25.** Both include `jdk.jfr.internal` (134 and 113),
`jdk.jfr.events` (33 and 36), `java.nio` (31 and 31) and `sun.nio.ch` (27 and 24). The
difference is the bytecode library JFR generates its event classes with: on JDK 21, 43 ASM
names (`jdk.internal.org`); on JDK 25, 104 ClassFile API names (`jdk.internal.classfile` 58,
`java.lang.classfile` 46). `jdk.jfr.internal.EventInstrumentation` imports
`jdk.internal.org.objectweb.asm` in JDK 21 and `java.lang.classfile` in JDK 25. So recording
costs JDK 25 more classes than JDK 21.

### The threshold test, with and without JFR

| Round | JFR | Threshold | JDK | Cycles, per run | Committed heap, min, MiB | Committed heap, final, MiB | Collections | Metaspace committed, MiB |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `m16` | yes | 16 MiB | 21 | 1 · 1 · 1 · 1 · 1 | 56 | 288 · 288 · 288 · 288 · 288 | 246 | 20.19 [20.19–20.25] |
| `m16` | yes | 16 MiB | 25 | 1 · 1 · 1 · 1 · 1 | 56 | 288 · 288 · 288 · 288 · 288 | 246 [245–246] | 21.31 [21.25–21.31] |
| `m64` | yes | 64 MiB | 21 | 0 · 0 · 0 · 0 · 0 | 516 | 620 · 620 · 620 · 620 · 620 | 113 | 20.25 [20.19–20.25] |
| `m64` | yes | 64 MiB | 25 | 0 · 0 · 0 · 0 · 0 | 516 | 744 · 744 · 620 · 620 · 744 | 96 [95–112] | 21.44 [21.38–21.44] |
| `n18` | no | 18.1 MiB | 21 | 0 · 0 · 0 · 0 · 0 | 516 | 516 · 516 · 516 · 516 · 516 | 136 | 17.75 [17.69–17.75] |
| `n18` | no | 18.1 MiB | 25 | 1 · 1 · 1 · 1 · 1 | 56 | 288 · 288 · 288 · 288 · 288 | 245 | 19.06 [19.06–19.12] |

Every cycle's cause was `Metadata GC Threshold`.

- **`m16` and `m64` replicate 2026-10-25 at five runs.** Lowered, both JDKs cycle and settle at
  288 MiB. Raised, neither cycles, and JDK 25 keeps 620 or 744 MiB (744 in three of five)
  against JDK 21's 620.
- **`n18` reproduces the halving without the profiler.** JDK 25 runs the cycle at 4.0 s in all
  five runs, shrinks to 56 MiB and settles at 288; JDK 21 never cycles and keeps 516. RSS
  medians are 472 MiB on JDK 25 and 573 on JDK 21.
- **So JFR is not the cause; it moves the line.** Without JFR, both JDKs' metaspace sits under
  the default 21 MiB threshold (17.7 and 19.1 MiB). Recording adds 2.5 MiB on JDK 21 and 2.2 on
  JDK 25, which puts the default threshold between them (20.25 and 21.31). Wherever the
  threshold falls between the two, JDK 25's extra 1.1–1.4 MiB of metaspace is what crosses it.

### What follows

- **The 2026-10-25 correction holds** at full length and five runs per arm: the halved heap is
  an effect of running with JFR, and without it JDK 25 uses more memory.
- **JDK 25's throughput gain is the first resolvable one in this log,** at ~3% in one session.
  The highest-value next experiment is C2, a pinned heap without JFR, which would say whether it
  is code generation or GC sizing: four arms, five runs, 2,000,000 generations, ~32 minutes.

### Reproducing the rerun

Staging, the drivers and the gate are steps 1–4 of [Reproducing](#reproducing). The study's
configuration, the runner log, the analysis scripts and the jar hashes were saved with the data
in `profiling/run-2M-20261026-full/study/`. The rounds were defined in a copy of `bench.conf`:

```bash
CLASSLOG='-Xlog:class+load=info:file={RUN}.classes.log:uptime'
GCLOG='-Xlog:gc*=info:file={RUN}.gc.log:uptime,pid,level,tags'
ROUND_C3="{JFR} $CLASSLOG"
ROUND_M="$GCLOG $CLASSLOG -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics"
ROUND_m16='-XX:MetaspaceSize=16m {JFR}'
ROUND_m64='-XX:MetaspaceSize=64m {JFR}'
ROUND_n18="-XX:MetaspaceSize=18560k $GCLOG $CLASSLOG"
```

```bash
tools/bench/sample-rss.sh profiling/run-2M-<date>-full/rss-samples.txt & S=$!
.claude/skills/jdk-performance-comparison/scripts/run-matrix.sh \
    --rounds C1,C3,M --reps 5 --units 2000000 \
    --jdks tools/bench/jdks.conf --bench <bench file> --out profiling/run-2M-<date>-full
kill "$S"
tools/bench/sample-rss.sh profiling/run-500k-<date>-threshold/rss-samples.txt & S=$!
.claude/skills/jdk-performance-comparison/scripts/run-matrix.sh \
    --rounds m16,m64,n18 --reps 5 --units 500000 \
    --jdks <arms 21 and 25 on java-21> --bench <bench file> --out profiling/run-500k-<date>-threshold
kill "$S"
```

`{RUN}` is each run's own path prefix, added to the runner for this rerun. Recordings were
reduced with the skill's `parse_jfr.py`; GC events were joined by `gcId`. Raw data was written to
`profiling/run-2M-20261026-full/` and `profiling/run-500k-20261026-threshold/`, which are
gitignored.

## 2026-10-26 — follow-ups: the 3% and the heap growth

*Added 2026-10-26, after the rerun, to answer two of its open questions. Same machine, same
day; the jars were rebuilt from the tags and are byte-identical to the rerun's, including the
release-25 jar (`7e9eb5e8…`), and the determinism gate passed again.*

- **The ~3% throughput gain is not GC sizing.** With both JDKs held at the same heap, every run
  on both made exactly the same number of collections, and JDK 25 was still 2–3% faster.
- **JDK 25 grows its heap because its early pauses are slightly longer.** G1 grows the heap after
  four collections whose pause-time ratio is over a 1% threshold. JDK 25's early collections
  land just over it and JDK 21's just under. The extra time is in evacuation.

### Throughput at equal heap (F620)

All four arms, `-Xms620m -Xmx620m` and one GC log line per collection, no JFR, 2,000,000
generations, five runs each:

| Arm | Median generations/s | Range | All five runs |
| --- | --- | --- | --- |
| `21` | 22,056.4 | 21,081.0–22,252.2 | 22,056 · 21,081 · 22,230 · 22,252 · 21,662 |
| `21b` | 21,968.8 | 21,007.8–22,155.8 | 21,008 · 22,156 · 22,040 · 21,969 · 21,764 |
| `25` | 22,446.4 | 22,246.8–22,832.2 | 22,247 · 22,832 · 22,733 · 22,330 · 22,446 |
| `25s` | 22,681.2 | 22,456.6–22,835.7 | 22,681 · 22,716 · 22,641 · 22,457 · 22,836 |

| Comparison | Delta | Ranges |
| --- | --- | --- |
| A/A: `21` → `21b` (true value 0) | −0.40% | overlap |
| Runtime alone: `21` → `25` | +1.77% | overlap, by one run: 22,252 against 22,247 |
| Upgrade as shipped: `21` → `25s` | +2.83% | **no overlap** |
| Compile target: `25` → `25s` | +1.05% | overlap |

- **GC is identical by construction.** Both JDKs committed 624 MiB in every run and made
  exactly 439 collections in every run; GC pauses took 0.34% of run time on JDK 21 and 0.36% on
  JDK 25.
- **JDK 25 stays ahead.** Against both JDK 21 arms pooled (n = 10, median 22,004.5, standard
  deviation 2.07% of the mean) it is +2.01% with the release-21 jar and +3.08% with the release-25
  jar. The release-21-jar arm overlaps JDK 21 by a single run, so this round alone does not meet
  the no-overlap criterion for it; the release-25 arm does.
- **So the rerun's +3.1% is not GC sizing.** It lives in the runtime: code generation, the JDK
  libraries, or both. What part of the runtime is not established.
- **This round ran in a different window state:** 19 of 20 runs ended with the window
  unfocused, and 12 started that way; in C1, one of 20 ended unfocused. Every arm shares it, so the
  within-round comparison stands; F620 must not be compared with C1 across rounds.

### Why JDK 25 grows its heap and JDK 21 does not (ergo)

Arms `21` and `25` on the same release-21 jar, no JFR, `-Xlog:gc*=info,gc+ergo*=debug,gc+heap=debug`,
500,000 generations, five runs each. G1 logs each sizing decision after every young collection:
a short-term pause-time ratio, a threshold, and the amount it resizes by.

- **The threshold is 1.00%, not 7.69%.** `GCTimeRatio=12` gives G1 a goal of 7.69% of time in
  GC, but G1 logs a threshold scaled down while the heap is small against its 8 GiB maximum:
  1.00% at 516 MiB and 1.16% at 620 MiB.
- **G1 grew the heap once four collections had gone over it,** in every run that grew (in one
  JDK 21 run the fourth logs as 1.00%, over by less than the log's rounding):

  | JDK | Runs that grew | Grew at collection | Short-term ratios of collections 2–6 |
  | --- | --- | --- | --- |
  | 21 | 2 of 5 | 8, 12 | collection 2 under 0.1%, 3 at 1.79–2.13%, 4–6 at 0.70–1.12% |
  | 25 | 5 of 5 | 5 in all five; one run grew again at 14, to 744 MiB | 1.05–1.54% |

- **JDK 21's sizing is bimodal because it sits at the edge.** Its early ratios hover just under
  1%, so whether it reaches four over-threshold collections is chance: 2 of 5 runs here and 0 of
  10 in round M. With JFR it grew in all ten C3 runs, twice on to 744 MiB.
- **JDK 25's early pauses are longer, not closer together.** In the burst at 4.0–5.3 s, the
  median pause is 2.28 ms on JDK 25 against 1.91 ms on JDK 21, while the median interval between
  collections is 180 against 172 ms.
- **The extra time is in evacuation.** Median phase times in that burst: Evacuate Collection Set
  1.46 ms on JDK 25 against 1.11 ms on JDK 21; Pre Evacuate (0.09 against 0.10), Merge Heap Roots
  (0.07 against 0.06), Post Evacuate (0.42 against 0.41) and Other (0.09 against 0.07) match.
  Whether that is root scanning, for instance of JDK 25's extra classes, or object copying is
  not established. `-Xlog:gc+phases=debug` splits evacuation into its sub-phases, about five
  minutes of runs.
- JDK 25's first collection also comes later, at 4.0–4.2 s against JDK 21's 0.87–1.10 s, but
  G1's ratio for JDK 25's first two collections is below 0.12%, so the first collection's timing
  does not decide the growth.

The follow-ups' configuration, runner log and jar hashes were saved in
`profiling/run-2M-20261026-f620/study/`. Raw data was written to
`profiling/run-2M-20261026-f620/` and `profiling/run-500k-20261026-ergo/`, which are gitignored.

## Not established

- ~~Why JDK 25 crosses the metadata threshold at startup and JDK 21 does not.~~ **2026-10-25:**
  it loads about 420 more classes ([why](#2026-10-25--why-half-the-metaspace-threshold)); ~~*which* classes, and why, is still not established.~~
  **2026-10-25:** mostly the ClassFile API and the FFM API ([which classes](#which-classes-and-why)),
  and it crosses only while JFR is recording ([without JFR](#2026-10-25--without-jfr-the-result-reverses)).
- ~~Why JDK 25, without JFR, expands its heap one step further than JDK 21: 620 against 516 MiB,
  with JDK 21 reaching 620 in one of six starts. With JFR and no cycle (`m64`), the same one-step
  gap appears at 744 against 620.~~ **2026-10-26:** G1 grows the heap after four collections over
  a 1% pause-time threshold; JDK 25's early pauses are ~0.4 ms longer and cross it, JDK 21's sit
  just under ([follow-ups](#why-jdk-25-grows-its-heap-and-jdk-21-does-not-ergo)).
- Why JDK 25's early young pauses spend ~0.35 ms longer in evacuation: root scanning or object
  copying. `-Xlog:gc+phases=debug` would split it.
- ~~Process memory with JFR: RSS and NMT were measured only without it.~~ **2026-10-26:** RSS
  with JFR was measured in C3 ([rerun](#memory-without-jfr-m-and-rss)); NMT with JFR still was not.
- ~~What causes JDK 25's ~3% throughput gain in the rerun: code generation, or the larger young
  generation it runs without JFR. A pinned-heap round without JFR (C2) would settle it.~~
  **2026-10-26:** not GC sizing; at equal heap, with identical collections, JDK 25 stays 2–3%
  ahead ([follow-ups](#throughput-at-equal-heap-f620)).
- Which part of the runtime gives JDK 25 its 2–3%: JIT-compiled code, the JDK libraries the
  engine calls, or both.
- Any other application. Whether a JDK crosses the metaspace threshold depends on how many
  classes the application, its libraries and its tooling load, so neither direction of the heap
  result says anything about an application with more classes or a fuller old generation.
- Whether that cycle causes the occasional 10–12 ms warmup pause on JDK 25. **2026-10-26:** no
  such pause in the rerun; JDK 25's worst was 6.34 ms, after 10 s.
- That JEP 423 is why GCLocker collections disappear — consistent, not tested.
- ~~A throughput difference smaller than this session's noise, about 4%. Resolving 1–2% would
  take far more than five runs per arm.~~ **2026-10-26:** the rerun's noise was 0.10% between
  twins, and it resolved ~3% ([rerun](#throughput-c1-1)). A difference of 1–2% would still need
  more runs.
- Anything beyond this machine, this board size (201×201) and this workload.

## Reproducing

*Updated 2026-10-10, after the `java25` branch was merged into `main`: the arms are rebuilt
from the release tags `java-21` and `java-25`, which pin them to the exact code measured.*

The rig is in `tools/bench/` on `main`: `drive/GuiDrive.java`, `drive/GateCheck.java`, and
the templates `bench.conf.example` and `jdks.conf.example`. The runner, the JFR parser and
the method are the project-level `jdk-performance-comparison` skill in
`.claude/skills/jdk-performance-comparison/`. The `java-21` tag predates the rig, so the JDK 21
jar comes from the tag and everything else from the `main` checkout. Commands run from the
repository root on `main`.

1. Build each release from its tag, with its own JDK:

   ```bash
   git worktree add --detach /tmp/b21 java-21
   git worktree add --detach /tmp/b25 java-25
   JAVA_HOME="$(jenv prefix openjdk64-21.0.11)" mvn -q -DskipTests package -f /tmp/b21/pom.xml
   JAVA_HOME="$(jenv prefix openjdk64-25.0.3)"  mvn -q -DskipTests package -f /tmp/b25/pom.xml
   ```

2. Stage each arm's jar as `/tmp/jdkcmp-<arm>/bench-jar.jar`: one copy of the release-21 jar
   for `21`, `21b` and `25`, the release-25 jar for `25s`. ~~Builds are not byte-reproducible
   (`pom.xml` sets no `outputTimestamp`), so a rebuilt jar will not match the hashes above —
   what matters is that the first three arms share one file:~~ **2026-10-26:** builds are
   byte-reproducible: Maven resolves a fixed `project.build.outputTimestamp`. `java-21` gave
   `00caae1f…` on 2026-10-10, 2026-10-25 and twice on 2026-10-26, and `java-25` gave
   `7e9eb5e8…` both times it was rebuilt on 2026-10-26. The 2026-10-01 hashes above differ,
   for a reason not established. What matters is still that the first three arms share one file:

   ```bash
   for a in 21 21b 25; do mkdir -p /tmp/jdkcmp-$a; cp /tmp/b21/target/GuiLife.jar /tmp/jdkcmp-$a/bench-jar.jar; done
   mkdir -p /tmp/jdkcmp-25s; cp /tmp/b25/target/GuiLife.jar /tmp/jdkcmp-25s/bench-jar.jar
   shasum -a 256 /tmp/jdkcmp-*/bench-jar.jar
   ```

3. Compile the drivers once at the lowest release, so one build runs on both JVMs:
   `javac --release 21 -cp /tmp/b21/target/GuiLife.jar -d tools/bench/out tools/bench/drive/*.java`.
4. Check that every JVM computes the same board before timing anything. All three lines must
   print the same final state — population 346, alive hash −183858688, ever-alive hash
   −1980827136:

   ```bash
   J21="$(jenv prefix openjdk64-21.0.11)"; J25="$(jenv prefix openjdk64-25.0.3)"
   "$J21/bin/java" -cp tools/bench/out:/tmp/jdkcmp-21/bench-jar.jar  drive.GateCheck 50000
   "$J25/bin/java" -cp tools/bench/out:/tmp/jdkcmp-25/bench-jar.jar  drive.GateCheck 50000
   "$J25/bin/java" -cp tools/bench/out:/tmp/jdkcmp-25s/bench-jar.jar drive.GateCheck 50000
   ```

   Call each JDK by its full path. A bare `java` goes through jenv's shim, which a
   `.java-version` file can point at a different release altogether.
5. Copy `bench.conf.example` to `bench.conf` unchanged, and `jdks.conf.example` to
   `jdks.conf`; the template carries arms `21`, `25` and `25s` on the tags, so add
   `21b  openjdk64-21.0.11  java-21` for the A/A twin. The runner resolves each tag to its
   commit before running and records both in every result row.
6. Run, and leave the machine alone — foreground placement on macOS moves this workload far
   more than the JDK does:

```bash
.claude/skills/jdk-performance-comparison/scripts/run-matrix.sh \
    --rounds C1,C3 --reps 5 --units 2000000 \
    --jdks tools/bench/jdks.conf --bench tools/bench/bench.conf \
    --out profiling/run-2M-<date>-jdk
```
