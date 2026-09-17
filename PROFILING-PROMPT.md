# Prompt — profiling the Conway Life engine across JDKs

Paste everything below the line into a fresh session, after filling in **Input
parameters**. Everything else is fixed; the JDK set is the only thing you change
between studies.

Supersedes `JVM-COMPARISON-PROMPT.md`, which encoded the hand-driven GUI method and
a 21-vs-25 scope. This one is headless, parameterized over any SDK set, and runs a
condition matrix rather than a single arm per JDK.

---

## Task

Profile this Conway Game of Life engine on each JDK listed below and report where they
differ in throughput, memory, GC and allocation. Run it in **both environments**: the
headless harness and the real Swing GUI. Everything is automated — build, launch, drive,
record, analyse, log. I am not hand-clicking Start/Stop; the GUI is driven by
`GuiDrive`, which issues the same engine commands the buttons send.

The GUI is the shipping application, so it is not optional: a JDK result that only holds
headless is a result about the harness. But the two environments are measured separately
and their numbers are **never** put in the same table — see invariant 4.

This is a **pure JDK comparison**. One jar, one driver bytecode, one workload; the only
thing that varies between arms is the `java` binary and the condition round. If you find
yourself changing the source, the harness, or the compile target to make an arm run, stop
and report it instead — that is a different experiment.

## Input parameters

**JDK set.** Fill in `tools/bench/jdks.conf`, one `<label> <JAVA_HOME>` per line. The
runner iterates this file, so the matrix, the interleave order and the analysis all widen
automatically when you add a line.

```
# label  JAVA_HOME
21  /opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home
25  /opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
# 26  /opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home
# 27  /Library/Java/JavaVirtualMachines/zulu-27.jdk/Contents/Home
```

Verify every entry before running anything:

```bash
while read -r label home; do case "$label" in \#*|"") continue;; esac; printf '%-4s ' "$label"; "$home/bin/java" -version 2>&1 | head -1; done < tools/bench/jdks.conf
```

**Vendor is part of the parameter.** 21 / 25 / 26 above are Homebrew *formula* OpenJDK
builds; 27 is the Azul **Zulu** cask, because no Homebrew formula 27 exists. A mixed-vendor
set is allowed but every cross-vendor delta carries an unquantified vendor component —
state it in the report, and bracket it with a same-vendor control arm before defending a
delta under ~10%. **Never put GraalVM in this set**: it compares JIT compilers, not JDKs.

**Environment set.** Both are run by default. Each is a separate result; neither
substitutes for the other.

| Environment | Driver | What it is |
|---|---|---|
| `headless` | `drive.HeadlessDrive` | Terminal, normal QoS, no window. Reproducible and fully unattended. |
| `gui` | `drive.GuiDrive` | The real application: Swing window, EDT, incremental repaint at a 60 fps target. What users actually run. |

They differ by more than a window. The GUI's live set after GC is ~13 MiB against
headless ~5 MiB, work per generation includes paint, and macOS gives a foreground window
different CPU treatment. Expect different absolute numbers and do not be surprised by
them — the question is whether the *JDK ranking* is the same in both.

**Other parameters**, changed only with a reason recorded in the log:

| Parameter | Default | Note |
|---|---|---|
| Grid | `201` | Active region saturates at 98–99% within ~50k generations, so board evolution is not a confound |
| Partitions | `4` | Where the effect lives; gains stop after 2 |
| Generations | `1000000` | ~52 s headless, ~50 s GUI. The historical GUI rounds used `2000000`; match that only if you intend to compare against them directly |
| Iterations | `5` per JDK per round per environment | See **Iterations and ordering** |
| Frozen source | `main` @ `f17e236` | Executor engine; the design 21/25 both shipped |

## Non-negotiable invariants

1. **One question per study.** This one varies the JDK. If the engine design also
   changes between arms, you are measuring two things and can attribute neither.
2. **Every arm runs the same program.** One `shared-r21.jar` (built from the frozen
   source by JDK 21, major 65, loads on every later JVM) and one driver bytecode. Never
   rebuild per arm.
3. **Never trust a JDK label — verify from the recording, after the fact.** See
   **Identity gates**. A Java 21 jar runs fine on a 25 JVM, so there is no
   `UnsupportedClassVersionError` to expose a mismatch.
