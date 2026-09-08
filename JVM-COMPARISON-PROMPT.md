# Prompt — JVM comparison for the Conway Life engine

Paste everything below the line into a fresh session. It encodes what the first
attempt cost to learn; without it you will repeat three wasted runs and two wrong
conclusions.

---

## Task

Compare JDK 21 and JDK 25 on this Conway Game of Life GUI app — throughput, memory,
and GC — using JFR. I will drive the GUI (Start/Stop); you switch branches, build,
launch, record, analyse, and keep a log.

## Machine and toolchain facts

- **Apple M2 Pro: 6 performance + 4 efficiency cores.** `availableProcessors()` says
  10 and tells you nothing about which kind you get. Default max heap 8 GB (¼ of RAM).
- Branches: `main` = `maven.compiler.release` 21, `java25` = release 25. **The only
  non-doc difference is one comment line** — verify with
  `git diff main java25 -- src/`. It is a pure JDK comparison.
- Runtimes, via jenv:
  | jenv name | Actually is |
  |---|---|
  | `openjdk64-21.0.11` | Homebrew OpenJDK **21.0.12.1** |
  | `openjdk64-25.0.3` | Homebrew OpenJDK **25.0.4.1** |
- App default config: `grid 201x201, cell 4px, target 60 fps, partitions 4 of at most 20`.
  Board is two Gosper glider guns, deterministic. Active region saturates at 98–99% of
  the grid within ~50k generations, so board evolution is **not** a confound.

## Rules that cost real time to learn

**1. Never trust a JDK label. Verify from the recording, after the fact.**

```bash
jfr print --events JVMInformation <file>.jfr | grep jvmVersion
jfr print --events NativeLibrary <file>.jfr | grep -o '[^"]*libjvm.dylib'
```

Three runs were thrown away because the JVM was not what the run was called. The traps:

| Trap | What happens |
|---|---|
| `.idea/misc.xml` → `project-jdk-name="graalvm-25"` | A "default JRE" run config silently uses GraalVM |
| `.idea/workspace.xml` → `ALTERNATIVE_JRE_PATH=homebrew-26` | The run meant as "Java 25" executed on JDK 26 |
| `jenv local 25.0.4` | Selects **GraalVM 25**, not OpenJDK — always use `openjdk64-*` names |
| A Java 21 jar on a 25 JVM | Runs fine. No `UnsupportedClassVersionError` to expose the mismatch |

**2. Core placement is worth 3.3×. Never compare across environments.**
Same JDK 21, same workload: foreground GUI 20,575 gen/s, terminal 17,600, background
QoS (`taskpolicy -b`, E-cores) 5,400. The JDK effect is ~9%. Mixing environments swamps
the signal — headless benchmark numbers and GUI numbers are **not comparable**.

**3. Run-to-run variance is ~6%.** Two runs of the *same* JDK on the same code path
came out 12,146 and 12,853. One run per arm cannot resolve a 9% effect. Use ≥4
repetitions, interleaved by repetition so drift hits both arms equally.

**4. Do not use the IntelliJ profiler.** Its async-profiler JVMTI hooks fire on every
virtual-thread mount (`VirtualThread.notifyJvmtiMount` shows up in the recordings), and
mount/unmount is exactly what this workload is bound by. Pure JFR only.

**5. Use `settings=profile`.** The default settings record zero `jdk.GarbageCollection`
events, leaving no pause durations and forcing GC counts to be inferred from heap
summaries.

**6. Throughput ground truth is the app's own log line**, not JFR inference:
`Run finished: N generations in T s using P partition(s), R generations/s`.

**7. When analysing recordings: `jdk.G1HeapSummary` timestamps sit on a different clock
base than `jdk.GCHeapSummary`** in these files (~7.7 h offset). Align by offset before
filtering to a time window, or the window will come back empty.

## Method

Per arm — same flags both times, only the JDK and filename change:

```bash
git checkout main                        # or java25
jenv local openjdk64-21.0.11             # or openjdk64-25.0.3
JAVA_HOME="$(jenv prefix)" mvn -q -DskipTests clean package
javap -v -cp target/GuiLife.jar com.conway.Main | grep 'major version'   # 65 or 69

java -XX:+UseG1GC -XX:FlightRecorderOptions=stackdepth=128 \
  -XX:StartFlightRecording=name=life,settings=profile,\
filename=profiling/<name>.jfr,dumponexit=true,maxsize=500m \
  -jar target/GuiLife.jar
```

