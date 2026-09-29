# Profiling findings

Analysis of a VisualVM CPU snapshot and a JVM thread dump captured on 2026-07-31.

> **Superseded in part by a clean profile.** These findings come from an *instrumented*
> recording. A later JFR sampling run (P2-4 in
> [profiling-issues-fixing-progress.md](profiling-issues-fixing-progress.md)) confirmed
> A1, A2, P1-1 and P1-3, but showed that **P1-2's premise does not hold**: the simulation
> thread accounts for 1 execution sample in 2,595 and every barrier wait is microseconds.
> The barrier is not the bottleneck; the 17.3% CPU below was largely instrumentation
> overhead on that thread. See [P1-2](#p1-2--the-per-generation-barrier-is-the-parallelism-ceiling)
> for the correction in place.

## Where each finding stands

Details are in [profiling-issues-fixing-progress.md](profiling-issues-fixing-progress.md).
Class names, line numbers and thread names below are quoted as captured on 2026-07-31:
`LifeRules` then lived in `com.conway.engine`, `ruleAt` has since moved to `BandKernel`, and
the band workers were a `ThreadPoolExecutor` whose threads were named `conway-worker-N`.

| Finding | Status |
| --- | --- |
| A1, A2 | Profiler artifacts, confirmed by the clean profile. No action |
| P1-1 | Fixed: the renderer redraws only cells whose color changed |
| P1-2 | Premise withdrawn. The band count is now capped by the active region, and the calling thread computes one band itself |
| P1-3 | Fixed, then superseded: the band workers are now a `ForkJoinPool` with parallelism `min(maxPartitions, cores)` |
| P2-4 | Done: two JFR re-profiles, one CPU-sampled and one wall-clock |
| P2-5 | Done: the population is counted during the band pass, so `Generation.population()` is O(1) |
| P2-6, P3-7..10, H-11..16 | Context only. No action |

## Source artifacts

| File | What it is | Captured |
| --- | --- | --- |
| `conway-game-of-life-gui.nps` | VisualVM/NetBeans **instrumented** CPU snapshot: 155.7 s, 299 instrumented methods, 22 threads | ended 13:26:24 |
| `thread_dump.txt` | Full JVM thread dump, OpenJDK 25.0.4, 51 threads | 13:24:51 — **during** the snapshot window |

Profiler configuration, from the settings block at the tail of the `.nps`:

```
profiler.settings.cpu.profiling.type=0                  # 0 = instrumented, not sampled
profiler.settings.istrumentation.root.methods-0=com.conway.**,*,
profiler.settings.instrumentation.filter.VALUE=java.**, javax.**, jdk.**, ...
profiler.settings.thread.cpu.timer.on=true              # two timestamps per call
profiler.settings.cpu.exclude.wait.time=true
```

Every method in `com.conway.**` was instrumented, with a thread-CPU-time reading on both
entry and exit. That choice determines how the numbers must be read — see
[Measurement artifacts](#measurement-artifacts).

The dump overlapping the snapshot window is what makes this pair useful: the dump
independently confirms two conclusions the snapshot alone could only suggest.

> **Note on the snapshot's coverage.** The profiler attached roughly 338 s into a 413 s JVM
> lifetime (agent threads show `elapsed=75.78s`), so the application ran uninstrumented for
> its first ~5.5 minutes. Lifetime CPU totals in the dump therefore mix instrumented and
> clean periods; treat them as an upper bound on real cost.

## Reproducing the analysis

An `.nps` file is `nBpRoFiLeR` magic, a 24-byte header, then a zlib stream. The payload is a
serialized `CPUResultsSnapshot`, so the profiler's own reader parses it — no need to
reverse-engineer the calling-context tree:

```java
byte[] payload = inflate(Files.readAllBytes(path), 10 + 2 + 4 + 4 + 4);
CPUResultsSnapshot snapshot = new CPUResultsSnapshot();
snapshot.readFromStream(new DataInputStream(new ByteArrayInputStream(payload)));
snapshot.getFlatProfile(threadIds, CPUResultsSnapshot.METHOD_LEVEL_VIEW);
```

Requires `org.netbeans.modules:org-netbeans-lib-profiler:RELEASE210`. `FlatProfileContainer`
leaves `percent[]` null — it is populated by the UI layer — so derive percentages from
`getWholeGraphNetTime0()`.

---

## Priority summary

**There are no P0 findings.** Neither artifact shows a correctness fault, deadlock, leak, or
production blocker. Every finding below is performance or design.

| Priority | Count | Theme |
| --- | --- | --- |
| ⛔ Artifacts | 2 | Do **not** act on these — they measure the profiler |
| 🔴 P1 | 3 | Rendering does full-board work per frame; the barrier caps parallelism; pool oversized |
| 🟡 P2 | 3 | Profiling methodology, a redundant per-frame scan, headroom context |
| 🟢 P3 | 4 | Informational |
| ✅ Healthy | 6 | Confirmed working — no action |

---

## Measurement artifacts

Acting on these would make the code worse. Both are confirmed by the dump, not merely
suspected from the snapshot.

### A1 — `ruleAt` and `colorOf` "hot spots" are instrumentation overhead

The snapshot ranks `LifeRules.ruleAt` (now `BandKernel.ruleAt`) at 20% self time (67.8 s)
and `BoardView.colorOf` at 7.4% (24.8 s). Their invocation counts are 125,973,820 and
56,601,801, giving **539 ns and 438 ns per call** for methods that perform a few array
reads.

The dump caught *both* RUNNABLE application threads inside the profiler's own probe,
wrapping precisely these two methods:

```
at org.graalvm.visualvm.lib.jfluid.server.system.Timers.getThreadCPUTimeInNanos(Native Method)
at org.graalvm.visualvm.lib.jfluid.server.ProfilerRuntimeCPU.writeTimeStampedEvent(...)
at org.graalvm.visualvm.lib.jfluid.server.ProfilerRuntimeCPUFullInstr.rootMethodEntry(...)
at com.conway.ui.BoardView.colorOf(BoardView.java:189)         <- AWT-EventQueue-0
at com.conway.engine.LifeRules.ruleAt(LifeRules.java:201)      <- conway-worker-2
```

Both are correctly written tight leaf methods. Instrumenting a method called 126 M times per
session is pathological; the cost attributed to them does not exist at runtime.

### A2 — Throughput figures in the snapshot are distorted

| Metric | Snapshot | Reality |
| --- | --- | --- |
| Generations/s | ~1,000 | 16,227 (uninstrumented benchmark, same machine and board) |
| Repaints | 1,401 in 155.7 s ≈ 9 fps | 60 fps configured |
| `refresh()` calls | 2,140 | ~9,340 due at a 16 ms timer |

The Swing `Timer` coalesced heavily because the EDT could not keep up under instrumentation.
None of these rates reflect production behavior.

---

## P1 — Act on these

### P1-1 — The renderer ignores the pruning the engine already computes

*Sources: snapshot + dump. High confidence.*

- `colorOf`: 56,601,801 ÷ 1,401 renders = **exactly 40,401 per frame** — the entire 201×201
  board, unconditionally, every frame.
- `ruleAt`: ~800 cells evaluated per generation — roughly 2% of the board.
- `renderInto` is 55.9% of EDT self time and `colorOf` 42.6% — together ~98% of everything
  the EDT did.
- Dump: `AWT-EventQueue-0` `cpu=72,668 ms` ≈ `conway-simulation` `cpu=71,460 ms`. Rendering
  costs as much as the entire simulation control path.
- Dump: the EDT was caught mid-`renderInto`; `Java2D Queue Flusher` burned 9.85 s pushing
  full-board blits.

`BoardView.renderInto` performs a whole-buffer `Arrays.fill` plus a complete 201×201 sweep
regardless of how little changed, while `SimulationEngine` already tracks an `ActiveRegion`.

**Fix:** diff the current and previously displayed `Generation` — both are already held as
fields, and snapshots are immutable — and rewrite only the cells that changed. Per-frame cost
drops from O(board) to O(changed).

### P1-2 — The per-generation barrier is the parallelism ceiling

> ⚠️ **This finding did not survive a clean profile.** Under JFR sampling the simulation
> thread accounts for 1 sample in 2,595, and only one `ThreadPark` event in 60 s exceeded the
> 10 ms threshold across 1.09 million generations — so barrier waits are microseconds. The
> 17.3% CPU figure below is largely instrumentation overhead on the simulation thread, since
> every `next`, `adopt` and `merge` call carried a probe. A coordinator that submits work and
> parks is the design working, not a bottleneck. The real scaling limit is that `computeBand`
> stops scaling past ~4 threads at this working-set size. Retained here as a record of what
> the instrumented artifacts showed.

*Source: dump. Proven, not inferred.*

```
"conway-simulation" #26 daemon prio=5 cpu=71460.36ms elapsed=413.35s
   java.lang.Thread.State: WAITING (parking)
        at jdk.internal.misc.Unsafe.park(Native Method)
        - parking to wait for <0x0000000352571800> (a java.util.concurrent.FutureTask)
        at java.util.concurrent.FutureTask.get(...)
        at java.util.concurrent.AbstractExecutorService.invokeAll(...)
        at com.conway.engine.LifeRules.next(LifeRules.java:80)
```

`LifeRules.java:80` is the `invokeAll` join. Utilization is **71,460 ms / 413,350 ms =
17.3%** — the simulation thread is blocked roughly 83% of its life waiting for workers.

This is the mechanism behind the 1.15× speedup ceiling measured at 201×201, and it explains
why the snapshot reports `next` as 98.5% of that thread's self time: it is wait, not CPU.
`cpu.exclude.wait.time=true` evidently does not cover executor parking.

**No partition count fixes this.** The options are a larger board — the planner already
scales up, and 1601 reaches 5.1× — or removing the per-generation barrier. Running
single-threaded at this size costs about 13% and deletes the coordination entirely.

### P1-3 — Worker pool is five times oversized

*Source: dump.*

20 `conway-worker-N` threads exist. At dump time **3 were RUNNABLE** (workers 2, 4, 12)
against 4 configured partitions; the other 17 were parked in `LinkedBlockingQueue.poll` via
`ThreadPoolExecutor.getTask`.

The pool is sized to `maxPartitions` (20 = 2 × cores) rather than the partition count
actually in use. Cost is ~20 thread stacks and scheduler churn rather than CPU, but the
sizing is wrong.

---

## P2 — Worth doing

### P2-4 — Re-profile with sampling or JFR

Full instrumentation is the wrong instrument for a workload issuing 126 M calls per session
to a leaf method: it distorts precisely the code under investigation. Sampling, or JFR with
the Java Monitor Blocked event, would show where time actually goes at full speed and would
separate barrier wait from CPU without needing a correlated thread dump.

### P2-5 — `Generation.population()` runs an O(n) scan every frame

2,140 calls totaling 42.9 ms, driving the status bar via `LifeFrame.refresh()`. At 40,401
cells and 60 fps that is ~2.4 M boolean reads per second. Negligible in absolute terms today,
but it is redundant: the engine could maintain the count incrementally as generations are
produced.

### P2-6 — The machine has substantial headroom

*Source: dump.* Workers 724 s + simulation 71 s + EDT 73 s ≈ 870 s CPU over 413 s wall,
about **2.1 of 10 cores**. The workload is latency-bound at the barrier, not throughput-bound.
Worth recording before anyone proposes "add more threads" as a remedy.

---

## P3 — Informational

| # | Finding | Source |
| --- | --- | --- |
| P3-7 | JIT warmup: C2 4.77 s, C1 1.14 s. Normal. | dump |
| P3-8 | Workers created ~68 s into the JVM's life — lazy `ThreadPoolExecutor` thread creation on first task. Expected. | dump |
| P3-9 | `AppKit Thread` 3.05 s, `TimerQueue` 631 ms. Unremarkable. | dump |
| P3-10 | `ActiveRegion` call ratios match the design exactly: `isEmpty` 10 per generation, `merge` 4 (one per band), `pad` 1. | snapshot |

---

## Confirmed healthy — no action

| # | Finding | Source |
| --- | --- | --- |
| H-11 | **No deadlocks**, no blocked monitors, no lock contention anywhere in the dump. | dump |
| H-12 | **GC is negligible** — ~3.5 s across 9 threads over 413 s. The array-adoption design in `Generation` is working; there is no allocation churn to chase. | dump |
| H-13 | **Active-region pruning is doing heavy lifting** — ~800 cells evaluated per generation against 40,401 on the board. | snapshot |
| H-14 | **Repaint-skip works** — 2,140 `setGeneration` calls produced only 1,401 paints; the reference-identity check suppresses redundant repaints. | snapshot |
| H-15 | **Partition planner held** — exactly 4 bands per generation across all 157,467 generations (629,868 ÷ 157,467), matching the recommendation for a 201 board. | snapshot |
| H-16 | **Work distribution is even** — worker CPU spans 36.02–36.50 s, a 1.3% spread across 20 threads. The shared queue starves no one. | dump |

---

## Confidence notes

- **P1-1, P1-2, P1-3** are corroborated by both artifacts independently. Highest confidence.
- **H-13** is the weakest number here. `SimulationEngine.clear()` was invoked once during the
  session, so generations after that ran on an emptier board and pull the average down. Treat
  "~2% of the board" as indicative, not precise.
- **P1-1's 40,401 cells per frame** carries no such caveat: it is exact
  (56,601,801 ÷ 1,401 = 40,401.0) and is the strongest single number in either file.
- Anything derived from wall-clock rates under instrumentation (A2) should not be quoted as a
  performance figure.