4. **One environment per claim — but measure both.** Core placement is worth 3.3×
   (foreground GUI 20,575 gen/s, terminal 17,600, background QoS 5,400), so mixing
   environments swamps a ~9% JDK effect. Every table is headless-only or GUI-only, with
   the environment in its heading. Never put a headless number beside a GUI number, never
   compute a delta across them, and never let a GUI arm and a headless arm be the two
   sides of one comparison. The JDK ranking *within* each environment is the finding; if
   the two environments disagree, that disagreement is itself a result worth reporting,
   not an error to reconcile.
5. **Run-to-run variance is ~6%.** Two runs of the same JDK on the same path came out
   12,146 and 12,853. One run per arm cannot resolve a 9% effect.
6. **Pure JFR, `settings=profile`.** No IntelliJ profiler: its async-profiler JVMTI hooks
   fire on every virtual-thread mount, and mount/unmount is what this workload is bound
   by. Default JFR settings record zero `jdk.GarbageCollection` events.
7. **Throughput ground truth is the app's own log line**, never JFR inference:
   `Run finished: N generations in T s using P partition(s), R generations/s`.
8. **No hand-transcribed numbers.** Every table in the report is generated from
   `results.jsonl` by `tools/bench/analyse.py`. Transcription is where the last log's
   errors came from.

## Project-level commands

### Build the shared jar — once, on JDK 21

```bash
git worktree add /tmp/frozen main && JAVA_HOME="$(sed -n 's/^21[[:space:]]*//p' tools/bench/jdks.conf)" mvn -q -DskipTests clean package -f /tmp/frozen/pom.xml && cp /tmp/frozen/target/GuiLife.jar tools/bench/shared-r21.jar
```

Confirm the bytecode target and record the hash — both go in the report:

```bash
javap -v -cp tools/bench/shared-r21.jar com.conway.Main | grep 'major version' && shasum -a 256 tools/bench/shared-r21.jar
```

Expect `major version: 65`. Anything else means the wrong JDK built it.

**Do not pass `--enable-preview` to this jar.** The frozen source needs no preview
features. The flag is only required by the `java27` branch's own jar, which is a
different experiment — and it is not a neutral flag (it changes verification and
disables the default CDS archive), so it must never differ between arms of this study.

### Compile the drivers — once, against the shared jar

```bash
javac -cp tools/bench/shared-r21.jar -d tools/bench/out tools/bench/drive/HeadlessDrive.java tools/bench/drive/GuiDrive.java tools/bench/drive/BoardChecksum.java
```

One driver bytecode at major 65 runs on every JVM in the set. Both drivers wire the app
exactly as `com.conway.Main` does and issue the same engine commands the Start/Stop
buttons send (`engine.start()`, then `engine.pause()` once the published snapshot reaches
the target); `GuiDrive` additionally launches the real window and raises it. The app's own
`Run finished:` line is the throughput ground truth in both.

**Keep these in the repo.** They live in `tools/bench/`, outside `src/`, so
`git diff main java25 -- src/` is unaffected — the reason they were once kept in `/tmp`.
That cost a full round when `/tmp/gui-drive` did not survive a reboot and `GuiDrive` had
to be reconstructed from a prose description.

### GUI preconditions — check before every GUI round

The GUI is sensitive to things that do not exist headless. Set these up once per session
and confirm they held afterwards:

```bash
caffeinate -d -i -w $$ &
```

- **Display must stay awake.** `caffeinate -d` for the duration; a display sleep or screen
  saver mid-run invalidates it.
- **Do Not Disturb on.** A notification banner steals focus and composites over the window.
- **No other foreground apps**, no Mission Control, no Spaces switch, nothing typed on the
  machine while a run is in flight. Start the round and leave it alone.
- **The window must be raised, visible and focused** at start *and* at pause. `GuiDrive`
  records this; see the window-state gate under **JFR analysis criteria**.
- Run GUI rounds on the internal display at a fixed resolution and scale factor. The app
  is HiDPI-aware and repaint cost tracks pixel count, so switching monitors mid-study
  changes the workload.

### Validate before measuring anything

```bash
tools/bench/verify.sh
```

Runs `BoardChecksum` (exactly 100,000 generations via `LifeRules.next`) on every JDK in
the config and prints one hash each. **All hashes must be identical.** If they are not,
the JDKs are not computing the same board and nothing downstream is comparable — stop and
report. This is the only guard that an arm is not silently doing less work; matching
generation *counts* does not prove matching *results*.