Held constant: same vendor (Homebrew) both arms — never mix in GraalVM, that compares
JIT compilers not JDK versions; `-XX:+UseG1GC` stated explicitly; heap left at defaults
(equal 8 GB budget, and how each JVM sizes eden inside it is the finding, not a confound).

I will run each arm at 4, 8 and 16 partitions, ~80 s each, then Quit. `dumponexit`
writes the recording.

For headless work use `com.conway.bench.GenerationBenchmark <grid> <generations> virtual`.
Note it **resets to the seed board for every measurement and reports best-of-5** over a
short window — that is a different measurement from the GUI's continuous multi-million
generation run. Pass ≥20000 generations; the 300 default is far too short.

## Extract from each recording

Per run window: generations/s, CPU per generation (`jdk.CPULoad` × window ÷ generations),
eden consumed and bytes/generation (sum `edenUsedSize` at "Before GC"), committed heap
and eden capacity p50, post-GC live set, GC count and rate, promotion
(`jdk.PromoteObjectInNewPLAB` + `OutsidePLAB`), pause total/p50/p99, top allocation
classes and sites. Use the `jdk.jfr.consumer` API via single-file source launch
(`java Analyze.java ...`) — `jfr print` output is too large to parse.

Bucket allocation rate into 20 s windows to confirm both arms reach steady state and
neither is thermally skewed.

## Reference results to reproduce

**Memory — replicated, the strongest finding.** GUI, 4 partitions:

| | Java 21 | Java 25 |
|---|---|---|
| Committed heap p50 | 650 MB / 541 MB | **302 MB both rounds** |
| Eden capacity p50 | 390 / 319 MB | 180 MB both |
| Live set after GC | ~13 MB | ~13 MB |
| Collections | 532 / 442 | 1473 / 504 |
| Promoted | 104 / 83 MB | 272 / 97 MB |

Nearly all committed heap is eden headroom, so this is purely G1 young-gen sizing policy.

**Throughput — depends on environment, and this is the interesting part:**

| Environment | Java 21 | Java 25 | Winner |
|---|---|---|---|
| Background QoS, E-cores | 5,400 | ~6,250 * | 25 by ~16% |
| Terminal, normal QoS | 17,600–18,114 | 19,260–19,715 | 25 by ~9% |
| Foreground GUI, P-cores | 20,575–20,781 | 19,657–20,098 | 21 by ~3% |

\* one partial run (3 of 4 segments) — **confirm this first.**

**Allocation — identical on every run.** 82,074–83,373 bytes/generation, 98.7–98.9%
`[Z`. That is two fresh `boolean[40401]` per `LifeRules.next` call (2 × 40,417 B =
80,834 B) plus ~1–2 KB of `BandResult`/`ActiveRegion` overhead. No JDK changes this;
only double-buffering those arrays would.

**Partition scaling.** Single-partition throughput is identical on both JDKs, so the
Life kernel compiles equally well. Gains stop after 2 partitions (a generation is
~48–50 µs, dominated by dispatch and join). Java 25's headless advantage is entirely in
virtual-thread dispatch.

## The open question — start here

**The more CPU-starved the environment, the more Java 25 wins; the more CPU-rich, the
more Java 21 wins.** The three-row table above is monotonic. Mechanism is unproven.

Two tests, in order:

1. Repeat the E-core arm properly — both JDKs, 4 repetitions each, full 1M generations
   under `taskpolicy -b`. Confirms or kills the trend.
2. Give the headless harness a visible AWT window so macOS treats it as a foreground
   app, then re-run. If Java 21 climbs from ~17,600 to ~20,500, the cause is QoS/core
   placement. If it stays at ~17,600, then contention with the Swing event thread and
   Metal render queue is doing the work — a genuinely interesting property of the two
   virtual-thread schedulers under load.

## Do not re-assert these — they were withdrawn

- ~~"Java 21 is 7% faster"~~ — from one GUI run per arm, variance the size of the effect.
  The direction happens to be right *for the GUI*, but establish it properly.
- ~~"JFR profiling penalises Java 21 ten times harder"~~ — from 3 reps. Real figures at
  7 reps: −3.5% on 21, −1.7% on 25. About 2×.
- ~~"Java 25 cuts pause p99 by 69%"~~ — did not replicate; round 2 reversed it. Median
  pause does hold (0.43–0.44 ms on 25 vs 0.55–0.58 ms on 21).

## Output

Track every action in `PROFILING-LOG.md` at project level — commands, verified JDK per
recording, and results. When a claim is overturned, mark it withdrawn in place rather
than deleting it; the corrections are the most useful part of the log.

Snapshots go in `profiling/` (gitignored). Note that `jenv local` writes `.java-version`
into the project root.
