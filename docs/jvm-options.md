# Production JVM options

A checklist of JVM flags for running this application, organized by situation. Every
flag line in this document was started on both JDK 21 (the project minimum) and JDK 25,
and every performance claim was measured on this codebase. The conditions are recorded
under [Evidence](#evidence), so any number here can be reproduced — or overturned.

Where something has **not** been measured, it says so.

## Which flags for which case

| Case | Add | Why |
| --- | --- | --- |
| Every production run | the [baseline](#1-every-production-run) | Bounded heap, crash artifacts, a GC log |
| Interactive use, default board | `-XX:+UseG1GC -XX:MaxGCPauseMillis=20` | G1's worst pause (7.3 ms) fits inside a 60 fps frame |
| Smoothest possible frames | `-XX:+UseZGC`, plus `-XX:+ZGenerational` on JDK 21 | Pauses of ~0.02 ms at the same throughput |
| Boards above a few thousand cells | ZGC and a heap [sized from the board](#4-large-boards) | G1 pauses grow with the heap; the pixel buffer grows with board² |
| Headless benchmark | `-XX:+UseParallelGC` | The throughput collector; pauses do not matter without a UI |
| Faster startup (JDK 25 only) | `-XX:AOTCacheOutput`, then `-XX:AOTCache` | Classes loaded and linked ahead of time — not measured here |
| Container or CI | `-XX:MaxRAMPercentage`, `-XX:ActiveProcessorCount` | Partitioning follows the CPU count the JVM reports |
| Investigating a problem | the [diagnostic flags](#8-investigating-a-problem) | Temporary; each has a cost |

## Pre-flight checklist

- [ ] `-Xmx` is set explicitly. Without it, the JVM on the reference machine allows an
      **8 GB** heap — a quarter of its 32 GB of RAM.
- [ ] The heap is sized from the board if the board is larger than the default
      ([formula](#4-large-boards)).
- [ ] Any directory an `-Xlog` file writes into **exists before launch**. The JVM refuses
      to start otherwise, on both JDK 21 and 25 — and this application's `logs/` is created
      by logback only *after* startup, so it does not exist on a fresh clone.
- [ ] The `HeapDumpPath` directory exists. Unlike `-Xlog` it is not checked at startup —
      only at the moment of an OutOfMemoryError, the worst time to discover it is missing.
- [ ] On JDK 21, ZGC is paired with `-XX:+ZGenerational`. On JDK 25 it is not: the flag
      is ignored there with a warning.
- [ ] No expired flags copied from an old guide. Six common ones
      [stop the JVM outright](#flags-that-stop-the-jvm-from-starting).
- [ ] `-XX:MaxRAMFraction` is replaced by `-XX:MaxRAMPercentage`. The former still starts
      on JDK 21 with a warning, and **fails** on JDK 25.
- [ ] If JFR runs continuously, it uses `settings=default`, and its measured ~6%
      throughput cost is acceptable.

## 1. Every production run

```bash
mkdir -p logs
java -Xms256m -Xmx1g \
     -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs \
     -XX:ErrorFile=logs/hs_err_%p.log \
     -Xlog:gc:file=logs/gc.log:time,uptime:filecount=5,filesize=10m \
     -jar target/GuiLife.jar
```

- **`-Xmx1g`** bounds the heap. The engine's live set on the default board is a few
  hundred kilobytes, so a gigabyte is ample — see [large boards](#4-large-boards) when it
  is not.
- **`-Xms256m`** is not a bound. The JVM's own default initial heap on the reference
  machine is 512 MB, so this *lowers* the starting footprint. Leave it out, or set it
  equal to `-Xmx` to avoid heap resizing; either is defensible.
- **Heap dump and error file** cost nothing until something goes wrong, and are the only
  evidence left afterwards.
- **`-Xlog:gc`** writes one summary line per collection, rotated across five 10 MB
  files. The default board runs about nine collections a second. `-Xlog:async` (JDK 17+)
  moves log writes off the collecting threads; it starts cleanly on both JDKs but its
  effect here has not been measured.

Optionally, a continuous flight recording that is dumped on exit:

```bash
-XX:StartFlightRecording=settings=default,maxage=1h,maxsize=250m,disk=true,dumponexit=true,filename=logs/app.jfr
```

It is **not free**: `settings=default` cost 6.1% of throughput here, `settings=profile`
8.2%. The often-quoted ~1% does not hold for this workload, because each of the ~9
collections a second emits its own events. The cost is invisible to a user — the
simulation already runs over 250× faster than the display — but it is real. To record
only when needed instead: `jcmd <pid> JFR.start duration=60s filename=rec.jfr`.

## 2. Interactive use, default board

```bash
-XX:+UseG1GC -XX:MaxGCPauseMillis=20
```

G1 is already the default collector on the reference machine; the flag only states it.
Measured on the default board: a median pause of **0.51 ms**, a worst of **7.28 ms**,
and 60–63 ms of pause in total per 12 seconds. Every pause fits inside a 16.7 ms frame.

`MaxGCPauseMillis=20` is **inert** at this board: no pause came near 20 ms. It is also a
goal that G1 steers toward, not a cap — on the largest board with a 3 GB heap, G1
paused for 23.6 ms regardless.

## 3. Smoothest possible frames

```bash
-XX:+UseZGC                        # JDK 25
-XX:+UseZGC -XX:+ZGenerational     # JDK 21
```

On the default board ZGC's worst pause was **0.02 ms**, against G1's 7.28 ms, at the same
throughput (16,292–16,648 generations/s versus G1's 16,384). There were **no allocation
stalls** in either ZGC mode. This removes the collector from the frame-time budget
entirely.

Why `-XX:+ZGenerational` on JDK 21, when it did not measure better? On JDK 21 that flag
defaults to `false`, so `-XX:+UseZGC` on its own selects the legacy, non-generational
mode. The two modes measured the same here, even at the 6.6 GB/s allocation rate of the
largest board. Choose generational because it is the only mode from JDK 24 onwards, so a
JDK 21 deployment then behaves like its JDK 25 successor. That is a compatibility
argument, not a performance one.

Not measured: ZGC's memory footprint relative to G1.

## 4. Large boards

The heap has three consumers once the GUI is running, and the size of each follows from
the code:

| Consumer | Bytes at N×N | At 10,000×10,000 |
| --- | --- | --- |
| Engine: the current generation and the one being computed | 4·N² | 400 MB |
| `BoardView`: up to two further generations (`displayed`, `rendered`) | up to 4·N² | up to 400 MB |
| `BoardView`: the pixel buffer, 4 bytes per pixel at device scale *s* | 4·N²·*s*² | 391 MiB measured at *s* = 1 |

That gives **8·N² + 4·N²·*s*²** bytes in the worst case, plus headroom for the collector:
about 1.2 GB at 10,000×10,000 on a 1× display, and 2.4 GB on a 2× (Retina) one. The
pixel buffer is the term people forget — at 1× it is as large as the engine's whole live
set, and at 2× it is four times larger. The 2× figure is arithmetic from `BoardView`'s
constructor; only the 1× value was measured, on this machine's reporting scale of 1.0.

The pixel-buffer term applies once a board reaches 1,000 cells across, where the cell
size bottoms out at one pixel. Below that, the buffer stays near the configured board
size in pixels and is not a concern.

Measured on the engine alone (no GUI) at 10,000×10,000, 15 seconds each:

| Configuration | Outcome | Generations/s | Pauses | Worst pause | Full GCs |
| --- | --- | --- | --- | --- | --- |
| G1, `-Xmx1g` | runs | 33 | 505 | 2.3 ms | 0 |
| G1, `-Xmx3g` | runs | 32 | 96 | **23.6 ms** | 0 |
| ZGC (legacy), `-Xmx3g` | runs | 33 | 159 | < 0.1 ms | 0 |
| ZGC (generational), `-Xmx3g` | runs | 28 | 120 | < 0.1 ms | 0 |

Two things follow. The engine alone fits in 1 GB even at the maximum board — it is the
GUI's additions that do not. And G1's pauses *grow* with the heap: the larger young
generation means more to copy per pause, which pushed it past both the 20 ms goal and a
60 fps frame. So when the heap has to grow for a large board, switch collector:

```bash
-Xmx3g -XX:+UseZGC     # 10,000×10,000 on a 1× display  (+ -XX:+ZGenerational on JDK 21)
-Xmx4g -XX:+UseZGC     # 10,000×10,000 on a 2× display
```

Those sizes are derived from the formula plus room for ZGC to allocate while it
collects; the GUI itself was not run at this size. Generational ZGC's lower throughput in
this table comes from a single run and was not repeated.

## 5. Headless benchmark

```bash
-XX:+UseParallelGC
```

Measured on the default board: 17,046 generations/s, against 16,384 for G1 and
16,292–16,648 for ZGC. That lead of about 4% sits inside run-to-run noise, so the reason
to choose Parallel is that it is the throughput collector by design, not a decisive
margin. Its 662 short pauses (median 0.19 ms) are irrelevant without a UI.

To measure throughput with no collector at all — laboratory use only:

```bash
-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xmx2g
```

Epsilon never reclaims memory, so the heap only fills. A full benchmark run on the
default board allocates about 790 MB (9,800 generations × 80 KB), which is why this pairs
with `-Xmx2g`. Not measured.

## 6. Faster startup (JDK 25 only)

```bash
java -XX:AOTCacheOutput=conway.aot -jar target/GuiLife.jar   # training run; cache written at exit
java -XX:AOTCache=conway.aot -jar target/GuiLife.jar         # every run after that
```

Loads and links classes ahead of time (JEP 483, 514, 515). Both flags exist on JDK 25
and are rejected by JDK 21. **Startup time has not been measured for this application.**
Per the JEPs, a cache is tied to the JDK build and class path that produced it, so
regenerate it after changing either.

## 7. Container or CI

```bash
-XX:MaxRAMPercentage=75 -XX:ActiveProcessorCount=4
```

- **`-XX:MaxRAMPercentage`** sizes the heap from the container's memory limit instead
  of a fixed `-Xmx`. Its predecessor `-XX:MaxRAMFraction` fails on JDK 25.
- **`-XX:ActiveProcessorCount`** matters more here than in most applications, because the
  code reads `availableProcessors()` to decide how to split the board:
  `PartitionPlanner.hardwareCap` caps partitions at `min(rows, max(2, 2 × cores))`, and
  `SimulationEngine` sizes its `ForkJoinPool` at `min(maxPartitions, cores)`. The CPU
  count the JVM reports therefore sets both. Pin it to the CPUs the container is
  actually given.

This case mostly concerns the headless benchmark; the GUI needs a display.

## 8. Investigating a problem

Temporary flags, each with a cost. Remove them afterwards.

| Question | Flags |
| --- | --- |
| What is the collector doing, phase by phase? | `-Xlog:gc*:file=logs/gc-detail.log:time,uptime` |
| Are threads stuck reaching safepoints? | `-Xlog:safepoint*:file=logs/safepoint.log` |
| Where do CPU and allocation go? | `-XX:StartFlightRecording=settings=profile,filename=logs/profile.jfr` — measured 8.2% |
| Are profiler stacks accurate between safepoints? | `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` |
| Is memory growing outside the heap? | `-XX:NativeMemoryTracking=summary`, then `jcmd <pid> VM.native_memory summary` |
| Does a virtual thread pin its carrier? | JFR with `jdk.VirtualThreadPinned#enabled=true,jdk.VirtualThreadPinned#threshold=0ms` |

The last row is how pinning was checked for this project: a threshold of 0 ms records
pins of any length, where the default 20 ms would miss short ones. The event exists on
both JDK versions but does not see the same things — JDK 25's also records blocking
operations such as contended monitor enters, which JDK 21's cannot. With the current
engine, JDK 21 reported zero pins and JDK 25 reported one: 12.8 µs inside the JDK's own
class loading, not in the engine's code.

## Flags that stop the JVM from starting

Each was tried with `java <flag> -version`. **✗** means the JVM exits before running
anything, with `Unrecognized VM option` and `Could not create the Java Virtual Machine`.

| Flag | JDK 21 | JDK 25 | Use instead |
| --- | --- | --- | --- |
| `-XX:+UseConcMarkSweepGC` | ✗ | ✗ | G1 or ZGC |
| `-XX:+UseParNewGC` | ✗ | ✗ | G1 or Parallel |
| `-XX:+CMSClassUnloadingEnabled` | ✗ | ✗ | nothing — `ClassUnloading` is already `true` by default |
| `-XX:MaxPermSize=…` | ✗ | ✗ | `-XX:MaxMetaspaceSize`, if there is a real reason to cap it |
| `-XX:+AggressiveOpts` | ✗ | ✗ | nothing |
| `-XX:+UseBiasedLocking` | ✗ | ✗ | nothing |
| `-XX:MaxRAMFraction=…` | warns | ✗ | `-XX:MaxRAMPercentage` |
| `-XX:+ZGenerational` | required for generational ZGC | warns, ignored | nothing on JDK 24+ |
| `-XX:+UseCompactObjectHeaders` | ✗ | ✓ | — |
| `-XX:AOTCacheOutput`, `-XX:AOTCache` | ✗ | ✓ | — |
| `-XX:+UseEpsilonGC` on its own | ✗ | ✗ | prefix `-XX:+UnlockExperimentalVMOptions` |

`-XX:MaxRAMFraction` is the dangerous one: old container guides still recommend it, and
it passes on JDK 21 with only a deprecation warning — so the failure appears on the JDK
upgrade, not when the flag is added.

## What the JVM picks when you pass nothing

From `java -XX:+PrintFlagsFinal -version` on the reference machine, reading the rows
marked `{ergonomic}`. The values were identical on JDK 21 and 25.

| Setting | Value | Derived from |
| --- | --- | --- |
| Collector | G1 | a "server-class" machine |
| `MaxHeapSize` | 8,192 MB | a quarter of RAM |
| `InitialHeapSize` | 512 MB | 1/64 of RAM |
| `G1HeapRegionSize` | 4 MB | the heap size |
| `ConcGCThreads` | 2 | the core count |
| `CICompilerCount` | 4 | the core count |

To see the equivalent on another machine:

```bash
java -XX:+PrintFlagsFinal -version | grep ergonomic
```

Between the two JDKs, 13 flags are new in 25 — among them the `AOT*` family and
`UseCompactObjectHeaders` — and 28 are gone, including `ZGenerational` and the three
`*RAMFraction` flags.

## Considered and not worth it here

- **`-XX:+UseCompactObjectHeaders`** (JDK 25) saves four bytes per object. This heap is
  dominated by a handful of very large arrays, so the saving is negligible. Reasoned from
  the allocation profile, not measured.
- **`-XX:+UseStringDeduplication`** targets many duplicate strings; this application
  has almost none.
- **`-Xmn` or `-XX:NewSize` with G1** fixes the young generation's size, overriding the
  sizing G1 uses to meet `MaxGCPauseMillis`. Standard G1 tuning guidance; not measured.
- **`-XX:+AlwaysPreTouch`** faults in the whole heap at startup, which can remove
  first-touch latency. Whether it trims G1's 7 ms tail here has not been measured.

## Evidence

Reference machine: Apple M2 Pro, 10 cores (6 performance, 4 efficiency), 32 GB, macOS.
JDKs: OpenJDK 21.0.12.1 and 25.0.4.1 (Homebrew). A container runtime was using roughly half
a core throughout, so absolute throughput is conservative; comparisons were interleaved
so that background load fell evenly on every configuration.

**Collectors, default board.** JDK 21, `-Xms256m -Xmx1g`, the real `SimulationEngine`
running headless with four partitions, two interleaved rounds of 12 s per collector:

| Collector | Generations/s (best) | Pauses | Median | Worst | Allocation stalls |
| --- | --- | --- | --- | --- | --- |
| G1, `MaxGCPauseMillis=20` | 16,384 | 216 | 0.51 ms | 7.28 ms | — |
| ZGC, legacy | 16,648 | 186 | < 0.01 ms | 0.02 ms | 0 |
| ZGC, generational | 16,292 | 150 | 0.01 ms | 0.02 ms | 0 |
| Parallel | 17,046 | 662 | 0.19 ms | 1.73 ms | — |

**JFR overhead.** Same setup with G1, three interleaved rounds of identical 12 s length:
no recording 16,480 generations/s; `settings=default` 15,478 (−6.1%); `settings=profile`
15,123 (−8.2%). Runs within each configuration agreed to within about 3%.

**Pixel buffer.** Heap retained by constructing `BoardView` at a device scale of 1.0:
76 MiB at 4,000×4,000 and 391 MiB at 10,000×10,000. Both track 4·N² bytes, plus roughly
10 MiB of fixed Swing overhead that appears at every size.

**Large boards and flag compatibility** are in their own sections above.

A caution on reading ZGC's logs, learned the hard way while producing these numbers:
generational ZGC prints per-cycle summary rows labeled `Allocation Stalls:` whose
counters are normally all zero. Counting those lines reports stalls that never happened.
A real stall appears as its own event, `Allocation Stall (<thread>) <n>ms`.