### Run the matrix

Headless first — it is unattended, so it can run while you set up for the GUI:

```bash
tools/bench/run-matrix.sh --config tools/bench/jdks.conf --env headless --reps 5 --generations 1000000 --partitions 4
```

Then the GUI, after the preconditions above are in place and with the machine left alone:

```bash
tools/bench/run-matrix.sh --config tools/bench/jdks.conf --env gui --reps 5 --generations 1000000 --partitions 4
```

Each appends one JSON line per run to `profiling/results.jsonl` — carrying `env` as a
field, so the analysis can never silently pool them — and writes recordings to
`profiling/<env>-<round>-<jdk>-rep<n>.jfr` with a matching `.console.log`.

### Analyse

```bash
tools/bench/analyse.py profiling/results.jsonl --out docs/jdk-comparison-tables.md
```

## Condition rounds

A round is one condition applied across the whole JDK set in one environment, and it is
the unit that gets its own table. C0–C4 are mandatory and run in **both** environments;
C5–C6 are opt-in and headless-only for the reasons given below.

| Round | Condition | Environments | Purpose | Reps |
|---|---|---|---|---|
| **C0** | Identity + checksum, no measurement | both | Prove every arm is the JDK it claims and computes the same board | 1 |
| **C1** | Default heap, no JFR | headless + gui | The headline throughput number, unperturbed | 5 |
| **C2** | Pinned heap, no JFR | headless + gui | Throughput with GC ergonomics removed | 5 |
| **C3** | Default heap + JFR `settings=profile` | headless + gui | Memory, GC, allocation, pauses — as each JDK sizes itself | 5 |
| **C4** | Pinned heap + JFR | headless + gui | Control for C3; isolates what is policy from what is runtime | 5 |
| **C5** | Default heap, partition sweep 1/2/4/8/16/20 | headless only | Locate the effect: compute kernel vs dispatch | 5 |
| **C6** | `taskpolicy -b` (E-cores), default heap | headless only | Does the JDK ranking invert when CPU-starved? | 5 |

For a 2-JDK set that is 8 measured rounds (C1–C4 × 2 environments) at 10 runs each, 80
runs, roughly 80 minutes; each additional JDK adds ~40 minutes.

**Why C5 and C6 are headless-only.** The partition sweep is six configurations per arm,
which in the GUI would mean an hour of the machine being untouchable for a question the
headless arm answers identically — partition scaling is an engine property, and the GUI's
own default is fixed at 4. C6 is incoherent in the GUI by construction: `taskpolicy -b`
requests background QoS while a foreground window requests the opposite, so the two
fight and the result means nothing.

**C1 vs C2 is the point of the matrix.** Every previous study here ran default heap only,
which bundles the JDK's code generation and runtime with its G1 young-sizing policy — and
those JDKs size the young generation very differently (committed heap p50 has run
620 / 300 / 262 MiB across 21 / 25 / 27). So a default-heap throughput delta cannot be
attributed. Reading the pair:

- Delta **survives** into C2 → it is runtime/codegen (e.g. virtual-thread dispatch).
- Delta **vanishes** in C2 → the default-heap result was GC ergonomics all along.

**Headless vs GUI is the second thing the matrix is for.** There is a standing,
unexplained observation that the JDK ranking *flips* between them: headless the newer JDK
won 28 of 28 runs by ~9%, while in the foreground GUI the older one led by ~3%. Both
environments running the same rounds, in one session, at n=5, is what turns that from an
anecdote into either a finding or a retraction. Report it as two tables and a paragraph,
never as one table with a delta.

**C5 is how you tell compute from dispatch.** If single-partition throughput is equal
across JDKs and the delta only appears at ≥4 partitions, the Life kernel compiles equally
well everywhere and the difference is in virtual-thread dispatch.

**C6 is opt-in and currently unresolved.** There is a standing, unconfirmed observation
that the more CPU-starved the environment, the more the newer JDK wins. Run it only with
full reps; the one data point behind it is a partial run.

## Iterations and ordering

- **5 iterations per JDK per round.** Not 1, not 3. G1's young-gen sizing has been
  observed to be **bimodal per JVM start** in this workload — the same jar and command
  landed twice on 108 MiB committed / 720 collections and once on 180 MiB / ~1,200. At
  n=1 that produces a confident, wrong story; at n=3 it is barely detectable.
