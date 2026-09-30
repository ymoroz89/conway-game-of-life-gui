# Profiling run log — Java 21 vs Java 25

Controlled comparison of the Conway GUI simulation engine across JDK versions.
Every action taken is recorded here in order, including the failures that made
this rerun necessary.

## Why this rerun exists

Two earlier snapshots in `~/IdeaSnapshots` were labelled "Java 21" and "Java 25".
Neither label was correct. Three independent checks disagreed with them:

| Check | `GuiLife_jar_2026_09_07_232540.jfr` | `jar_2026_09_07_233434.jfr` |
|---|---|---|
| `libjvm.dylib` path in `jdk.NativeLibrary` | `graalvm-25.jdk` | `openjdk/26.0.2.1` |
| JFR event-type fingerprint vs local JDKs | JDK 21 ruled out | JDK 21 **and** 25 ruled out |
| Bytecode major version of the jar | 69 (Java 25) — cannot load on a 21 JVM | 69 (Java 25) |

Root cause: `.idea/misc.xml` sets `project-jdk-name="graalvm-25"`, and the
`GuiLife.jar` run configuration in `.idea/workspace.xml` sets
`ALTERNATIVE_JRE_PATH=homebrew-26`. Neither run configuration ever pointed at a
JDK 21. A later attempt with a genuine Java 21 jar (`major 65`) still ran on
GraalVM 25, because a Java 21 jar loads happily on a 25 JVM — there is no
`UnsupportedClassVersionError` to expose the mismatch.

**Consequence:** the JDK is never inferred from a label in this log. It is read
back out of each recording and recorded below.

## Method

Each arm is built by its own JDK and launched from the terminal, bypassing
IntelliJ entirely.

| | Java 21 arm | Java 25 arm |
|---|---|---|
| Branch | `main` | `java25` |
| `maven.compiler.release` | 21 | 25 |
| Build + runtime JDK | Homebrew `openjdk@21` 21.0.12.1 | Homebrew `openjdk@25` 25.0.4.1 |

Held constant across both arms:

- **Same vendor** — Homebrew OpenJDK on both, both built 2026-08-18. GraalVM is
  deliberately excluded; mixing it in would compare JIT compilers, not JDK versions.
- **`-XX:+UseG1GC`** stated explicitly, so the collector is identical rather than
  merely defaulted to the same thing.
- **Heap left at defaults.** Max heap is 8 GB on both (¼ of RAM, same machine), so
  the budget is already equal. How each JVM sizes eden *inside* that budget is real
  behaviour worth measuring, not a confound to suppress.
- **No IntelliJ profiler agent.** The earlier snapshots carried async-profiler's
  JVMTI hooks, visible as `VirtualThread.notifyJvmtiMount`. Those hooks fire on
  every virtual-thread mount, and park/unpark is 33–41% of this workload — they
  distort precisely the thing that dominates it. Pure JFR only here.
- **`settings=profile`**, which records `jdk.GarbageCollection` with real pause
  durations. The earlier snapshots had zero GC events, so GC counts had to be
  inferred from heap summaries and pause times were unavailable.
- **Workload driven by hand** — the user clicks Start, lets it run, clicks Stop.
  Generation counts and elapsed time come from the app's own
  `Run finished: N generations in T s` log line, not from JFR inference.

Snapshots are written to `profiling/` (gitignored — binaries).

---

## Actions

<!-- appended in order as each step completes -->
### 2026-09-08 11:45 — Java 21 arm

**Build.** `git branch` = `main` @ `f17e236`, working tree clean.

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home
mvn -q -DskipTests clean package
```

Verified output bytecode, not assumed: `javap -v com.conway.Main` → `major version: 65`
(Java 21). Tests skipped — this is a profiling build.

**Launch.**

```
/opt/homebrew/Cellar/openjdk@21/21.0.12.1/.../bin/java \
  -XX:+UseG1GC \
  -XX:FlightRecorderOptions=stackdepth=128 \
  -XX:StartFlightRecording=name=life,settings=profile,\
filename=profiling/jdk21.jfr,dumponexit=true,maxsize=500m \
  -jar target/GuiLife.jar
