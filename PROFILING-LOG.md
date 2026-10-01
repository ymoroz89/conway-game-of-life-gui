# Profiling log — JDK 21 vs JDK 25

One controlled comparison of the Conway GUI on JDK 21 and JDK 25, run on 2026-10-01. Raw
data was written to `profiling/run-2M-20261001-jdk/`, which is gitignored, so the numbers in
this file are the record. Every result below was derived from that data in a single pass;
the preflight figures come from the checks described under Preflight.

## Result at a glance

| Question | Answer |
| --- | --- |
| Is JDK 25 faster? | **No resolvable difference.** Two identical JDK 21 arms differ by 3.90%, as much as JDK 21 and 25 do |
| Does it use less memory? | **Yes: half the committed heap**, 296 against 620 MiB, with the same live set |
| What does that cost? | Twice the young collections. GC pause share rises from 0.30% to 0.47% of run time — negligible on both |
| Are pauses better or worse? | Steady-state p99 is ~17% lower on 25. Warmup has fewer long pauses on 25, but 3 of 10 runs had one 9.6–12.5 ms pause, against 4.0 ms at worst on 21 |
| Does compiling for release 25 matter? | **No.** A release-25 jar and a release-21 jar on JDK 25 overlap on every metric |
| Is the upgrade worth it? | **For memory, not for speed** |

## What was measured

**Code.** The band workers are virtual threads on both branches (`main` `a29e9fd`, `java25`
`3492ab9`; identical `src/`).

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
| `21` | release-21 jar built from `main` by JDK 21 | 21.0.12.1 | Baseline |
| `21b` | the same file | 21.0.12.1 | A/A twin: any delta against `21` is noise by construction |
| `25` | the same file | 25.0.4.1 | The runtime alone (`21` → `25`) |
| `25s` | release-25 jar built from `java25` by JDK 25 | 25.0.4.1 | The upgrade as shipped (`21` → `25s`) |

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
  defaults identical it is adaptive behavior at runtime.
- **Twice the collections** is the direct cost of the smaller young generation. GC pause share
  rises from 0.30% to 0.47% of run time, far under G1's own goal of 1/(1+`GCTimeRatio`) ≈ 7.7%.
  Even removing GC entirely would buy less than the A/A noise.
- **One startup old-generation cycle per JDK 25 run, none on JDK 21.** Every JDK 25 run had
  exactly one `G1Old` cycle at 3.9–4.1 s, caused by `Metadata GC Threshold` — metaspace
  reaching its initial high-water mark during startup class loading — lasting 5–7 ms with
  1.9–2.3 ms of pause. JDK 21 never reached the threshold, although `MetaspaceSize` is 21 MiB
  on both; why JDK 25 crosses it is not established. Every other collection on both JDKs was
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

## Not established

- Why JDK 25 crosses the metadata threshold at startup and JDK 21 does not.
- Whether that cycle causes the occasional 10–12 ms warmup pause on JDK 25.
- That JEP 423 is why GCLocker collections disappear — consistent, not tested.
- A throughput difference smaller than this session's noise, about 4%. Resolving 1–2% would
  take far more than five runs per arm.
- Anything beyond this machine, this board size (201×201) and this workload.

## Reproducing

The rig is in `tools/bench/` on the `java25` branch: `drive/GuiDrive.java`,
`drive/GateCheck.java`, and the templates `bench.conf.example` and `jdks.conf.example`. The
runner, the JFR parser and the method are the project-level `jdk-performance-comparison`
skill in `.claude/skills/jdk-performance-comparison/`. Commands below run from the
repository root.

1. Build `main` with JDK 21 and `java25` with JDK 25 (`mvn -DskipTests package`), and stage
   each arm's jar as `/tmp/jdkcmp-<arm>/bench-jar.jar` — the release-21 jar for `21`, `21b`
   and `25`, the release-25 jar for `25s`. Check the first three are sha256-identical.
2. Compile the drivers once at the lowest release, so one build runs on both JVMs:
   `javac --release 21 -cp <release-21 jar> -d tools/bench/out tools/bench/drive/*.java`.
3. Copy `bench.conf.example` to `bench.conf` unchanged; write `jdks.conf` with the four arms
   in the table above.
4. Run, and leave the machine alone — foreground placement on macOS moves this workload far
   more than the JDK does:

```bash
.claude/skills/jdk-performance-comparison/scripts/run-matrix.sh \
    --rounds C1,C3 --reps 5 --units 2000000 \
    --jdks tools/bench/jdks.conf --bench tools/bench/bench.conf \
    --out profiling/run-2M-20261001-jdk
```