- **Round-robin interleaved, never blocked.** For JDKs `A B` and round `R`, the order is
  `R-A-1, R-B-1, R-A-2, R-B-2, …` — so thermal drift, background load and session effects
  hit every arm equally. Blocked order (`A A A A A B B B B B`) silently charges one arm
  for the machine warming up.
- **Discard and re-run, don't average away.** A run that fails an identity gate or shows
  external CPU contention is not a data point. Discard it, note it in the log, and run a
  replacement in the same interleave position.
- **GUI rounds interleave the same way**, and the window state gate is applied **before**
  the analysis, not after. A previous round noticed only at write-up time that one arm had
  lost focus mid-flight and had to exclude it from a mean it had already computed — and
  separately compared a focused arm against a historical unfocused one without noticing.
  Reject on the marker, then analyse what survives.
- **Do not interleave environments.** Finish the headless round, then the GUI round. They
  are separate results, and alternating them would put window raise/lower churn inside
  every headless measurement.
- **Report every iteration.** `results.jsonl` keeps all of them. Tables report **median
  and IQR over all reps** — never best-of-N. Best-of-N is a biased maximum with no
  variance estimate; it is why the ~6% variance figure had to be discovered empirically
  instead of read off the data.

Approximate cost at 1M generations: ~56 s per run headless, ~53 s GUI plus ~3 s of window
startup. C1–C4 for a 2-JDK set is 40 runs per environment, so 80 runs ≈ 80 min across
both; each additional JDK adds ~40 min. C5 uses 200,000 generations (~14 s per run) to
keep the 6-partition sweep affordable. Budget the GUI half as time the machine cannot be
used for anything else.

## JVM options

**Every run, every round:**

```
-XX:+UseG1GC
```

Stated explicitly rather than relied on as a default — the default collector is exactly
the kind of thing that changes between releases and would silently become the variable.

**Round-specific:**

| Round | Added options |
|---|---|
| C1, C5, C6 | *(none — default ergonomics)* |
| C2, C4 | `-Xms1g -Xmx1g -Xmn512m -XX:+AlwaysPreTouch` |
| C3, C4 | `-XX:FlightRecorderOptions=stackdepth=128 -XX:StartFlightRecording=name=life,settings=profile,filename=profiling/<round>-<jdk>-rep<n>.jfr,dumponexit=true,maxsize=500m` |

Notes that matter:

- **`-Xmn512m` disables G1's adaptive young sizing.** That is the intent — it removes the
  variable — but say so in the report rather than letting a reader assume G1 behaved
  normally. 512 MiB is generous against a live set of ~5 MiB headless, so GC largely
  stops being a factor.
- **`-XX:+AlwaysPreTouch`** commits and faults in the heap at startup, so page-fault cost
  does not leak into the measured window. Only on the pinned rounds, where the heap size
  is fixed.
- **Default heap is 8 GB** here (¼ of 32 GB). It is equal across arms, and how each JVM
  sizes eden *inside* that budget is a C3 finding, not a C3 confound.
- **CDS is left at each JDK's default.** The archives differ between releases; that is
  part of what the JDK ships. Note it as a known difference rather than forcing
  `-Xshare:off`.
- **Profiling costs throughput and not equally.** Measured at −3.5% on 21 against −1.7%
  on 25. Never compare a JFR run to a non-JFR run across arms — that is what C1/C3 and
  C2/C4 being separate rounds is for.

- **The GUI arm adds no JVM options of its own.** Same flags, same rounds; only the
  driver differs. In particular do not add `-Djava.awt.headless`, do not tune
  `-Dsun.java2d.*`, and do not set `-XX:MaxGCPauseMillis` — the README suggests
  `-Xms256m -Xmx1g -XX:MaxGCPauseMillis=20` for a comfortable interactive session, and
  that is exactly the kind of tuning that would replace the JDK's own ergonomics with
  yours and destroy the C1/C3 result.

**Command shape** (the runner builds this; here for reference):

```bash
"$JAVA_HOME/bin/java" -XX:+UseG1GC $ROUND_OPTS -cp tools/bench/out:tools/bench/shared-r21.jar drive.HeadlessDrive 201 4 1000000
```