```

**Runtime JDK read back out of the recording** (`jdk.JVMInformation` /
`jdk.NativeLibrary`), which is the check the earlier snapshots failed:

```
jvmVersion = OpenJDK 64-Bit Server VM (21.0.12.1) for bsd-aarch64, built by "brew"
libjvm     = /opt/homebrew/Cellar/openjdk@21/21.0.12.1/.../lib/server/libjvm.dylib
GC         = G1New / G1Old
```

Confirmed Java 21. No GraalVM, no JDK 26, no profiler agent.

**Workload** — driven by hand, config `grid 201x201, cell 4px, partitions 4 of at
most 20 (10 cores available)`:

| | |
|---|---|
| Start requested | 11:45:33.177 |
| Stop requested | 11:47:29.853 |
| Result (app's own log) | `2423829 generations in 116.638 s using 4 partition(s), 20780.9 generations/s` |

**Snapshot** — `profiling/jdk21.jfr`, 7.5 MB, 533 `jdk.G1GarbageCollection` events
with real pause durations (the earlier snapshots had none).

### 2026-09-08 11:48 — Java 25 arm

**Build.** `git checkout java25` → `d6a22ab`.

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
mvn -q -DskipTests clean package
```

Verified bytecode: `javap -v com.conway.Main` → `major version: 69` (Java 25).

**Launch.** Byte-for-byte the same JVM flags as the Java 21 arm, only the JDK path
and the output filename differ.

**Runtime JDK read back out of the recording:**

```
jvmVersion = OpenJDK 64-Bit Server VM (25.0.4.1) for bsd-aarch64, built by "brew"
libjvm     = /opt/homebrew/Cellar/openjdk@25/25.0.4.1/.../lib/server/libjvm.dylib
GC         = G1New / G1Old
```

**Workload** — same config, `grid 201x201, partitions 4`:

| | |
|---|---|
| Start requested | 11:49:05.696 |
| Stop requested | 11:51:42.724 |
| Result (app's own log) | `3086785 generations in 157.028 s using 4 partition(s), 19657.5 generations/s` |

**Snapshot** — `profiling/jdk25.jfr`, 13.5 MB, 1473 GC events in-window.

### Validity checks

**The branches are the same program.** `git diff main java25 -- src/` is a single
changed line, and it is a *comment*:

```
- * {@code GenerationBenchmark} (see docs/java21-migration.md), and it removes the entire
+  * {@code GenerationBenchmark} (see docs/java25-migration.md), and it removes the entire
```

`pom.xml` differs only in `maven.compiler.release` (21 → 25). No engine, model or UI
code differs. This is a pure JDK comparison.

**Neither run is warmup- or thermal-skewed.** The arms ran for different wall times
(117 s vs 157 s), so allocation rate was bucketed into 20-second windows. Both reach
steady state after the first bucket and stay flat — no decay in the longer run:

| t | Java 21 | Java 25 |
|---|---|---|
| 0–20 s | 1377 MB/s | 1206 MB/s |
| 20–40 s | 1745 | 1623 |
| 40–60 s | 1740 | 1621 |
| 60–80 s | 1743 | 1623 |
| 80–100 s | 1743 | 1607 |
| 100–120 s | 1736 | 1624 |
| 120–140 s | 1743 | 1610 |
| 140–160 s | — | 1623 |
| 160–180 s | — | 1579 |

Steady state: **~1740 MB/s (21) vs ~1620 MB/s (25)**. Background load was equal
(machine CPU 36.7% vs 36.5%).

---

## Results

> ⚠️ **The throughput rows in this table are superseded.** They come from one
> hand-driven run per arm. A controlled 12-run headless experiment reverses the
> direction — see [Correction](#correction-the-throughput-finding-was-wrong).
> The memory, GC and allocation rows are unaffected.

All figures are in-window (Start → Stop). Generation counts come from the app's own
counter; everything else from JFR.

| | Java 21 | Java 25 | Δ |
|---|---|---|---|
| JVM (read from recording) | OpenJDK 21.0.12.1 | OpenJDK 25.0.4.1 | |
| Generations | 2,423,829 | 3,086,785 | |
| Wall clock | 116.638 s | 157.028 s | |
| **Throughput** | **20,781 gen/s** | **19,657 gen/s** | **−5.4%** |
| Steady-state throughput | ~1740 MB/s | ~1620 MB/s | 21 faster by **7.4%** |
| Time per generation | 48.1 µs | 50.9 µs | +5.7% |
| CPU busy (of 10 cores) | 3.00 | 2.91 | −3% |
| CPU per generation | 144 µs | 148 µs | +2.8% |
| **Bytes allocated per generation** | **83,373 B** | **82,094 B** | −1.5% |
| **Committed heap (p50)** | **650 MB** | **302 MB** | **−54%** |
| Eden capacity (p50) | 390 MB | 180 MB | −54% |
| Live set after GC (p50) | 13.9 MB | 13.4 MB | −4% |
| GC collections | 532 | 1473 | +177% |
| GC rate | 4.56/s | 9.38/s | |
| Generations per GC | 4,556 | 2,096 | |
| Promoted to old gen | 104 MB (0.89 MB/s) | 272 MB (1.73 MB/s) | +94% |
| Total pause | 335 ms (0.287% of wall) | 670 ms (0.426%) | +100% |
| Pause p50 | 0.58 ms | 0.43 ms | −26% |
| **Pause p99** | **2.49 ms** | **0.76 ms** | **−69%** |
| Pause max | 3.10 ms | 3.79 ms | +22% |

### Findings

**1. Java 25 runs this workload in less than half the heap.** 302 MB committed
against 650 MB, because G1 on 25 sizes eden at 180 MB where 21 chose 390 MB. This
is the "less memory" effect — and it is a genuine JDK-version difference, not the
GC-ergonomics artefact the earlier mislabelled comparison appeared to show.

Cross-check: the earlier GraalVM 25 run independently settled at 298 MB committed,
and OpenJDK 26 at 411–478 MB. Two unrelated JDK 25 builds landing on ~300 MB while
21 sits at 650 MB makes the sizing difference look like a real 25-generation change
rather than run-to-run luck.

**2. ~~Java 21 is about 7% faster.~~ WITHDRAWN — see [Correction](#correction-the-throughput-finding-was-wrong).**
The GUI arms gave 20,781 vs 19,657 gen/s, but that is one hand-driven sample per
arm, and 12 controlled headless runs contradict it: JDK 25 is 7.5% *faster*.

**3. ~~The smaller heap is what costs the throughput.~~ WITHDRAWN.** This explained
a throughput regression that does not exist. The heap and GC observations below
stand on their own; they are not a cost in throughput terms. Half the eden does mean
2.8× as many collections (1473 vs 532) and nearly double the promotion (272 MB vs
104 MB), but total pause is 0.43% of wall clock and JDK 25 is faster regardless.

**4. Pauses became far more predictable on 25.** p99 dropped from 2.49 ms to
0.76 ms — a 69% improvement — even though there are nearly three times as many
pauses. For an interactive GUI this is the more valuable property.

**5. The application allocates identically on both.** 83.4 KB vs 82.1 KB per
generation, and `[Z` is 98.7–98.9% of it on both. That is exactly the two fresh
`boolean[40401]` arrays that `LifeRules.next` allocates every call
(2 × 40,417 B = 80,834 B) plus per-generation `BandResult`/`ActiveRegion` overhead.
**No JDK upgrade will change this number.** The live set is only ~14 MB on both, and
flat, so nothing leaks — the entire 1.6–1.7 GB/s is short-lived garbage by design.

### Caveat on the hot-method profile

Execution-sample breakdowns are *not* directly comparable across these two JDKs.
JFR's virtual-thread sampling changed between 21 and 25, and the sample rates differ
(62/s vs 80/s). The Java 25 profile attributes ~7% to continuation machinery
(`VirtualThread.unmount`, `Continuation.yield`, `Continuation.enterSpecial`,
`ForkJoinPool.awaitWork`) which is essentially absent from the Java 21 profile:

| | Java 21 | Java 25 |
|---|---|---|
| `LifeRules.computeBand` | 89.7% | 77.7% |
| `LifeRules.ruleAt` | 8.2% | 9.6% |
| virtual-thread / continuation frames | ~0.4% | ~7.4% |

That ~7% matches the ~7.4% throughput gap suspiciously well, and long parks per
generation are 12% higher on 25, so scheduling overhead is a plausible explanation.
It is **not established** — the sampling difference could equally explain the gap in
visibility. Confirming it would need `GenerationBenchmark` run headlessly with
platform threads vs virtual threads on both JDKs.

### Reproducing

```
git checkout main   # or java25
JAVA_HOME=<jdk21-or-25> mvn -q -DskipTests clean package
$JAVA_HOME/bin/java -XX:+UseG1GC -XX:FlightRecorderOptions=stackdepth=128 \
  -XX:StartFlightRecording=name=life,settings=profile,\
filename=profiling/<name>.jfr,dumponexit=true,maxsize=500m \
  -jar target/GuiLife.jar
```

Then confirm the JDK from the recording before trusting any number:

```
jfr print --events JVMInformation profiling/<name>.jfr | grep jvmVersion
```

---

## Correction: the throughput finding was wrong

The GUI arms reported Java 21 as ~7% faster. That conclusion is **withdrawn**. It
rested on one hand-driven run per arm, and a controlled headless experiment reverses
it.

### Why the GUI comparison could not resolve it

Two invocations of the *same* JDK on the *same* single-partition code path returned
12,146 and 12,853 gen/s — 5.8% apart. Run-to-run variance was the same size as the
effect being reported, so n=1 per arm was never enough.

### The controlled experiment

`GenerationBenchmark` is headless, seeds a fixed board and takes best-of-5, so the
workload is identical across arms. Because the branches differ only in a comment, the
**same jar** (`release=21`, major 65) runs on both JVMs — identical bytecode, so the
only variable is the JVM. 20,000 generations per measurement, `-XX:+UseG1GC`,
3 repetitions per configuration, 4 partitions:

| Configuration | run 1 | run 2 | run 3 | mean |
|---|---|---|---|---|
| JDK 21, no JFR | 18,121 | 18,264 | 18,407 | **18,264** |
| JDK 25, no JFR | 19,716 | 19,242 | 19,918 | **19,625** |
| JDK 21, `settings=profile` | 17,528 | 17,426 | 17,564 | **17,506** |
| JDK 25, `settings=profile` | 19,564 | 19,538 | 19,532 | **19,545** |

**JDK 25 wins all 12 runs with zero distribution overlap** — JDK 21's best (18,407)
never reaches JDK 25's worst (19,242).

| | JDK 25 advantage |
|---|---|
| without JFR | **+7.5%** (4 partitions), +7.6% (16 partitions) |
| with JFR | **+11.6%** (4 partitions), +11.4% (16 partitions) |

### Where the advantage comes from

Partition sweep, virtual threads, no JFR:

| Partitions | JDK 21 | JDK 25 | Δ |
|---|---|---|---|
| 1 | 12,853 | 12,911 | +0.5% |
| 2 | 18,479 | 18,469 | −0.1% |
| 4 | 18,086 | 19,791 | +9.4% |
| 8 | 18,009 | 19,911 | +10.6% |
| 16 | 18,010 | 20,000 | +11.0% |
| 20 | 18,013 | 19,844 | +10.2% |

Single-partition throughput is **identical**, so the compute kernel runs at the same
speed on both. The gap opens only once work is dispatched across threads: JDK 21
flatlines at ~18,000 from 2 partitions on, while JDK 25 keeps scaling to ~20,000.
That points at virtual-thread scheduling, and it agrees with the sweep already in the
`java25` README.

Running the `release=25` jar on JDK 25 gave 19,778 at 4 partitions against the
`release=21` jar's 19,791 — indistinguishable, confirming the compile target is
irrelevant, as a comment-only diff implies.

### A hypothesis that turned out to be wrong

The first explanation offered for the GUI result was that JFR's execution sampler
walks virtual-thread stacks on 25 but not on 21, taxing 25 on exactly the path where
it wins. **The data says the opposite.** JFR costs:

| | cost of `settings=profile` (pooled, n=7) |
|---|---|
| JDK 21 | **−3.5%** |
| JDK 25 | **−1.7%** |

Profiling penalises JDK 21 about twice as hard. It makes JDK 25 look *better*, so it
cannot explain a result in which 21 came out ahead.

*(An earlier version of this section reported −4.2% and −0.4%, from 3 reps, and said
21 was penalised "ten times harder". The JDK 25 JFR cell was unusually tight in that
first batch; with 7 reps per cell the gap is 2×, not 10×. The direction is unchanged.)*

### What remains unexplained

Why the single GUI run favoured JDK 21 is still open. Absolute numbers are not
comparable between the two harnesses — the benchmark seeds a fixed board while the
GUI runs two Gosper glider guns to saturation, and `computeBand` iterates only the
active region, so work per generation differs between harnesses (though not between
arms within one harness, since seeding is deterministic).

Candidates not yet tested: the 60 fps Swing/Metal repaint loop competing with the
engine, and the `SimulationEngine` actor's command-polling path, neither of which the
benchmark exercises. Resolving it needs several GUI runs per arm rather than one.

### Standing conclusion

- **Throughput: JDK 25 is ~7.5% faster** (~11.6% under profiling), consistently, in
  the controlled comparison. The advantage is in multi-threaded dispatch, not compute.
- **Memory: JDK 25 uses 302 MB committed against 650 MB.** Unaffected by any of the
  above — it comes from GC events, not execution sampling.
- **Allocation: identical.** ~82–83 KB per generation on both, 98.8% of it `[Z`.

---

## Matched-pair confirmation (shipping configuration)

The correction above ran the **same** `release=21` jar on both JVMs to isolate the
JDK. That answers "is the JVM faster", but not "is the shipped build faster", since
each branch is normally compiled by its own JDK. This section repeats the experiment
with **matched pairs** — the artifact you would actually ship:

| Arm | Branch | Built by | Bytecode | Run on |
|---|---|---|---|---|
| Java 21 | `main` | openjdk@21 21.0.12.1 | major 65 | openjdk@21 |
| Java 25 | `java25` | openjdk@25 25.0.4.1 | major 69 | openjdk@25 |

4 repetitions per configuration, interleaved by repetition so drift hits both arms
equally. 20,000 generations per measurement, best-of-5 inside the harness,
`-XX:+UseG1GC`, virtual-thread executor.

### Mean generations/s over 4 repetitions

| Partitions | JDK 21 | JDK 25 | Δ | JDK 21 +JFR | JDK 25 +JFR | Δ |
|---|---|---|---|---|---|---|
| 1 | 12,519 | 12,872 | +2.8% | 12,692 | 12,465 | −1.8% |
| 2 | 18,296 | 18,851 | +3.0% | 18,567 | 18,349 | −1.2% |
| **4** | **18,002** | **19,782** | **+9.9%** | 17,466 | 19,268 | +10.3% |
| 8 | 18,021 | 19,771 | +9.7% | 17,506 | 19,397 | +10.8% |
| 16 | 18,036 | 19,763 | +9.6% | 17,498 | 19,427 | +11.0% |
| 20 | 18,115 | 19,794 | +9.3% | 17,521 | 19,375 | +10.6% |

At 4 partitions the ranges do not overlap in either condition:

| | JDK 21 range | JDK 25 range | Overlap |
|---|---|---|---|
| no JFR | 17,653 – 18,375 | 19,594 – 19,954 | none |
| with JFR | 17,251 – 17,634 | 18,927 – 19,611 | none |

### Pooled with the shared-bytecode matrix

The JDK 21 arm is identical in both experiments (`r21` jar on JDK 21), so those runs
are independent replication. Pooling all runs at 4 partitions, n=7 per cell:

| | JDK 21 | JDK 25 | Δ | Overlap |
|---|---|---|---|---|
| no JFR | 18,114 (17,653–18,407) | 19,715 (19,242–19,954) | **+8.8%** | none |
| with JFR | 17,483 (17,251–17,634) | 19,387 (18,927–19,611) | **+10.9%** | none |

**JDK 25 won all 28 runs across both experiments.**

### The compile target does not matter

| JDK 25 at 4 partitions, no JFR | mean |
|---|---|
| running `release=21` bytecode | 19,625 |
| running `release=25` bytecode | 19,782 |

A 0.8% difference, inside the run-to-run spread of either cell. The shared-bytecode
delta (+7.5%) and the matched-pair delta (+9.9%) differ by more than that, which is
drift between batches rather than a bytecode effect — as a comment-only source diff
implies. Both framings agree on direction and rough magnitude.

### Shape of the advantage, restated

At 1–2 partitions the two JDKs are within ±3% and the sign flips between the JFR and
non-JFR conditions — that is noise, and it means **the Life kernel itself runs at the
same speed on both**. The advantage appears only at 4 partitions and above, where it
is stable at ~9–11%. The difference is in multi-threaded dispatch — virtual-thread
scheduling — not in compute.

### Standing conclusion (unchanged, now on 28 runs)

- **Throughput: JDK 25 is ~9% faster** at the default 4 partitions, ~10% under
  profiling, in the shipping configuration. Consistent across 28 runs with no
  distribution overlap.
- **Memory: JDK 25 uses 302 MB committed against 650 MB.**
- **Allocation: identical**, ~82–83 KB per generation, 98.8% of it `[Z`.

---

## Round 2 — GUI reruns via jenv, and a scheduling discovery

### Discovery that reframes every absolute number: core placement

This machine is an **Apple M2 Pro — 6 performance + 4 efficiency cores**. macOS
assigns QoS by process type, and it dominates this workload. Identical glider-gun
harness, JDK 21, 4 partitions, 1,000,000 generations:

| QoS | gen/s |
|---|---|
| Normal (terminal) | ~17,600 |
| Background (`taskpolicy -b`, E-cores) | **~5,400** |

**A 3.3× swing from core placement alone.** Consequence: absolute throughput is only
comparable *within* one environment. Headless terminal numbers and foreground GUI
numbers must never be compared to each other — an error made earlier in this log.

### jenv, as requested — with two traps recorded

Runtime switching now goes through jenv. Two things worth knowing:

| jenv name | Actually resolves to |
|---|---|
| `openjdk64-21.0.11` | Homebrew OpenJDK **21.0.12.1** |
| `openjdk64-25.0.3` | Homebrew OpenJDK **25.0.4.1** |
| ⚠️ `25.0.4` | **GraalVM 25**, not OpenJDK |

The version *names* are stale — Homebrew updated both JDKs in place and jenv kept the
old labels. The targets are correct. But `jenv local 25.0.4` selects GraalVM, which is
exactly the substitution that produced the two bogus snapshots on 2026-09-07. Both
arms therefore use the explicit `openjdk64-*` names, and the JDK is still verified out
of `jdk.JVMInformation` rather than trusted from the selection.

### Method

```
git checkout main            # or java25
jenv local openjdk64-21.0.11 # or openjdk64-25.0.3   -> writes .java-version
JAVA_HOME="$(jenv prefix)" mvn -q -DskipTests clean package
java -XX:+UseG1GC -XX:FlightRecorderOptions=stackdepth=128 \
     -XX:StartFlightRecording=name=life,settings=profile,\
filename=profiling/run2-jdk<N>.jfr,dumponexit=true,maxsize=500m \
     -jar target/GuiLife.jar        # `java` is the jenv shim
```

Bytecode verified major 65 / 69. Runtime verified from each recording:

| | Java 21 arm | Java 25 arm |
|---|---|---|
| `jvmVersion` | OpenJDK (21.0.12.1), by "brew" | OpenJDK (25.0.4.1) |
| `libjvm` | `openjdk@21/21.0.12.1/...` | `openjdk@25/25.0.4.1/...` |
| Snapshot | `profiling/run2-jdk21.jfr` (10.4 MB) | `profiling/run2-jdk25.jfr` (14.3 MB) |

### GUI throughput, three partition counts per arm

| Partitions | Java 21 | Java 25 | Δ |
|---|---|---|---|
| 4 | 20,574.9 | 19,856.8 | 21 faster by 3.6% |
| 8 | 20,590.8 | 20,079.7 | 21 faster by 2.5% |
| 16 | 20,631.8 | 20,097.6 | 21 faster by 2.7% |

Adding round 1 (Java 21 20,781 / Java 25 19,657 at 4 partitions), the GUI ranges are
**21: 20,575–20,781** and **25: 19,657–20,098** — no overlap across two independent
rounds. **The GUI result replicates.** The earlier dismissal of it as a single noisy
sample was wrong.

### The two environments disagree, and both replicate

| Environment | Java 21 | Java 25 | Winner |
|---|---|---|---|
| Headless terminal (28 benchmark runs + sustained harness) | 17,600–18,100 | 19,260–19,715 | **25, by ~9%** |
| Foreground GUI (2 rounds × 3 partition counts) | 20,575–20,781 | 19,657–20,098 | **21, by ~3%** |

The GUI environment lifts **Java 21 by ~17%** (17,600 → 20,575) but **Java 25 by only
~4%** (19,260 → 19,857). Both converge toward ~20,000–20,600 in the GUI, with 21
slightly ahead.

So the ranking is environment-dependent and neither result is an artefact. Mechanism
is not established. Candidates: QoS/core placement interacting differently with each
JDK's virtual-thread scheduler, and contention with the EDT and Metal render threads,
which the headless harness does not exercise. JDK 25 does use measurably more CPU per
generation in the GUI (150 µs vs 144 µs at equal core occupancy), so it is doing more
work per generation there, not merely being scheduled worse.

### JFR detail, 4-partition windows

| | Java 21 | Java 25 |
|---|---|---|
| Throughput | 20,575 gen/s | 19,857 gen/s |
| CPU busy | 2.97 of 10 cores | 2.97 of 10 cores |
| **CPU per generation** | **144 µs** | **150 µs** (+4.2%) |
| Bytes per generation | 83,035 B | 82,074 B |
| **Committed heap (p50)** | **541 MB** | **302 MB** (−44%) |
| Eden capacity (p50) | 318.6 MB | 180.2 MB |
| Live set after GC | 13.2 MB | 13.2 MB |
| GC collections | 442 (5.53/s) | 504 (9.44/s) |
| Generations per GC | 3,723 | 2,103 |
| Promoted | 82.6 MB (1.03 MB/s) | 96.8 MB (1.81 MB/s) |
| Total pause | 0.330% of wall | 0.467% of wall |
| Pause p50 | 0.55 ms | 0.44 ms |
| Pause p99 | 1.84 ms | **2.65 ms** |

**Memory replicates strongly.** Java 25 committed 302 MB in both rounds; Java 21 took
541 MB here and 650 MB in round 1. Java 25 consistently runs this workload in roughly
half to two-thirds the heap, with an identical 13.2 MB live set.

**The pause-percentile finding does NOT replicate.** Round 1 reported p99 of 0.76 ms
on 25 against 2.49 ms on 21, and called it a 69% improvement. Round 2 reverses it:
2.65 ms on 25 against 1.84 ms on 21. With n=2 the p99 tail is simply noisy, and that
claim is withdrawn. Median pause is consistently lower on 25 (0.43–0.44 vs
0.55–0.58 ms) — that part holds.

### Standing conclusions after two rounds

- **Memory: Java 25 wins clearly and reproducibly** — ~302 MB committed vs 541–650 MB,
  same 13.2 MB live set, at the cost of ~2× the collections and ~1.8× promotion.
- **Throughput: it depends on the environment.** Java 25 is ~9% faster headless;
  Java 21 is ~3% faster in the actual GUI app. Both replicate.
- **Allocation: identical**, ~82–83 KB per generation, 98.9% of it `[Z`, on every run.
- **Pause tails: no reliable difference** at n=2.

> **The GUI throughput conclusion above does not survive a noise floor.** See
> [2026-09-30](#2026-09-30--an-aa-noise-floor-and-a-234-executor-regression): an A/A test
> on this machine and workload produced a **+1.71%** difference between two arms that are
> identical by construction, with a 4.70% full spread. The "Java 21 is ~3% faster in the
> GUI" claim sits inside that floor and is withdrawn. The memory and allocation
> conclusions are unaffected — those effects are ~50% and ~0.1%, far outside it.

## 2026-09-30 — an A/A noise floor, and a 23.4% executor regression

Rig rebuilt, a determinism gate added, and a measurement that turns out to matter more
than the JDK question that prompted it. Workload throughout: `drive.GuiDrive 2000000 3`,
`ENV=gui` (window, EDT, incremental repaint), 5 reps per arm interleaved by repetition,
JDK identity verified per arm. Raw data was written to `profiling/run-2M-20260930-*/` and
is **gitignored**, so the numbers below are the record.

### The rig had been lost again, and the gate was never real

`tools/bench/` was empty — second loss, same cause: it was never committed. Recovered
from `df5b6ae` on `java27`; one import had gone stale (`SimulationSeeder` moved to
`com.conway.engine.support`). `shared-r21.jar` was rebuilt from current `main`, because the
committed jar was the pre-`ForkJoinPool` engine and would have measured code that no longer
exists. Driver sources are now in tree (`b821e72`) with `*.conf.example` templates, so the
invocation survives even though the live configs stay local.

`drive.GateCheck` is new, and closes a real hole: **every prior round's "determinism gate"
compared generation counts**, which varied 2,000,143–2,000,311 because a free-running engine
stops on the first snapshot at or past target. Matching counts never proved matching work.
GateCheck calls `LifeRules.next()` synchronously for an exact step count and hashes the
board. At 50,000 steps both JDKs agree exactly — seed population 72 / aliveHash
−1511641660, final population 346 / aliveHash −183858688 / everAliveHash −1980827136.

### The A/A floor: this design fabricates 1.71%

Two arms, same JDK (21.0.12.1), sha256-identical jar, same branch. True difference is zero.

| Arm | median gen/s | range | spread |
|---|---|---|---|
| `21a` | 16,941.9 | 16,601.9–17,382.3 | 4.70% |
| `21b` | 17,231.9 | 16,959.0–17,375.4 | 2.46% |
| **false delta** | **+1.71%** | | |

All ten runs: 16,602 · 16,723 · 16,942 · 16,959 · 17,144 · 17,232 · 17,250 · 17,329 ·
17,375 · 17,382. Median 17,187.8, full spread 4.70%, stdev 1.61% of mean.

**Consequence: no throughput claim below ~3% in this environment means anything at n=5**,
and that retroactively covers every 21-vs-25 throughput figure this project has produced —
including this document's ~3% GUI result. At stdev 1.6%, n=5 resolves roughly 3%+, n=10
about 2%, and resolving 1% would need ~40 reps per arm. Not worth buying.

The focus markers failed to predict again: the only two runs ending focused were 16,959 and
16,942 — among the *slowest* — while the fastest two ended unfocused. Third time this gate
has shown no correlation. Treat it as provenance, never as grounds for discarding a run.

### `ForkJoinPool` costs 23.4% against virtual threads

One JDK (21.0.12.1), one commit (`13fc868`), one line different, artifacts staged per arm,
interleaved, no JVM options. The two worktrees were diffed to confirm the only change was
the executor construction plus its import, and the built jars were checked to actually
differ in bytecode — a config that silently builds the same thing twice is the obvious way
for this test to lie.

| Arm | Band dispatch | median gen/s | range | all five |
|---|---|---|---|---|
| `fj` | `new ForkJoinPool(min(maxPartitions, cores))` | **17,058.7** | 15,935.3–17,652.1 | 15,935 · 16,872 · 17,059 · 17,500 · 17,652 |
| `vt` | `Executors.newVirtualThreadPerTaskExecutor()` | **22,282.8** | 20,070.5–22,605.9 | 20,070 · 21,891 · 22,283 · 22,511 · 22,606 |

**`vt` is +30.62% faster; `ForkJoinPool` is −23.44%.** Every `vt` run beat every `fj` run —
complete separation, roughly 18× the A/A floor. This is the largest effect ever measured on
this workload, and the only one here that is unambiguously actionable: reverting one line in
`SimulationEngine` is worth ~23% throughput in the shipping app.

**It also inverts README's executor table.** `GenerationBenchmark` measures forkjoin *ahead*
of virtual at 201/4 partitions (19,473 vs 17,422). In the GUI the ordering reverses and the
margin is larger. A prediction was recorded before the run that if the README held, `vt`
would come back slower; it came back 30% faster. So the harness and the shipping
application disagree about which executor is faster, in opposite directions — and the
executor currently in `main` was chosen on the harness's evidence.

**Mechanism: hypothesis only, not measured.** The simulation thread is itself a virtual
thread that submits bands and blocks on `Future.get()`. With a virtual-thread-per-task
executor the bands are virtual threads too and the blocking composes; with a dedicated
`ForkJoinPool` a virtual thread submits externally and then blocks on an FJP `Future`, a
path where carrier pinning and poor blocking compensation are plausible. C1 carries no JFR,
so there is no evidence either way yet. The test that would settle it is C3
(`settings=profile`) on both arms, reading `virtual_thread_pinned_count` — 0 in the
virtual-thread rounds of 2026-09-18.

**Two caveats.** Within-arm spreads this round (10.77% and 12.63%) were wider than the A/A
floor measured an hour earlier, so the machine was noisier than the floor suggests; the
separation is total regardless, but the medians carry more uncertainty than ±1.7%. And this
isolates the executor on *current* `main` only — it does not re-test the rest of the
37-file refactor between `f17e236` and `13fc868`, which remains unmeasured.

### An unexplained cross-session gap, and what is NOT claimed here

`vt`'s 22,282.8 lands within +1.8% of the 21,884 measured on 2026-09-18's virtual-thread
engine — inside the A/A false delta. Two sessions agreeing on the virtual-thread level,
with `fj` 23% below both, is suggestive. But **that 2026-09-18 figure cannot be verified**:
its artifacts were deleted, and cross-session comparison is the one thing this method
forbids most firmly. It is recorded as corroboration, not evidence.

Also unrecorded by necessity: the 2026-09-18 pinned-heap round (C2/C4 at `-Xms1g -Xmx1g
-XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=40`), which found the +60% total-pause
gap between 21 and 25 vanished at equal eden — 267 collections against 266. Its data and its
log entry were both lost when the branch moved, and it is not re-derivable. The method
lesson is in `.gitignore` now: write the numbers into the entry while the data is still on
disk.

### Standing next steps

1. **Revert the executor**, or justify keeping it against a 23.4% measured cost.
2. **C3 on `fj` vs `vt`** to settle the pinning hypothesis (~20 min, 10 runs).
3. **The 21-vs-25 matrix is still unrun** at 2M on the current engine. Worth running for
   the memory and GC rows, which clear the floor by 30×; its throughput row can only ever
   be a bound, not a measurement.
