# Performance tooling

How to re-run every measurement this project relies on. Commands only — numbers belong in
whatever you are writing up, not here, because they go stale the moment anything changes.

Nothing below needs a display except `run`.

## Benchmarks

Two harnesses, both plain `main` functions under `com.conway.bench`, both wired to Gradle
tasks. Neither is JMH: they warm up, take the best of several repetitions and feed results
into a sink, which is enough to tell a real cost from a non-existent one. Reach for JMH if
a number ever has to support a finer decision than that.

### Simulation throughput

Generations per second across partition counts, which is what the parallel band engine
exists to be measured on.

```bash
./gradlew bench
```

Optional arguments: grid size, generations per measurement, and executor mode
(`platform`, the default, or `virtual`).

```bash
./gradlew bench --args="401 300 virtual"
```

### Render path

The two per-frame costs on the UI side: whether the render loop's snapshot comparison
reaches the cell arrays, and what re-rendering a board that is already on screen costs.

```bash
./gradlew renderBench
```

Optional arguments: the board sizes to measure, in cells.

```bash
./gradlew renderBench --args="201 401 801"
```

Rows reading well under a nanosecond are at the harness's resolution limit; read them as
"free", not as a figure.

### Comparing a change against a baseline

Both benchmarks vary enough run to run that a single pair of numbers proves nothing. Build
the baseline in a worktree so the working tree is untouched, and run each side several
times:

```bash
git worktree add -f /tmp/life-baseline HEAD
for i in 1 2 3; do (cd /tmp/life-baseline && ./gradlew bench -q); done
for i in 1 2 3; do ./gradlew bench -q; done
git worktree remove --force /tmp/life-baseline
```

## Profiling

### Flight Recorder

`-Pjfr` attaches a recording to any JVM the build forks — `run`, `bench` or `renderBench`.
The value is passed to `StartFlightRecording` verbatim, so any of its options work; it
defaults to `duration=30s`.

```bash
./gradlew bench -Pjfr
./gradlew renderBench -Pjfr=duration=60s
./gradlew run -Pjfr=duration=30s
```

Recordings land in `build/jfr/<task>.jfr`. Read them with the JDK's own tool, or open them
in JDK Mission Control:

```bash
jfr summary build/jfr/bench.jfr
jfr print --events jdk.ExecutionSample build/jfr/bench.jfr
```

A quick hot-method count, which is usually all you want:

```bash
jfr print --events jdk.ExecutionSample build/jfr/run.jfr \
  | grep -oE 'com\.conway\.[A-Za-z.]+' | sort | uniq -c | sort -rn | head -20
```

To attach to an already-running instance instead, use `jcmd <pid> JFR.start` and
`jcmd <pid> JFR.dump`.

### Compose recomposition

Whether a state change recomposes a little or a lot is decided statically, by whether each
composable is skippable and whether its parameters are stable. The Compose compiler will
report that directly, which is cheaper and more reliable than instrumenting a running UI to
count recompositions.

```bash
./gradlew compileKotlin --rerun-tasks -PcomposeReports
```

Reports land in `build/compose-reports`; `*-composables.txt` is the readable one. It is
opt-in and forces a full recompile, so it is off by default.

Two things worth knowing when reading it:

- Strong skipping is on by default for this Kotlin version. Lambda parameters are
  auto-remembered, and an unstable parameter is compared by instance rather than treated as
  always-changed — so an unstable type in a signature does not by itself mean the composable
  recomposes.
- A parameter marked `stable` that never changes identity (a `Function0` reading state
  inside, for example) lets the composable be skipped entirely, moving the work to whichever
  phase actually reads the state.

To diff a change against the baseline, generate the report in a worktree the same way as
for the benchmarks — the compose block has to be added to the baseline's `build.gradle.kts`
first, since it did not exist there.