```bash
"$JAVA_HOME/bin/java" -XX:+UseG1GC $ROUND_OPTS -cp tools/bench/out:tools/bench/shared-r21.jar drive.GuiDrive 1000000 3
```

`GuiDrive`'s second argument is a start delay in seconds, giving the window time to
appear and settle before the engine starts.

## JFR analysis criteria

### Identity gates — a run that fails any of these is discarded, not analysed

| Gate | Check |
|---|---|
| JVM version | `jfr print --events JVMInformation <f>.jfr \| grep jvmVersion` matches the intended JDK |
| Native library | `jfr print --events NativeLibrary <f>.jfr \| grep -o '[^"]*libjvm.dylib'` resolves under the intended `JAVA_HOME` |
| Arguments | `javaArguments` matches the intended driver args exactly |
| Generations | within 0.1% of target across all arms of the round |
| Exclusivity | `jdk.CPULoad` — `machineTotal` minus `jvmUser+jvmSystem` shows no competing load |
| Pinning | `jdk.VirtualThreadPinned` count is 0; a non-zero count is a finding, not noise |
| Configuration | The console `Configuration:` line is byte-identical across every arm of the round |

Traps that have already produced mislabelled runs: `.idea/misc.xml` points the default
JRE at GraalVM; `.idea/workspace.xml` sets `ALTERNATIVE_JRE_PATH` to a different JDK than
the run config's name; `jenv local 25.0.4` selects GraalVM, not OpenJDK.

### Additional gates for GUI rounds

| Gate | Check |
|---|---|
| **Window state** | `GUI-DRIVE WINDOW` marker reads `visible=true active=true focused=true` at **both** `at-start` and `at-pause`. Any other value discards the run. |
| Environment sanity | Live set after GC is ~12–14 MiB. A GUI run reading ~5 MiB did not really render — it is a headless run wearing a window, and it is not comparable to the other GUI arms. |
| EDT present | `jdk.ExecutionSample` contains `AWT-EventQueue-0` frames. Their absence means repaint never ran. |
| Display continuity | No display sleep, screen saver, Spaces switch or notification during the window between `START` and `PAUSE`. |

The window-state gate is not a formality. macOS foreground treatment is worth up to 3.3×,
and it is **graded, not binary** — a visible-but-unfocused window landed roughly 60% of
the way from terminal throughput to hand-clicked-foreground throughput. A run that drops
focus for part of its life is not a slightly worse data point; it is a different
environment, and it must be rejected on the marker rather than on its number looking odd.

### Extraction — reduce each event family on its own values

**Never window by wall clock.** `jdk.G1HeapSummary` and `jdk.GCHeapSummary` timestamps sit
on different clock bases in these files (~7.7 h offset); a naive time window comes back
empty. Reduce each family independently.

| Metric | Source | Reduction |
|---|---|---|
| Throughput | app console `Run finished:` | gen/s — ground truth |
| Wall clock | same line | seconds |
| CPU busy | `jdk.CPULoad` | mean(`jvmUser`+`jvmSystem`) × cores |
| CPU per generation | derived | CPU busy × wall ÷ generations |
| Committed heap p50 | `jdk.GCHeapSummary` | median `heapSpace.committedSize` |
| Eden capacity p50 | `jdk.G1HeapSummary` | median `edenTotalSize` |
| Live set after GC | `jdk.GCHeapSummary` where `when == "After GC"` | median `heapUsed` |
| Bytes per generation **(A)** | `jdk.G1HeapSummary` where `when == "Before GC"` | Σ `edenUsedSize` ÷ generations |
| Bytes per generation **(B)** | `jdk.ThreadAllocationStatistics` | Σ per-thread high-water ÷ generations |
| GC collections | `jdk.GarbageCollection` | count, split young / old, and per second |
| Promotion | `jdk.PromoteObjectInNewPLAB` + `jdk.PromoteObjectOutsidePLAB` | sum — **sampled**, label it as such |
| Pause total / p50 / p99 / max | `jdk.GarbageCollection.longestPause` | sum, median, p99, max |
| Safepoints | `jdk.SafepointBegin` | count, max duration |
| Allocation profile | `jdk.ObjectAllocationSample` | top classes by weight |
| Hot methods | `jdk.ExecutionSample` | top frames — **indicative only**, see below |
| EDT share *(gui)* | `jdk.ExecutionSample` filtered to `AWT-EventQueue-0` | share of samples, and top frames under it |
| Repaint cost *(gui)* | `jdk.ExecutionSample` | share in `BoardView.renderInto` / Java2D / Metal blit frames |
| EDT blocking *(gui)* | `jdk.ThreadPark`, `jdk.JavaMonitorEnter` on the EDT | count and total duration — the engine and the EDT should not be contending |

**Report both bytes-per-generation derivations.** A and B have disagreed before (82,878 B
vs 83,289 B on the same recording). They measure slightly different things; if they
disagree by more than ~1%, report both and say which is which rather than picking one.

**Execution samples are not comparable across JDKs.** JFR's virtual-thread sampling rate
has changed between releases (62/s vs 80/s on 21 vs 25), so a shift in a method's sample
share may be a sampling artefact rather than a real change in where time goes. Use them to
confirm the hot method is unchanged, never to attribute a percentage of runtime.

Use the `jdk.jfr.consumer` API via single-file source launch (`java Analyze.java …`).
`jfr print` output is too large to parse for anything but the identity gates.

### Acceptance criteria — when a delta may be reported as a finding

1. **n ≥ 5 per arm**, all reps passing the identity gates.
2. **Throughput:** report a delta only if the per-rep distributions do not overlap. A
   delta under ~6% with overlapping distributions is noise; say so explicitly rather than
   quoting it with a qualifier.
3. **Heap, eden, GC count:** report **every per-run value**, not just a central estimate.
   These are bimodal per JVM start. A mean across modes is meaningless, and a single-run
   comparison is invalid regardless of how large the gap looks.
4. **Significant figures follow n.** With n=5, two are honest. Quoting 4 significant
   figures off n=2 has already put wrong numbers in a table here.
5. **Cross-vendor deltas** carry a stated, unquantified vendor component until a
   same-vendor control arm exists.
6. **Direction must be consistent across reps.** A mean that flips sign between reps is
   not a result.
7. **Environment never appears inside a comparison.** Each table states its environment in
   the heading and contains one environment's runs. If the JDK ranking differs between
   headless and GUI, report both rankings and say the mechanism is open — do not average
   them, pick the one that agrees with a prior result, or describe one as the "real" number.
8. **State what is bundled.** If an arm differs in more than one way, enumerate every way
   before reporting — including the harness, the thread factory, and any per-generation
   object the other arm did not allocate. A previous round attributed a 1,137 B/generation
   difference to one cause when at least four were in play.

## Output

- **`profiling/results.jsonl`** — one line per run, every iteration kept, including
  discarded ones with a `discarded_reason`. Every line carries `env`, `round`, `jdk` and
  `rep`, and the analysis groups by `(env, round)` so headless and GUI runs cannot be
  pooled by accident. GUI lines also carry the window state at start and at pause.
- **`PROFILING-LOG.md`** — the narrative record. Append an action-log entry per round:
  commands, verified JDK per recording, what was found, what was discarded and why. When
  a claim is overturned, **mark it withdrawn in place rather than deleting it** — the
  corrections are the most useful part of the log.
- **`docs/jdk-comparison-tables.md`** — generated, never edited by hand. One section per
  environment, each table headed with its environment and round, and a closing section
  comparing the two *rankings* (never the two sets of numbers).
- Recordings in `profiling/` (gitignored). Commit the derived per-run JSON and each
  recording's SHA-256 so a result stays traceable to evidence that no longer exists
  locally; the measurement rig has been lost to a reboot once already.

## Do not re-assert these — they were withdrawn

- ~~"Java 25 cuts pause p99 by 69%"~~ — did not replicate; a later round reversed it and
  a third was even. The tail is noise at these sample counts. Median pause does hold.
- ~~"JFR profiling penalises Java 21 ten times harder"~~ — from 3 reps. At 7 reps it is
  −3.5% vs −1.7%, about 2×.
- ~~"Java 21 is 7% faster"~~ — one GUI run per arm, variance the size of the effect.
- ~~"Compile target is irrelevant"~~ — measured only r21-vs-r25 on JDK 25 (+0.8%). It has
  not been retested on later JDKs, and one unexplained 2–7% gap is outstanding. This
  study sidesteps it by using one shared jar; do not quote the claim as settled.
