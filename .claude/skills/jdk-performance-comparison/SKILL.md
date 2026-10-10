---
name: jdk-performance-comparison
description: >
  Benchmark and profile an application across multiple JDK versions (21 vs 25 vs 27, and
  so on) using jenv, a git ref per version (a release tag, or a branch) and JFR, producing a comparison table of
  throughput, heap, GC and allocation plus an honest assessment of what is actually
  attributable to the JDK. Use this whenever the user names two or more Java or JDK
  versions to compare, asks which JDK is faster, asks whether a Java upgrade or migration
  is worth it or what it cost in performance, wants to profile or benchmark across JDK
  releases, or needs to decide whether a measured difference between JVMs is real or just
  noise. Also use it when setting up JFR recordings for a version comparison, when
  switching JDKs with jenv for measurement, or when interpreting .jfr files whose event
  schemas differ between JDK versions.
---

# Cross-JDK performance comparison

Compare an application's performance across JDK versions and report what the difference
actually is — including when the honest answer is "smaller than the noise".

The user supplies one thing: **the list of versions** ("compare 21 and 25", "compare 21,
25 and 27"). Everything else you discover, confirm, then run. Two versions and six work
the same way.

## Why this is harder than it looks

Naive JDK comparisons produce confident wrong answers, reliably, for four reasons. The
protocol below exists to close each one:

- **The label is not the JVM.** jenv aliases go stale, resolve to a different vendor, or
  get shadowed by a file you forgot about. Only the recording is evidence.
- **The environment dwarfs the effect.** Foreground versus background CPU placement has
  measured a 3.3× swing against a ~9% JDK effect. Mix environments and you measure the
  scheduler.
- **Measurement overhead is not equal across JDKs.** Enabling JFR cost one release 8.0%
  and another 11.9% on the same workload. A round with profiling on cannot be read as a
  JDK comparison until that per-JDK cost is subtracted out.
- **Run-to-run variance is large.** ~6% is typical. A single run per version cannot
  resolve a 9% difference, and two runs of the *same* JDK will happily differ by more
  than the effect you are hunting.
- **GC ergonomics masquerade as runtime differences.** JDKs size the heap very
  differently out of the box — 620 / 300 / 262 MiB committed for the same workload across
  three releases. A default-heap throughput number bundles codegen with GC policy and
  cannot attribute either.

## Step 1 — discovery

Report what you find; never guess any of it. Run `scripts/discover_jdks.sh`, or directly:

```bash
jenv versions --bare | while read -r v; do printf '%-24s ' "$v"; "$(jenv prefix "$v")/bin/java" -version 2>&1 | head -1; done
git tag -l && git branch -a && git status --porcelain
```

**jenv names are labels, and labels lie.** Expect to find aliases that are stale by a
patch release, and bare numeric aliases that resolve somewhere surprising — on one machine
`jenv prefix 25.0.4` returns GraalVM while `25` returns OpenJDK. Select fully-qualified
names (`openjdk64-*`, `zulu64-*`), never bare numbers, and record what each resolved to.

**Never include GraalVM in a JDK-version comparison** — it compares JIT compilers, not JDK
versions. If a requested version exists only as GraalVM, say so and stop.

**Note each arm's vendor.** A set mixing vendors (Homebrew OpenJDK against Azul Zulu, say)
carries an unquantified vendor component in every delta. That does not block the study,
but it must be stated, and a small cross-vendor delta cannot be attributed to the version.

Then work out the project configuration and present it with the mapping table:

| Field | How to find it |
|---|---|
| Ref per version | `git tag -l`, then `git branch -a`; the release setting in the build file at each ref. Prefer release tags — see **Pin each arm to a tag** |
| Build command, artifact, launch command | README, build file, CI config |
| Automated driver | something that starts the app, runs a fixed workload, stops, prints a result line. It may live only at the newest ref: an older release tag can predate the rig, so build the *application* from the arm's ref and take the rig from the current checkout |
| Per-ref extra flags | compiler config (e.g. a ref needing `--enable-preview`) |
| Workload unit and size | what the app counts; see **the workload floor** |
| Throughput ground truth | a line the app itself logs — never JFR inference |
| Environment | how the app *ships* — GUI foreground, server, CLI. This is what you measure |

**Profile the shipping application, not a harness.** A benchmark harness measures the
harness: different CPU treatment, no rendering or I/O, a different live set. Two rules
follow, both learned the hard way:

- **Give the runner no harness mode at all.** If a headless path exists it gets used —
  it is faster and unattended, so it wins whenever someone is tired or in a hurry. Name
  every artifact for its environment (`gui-C1-21-rep1.jfr`) and carry the environment as
  a field on every result row, so provenance survives into the analysis.
- **Data from the wrong environment is not a fallback.** When the only dataset to hand
  came from a harness, the answer is "this cannot answer the question, here is what has
  to be run" — not an analysis with a caveat bolted on. The caveat gets dropped the
  moment the table is quoted, and the user's attention was spent on a number that was
  never going to count.

**Wait for confirmation before running anything.** Getting the ref map or the JDK
mapping wrong wastes hours of machine time, and both are cheap to check with a human.

If the project has no automated driver, write one and commit it under `tools/bench/` or
the project equivalent — outside the source tree whose diff you measure. Keeping it in the
repo matters: a driver kept in `/tmp` on one project did not survive a reboot and had to be
reconstructed from prose, costing a full round.

### The workload floor

Each run must be long enough that startup is under 1% of wall clock, the run produces at
least several hundred GC cycles, and per-unit metrics reproduce between runs. Establish
this per project and record it. Raise it freely; never lower it. A short run does not give
a noisier answer — it invalidates the GC rows outright.

### What ref-per-version actually measures

Each arm is a JDK **and** the code at that JDK's ref, so a delta bundles the runtime with
whatever the migration changed in source. Quantify rather than assume:

```bash
git diff --stat <ref-a> <ref-b> -- <source-dir>
```

Report it per adjacent pair. A one-line difference means effectively a pure JDK
comparison; a few hundred lines including a rewrite means that column bundles a code change
with a JDK change, and **no delta across it belongs to the JDK alone**. Mark which columns
are clean in the table itself.

If the user wants a *pure* JDK comparison instead, build one artifact from one ref and
run that same artifact on every JVM. Ask which they want when the source diff is large.

### Pin each arm to a tag

A ref can be a branch, a tag or a commit — the runner checks each one out with
`git worktree add --detach`, so all three work. **Prefer a tag per release.** A branch moves:
between a study and its reproduction it can gain commits, change what it means, or be
merged away and deleted, and then the report names code that no longer exists — or worse,
different code under the same name. A tag does not move, so "built from `java-21`" still
means one exact commit years later. If the project releases from branches, record the
commit hash of every arm alongside the branch name, in the results and in the report.

## Step 2 — preflight

Report each check; stop on failure.

1. **Working tree clean** (`git status --porcelain` empty). Switching refs with
   uncommitted changes either fails or carries them into the next arm. If dirty, ask —
   do not stash on your own initiative.
2. **Record restore state**: current branch (or detached commit), `jenv global`, and any `.java-version`
   contents. Restore all three when the study ends, including after a failure.
3. **Neutralise `.java-version`.** jenv precedence is `JENV_VERSION` > local
   `.java-version` > global, so a `.java-version` in the project root **silently overrides
   `jenv global`** and every arm runs the same JVM under different labels. This has
   happened. Move it aside and restore it afterwards.
4. **Confirm the build tool honours the switch.** Maven and Gradle read `JAVA_HOME`, not
   jenv's shims — export `JAVA_HOME="$(jenv prefix <name>)"` for builds.
5. **Determinism gate.** Run a fixed workload on each JDK and compare a checksum of the
   final state. All must match, or the arms are not doing the same work and nothing
   downstream compares. This is a gate, not a round: it produces no table and no timing.
   It exists because matching *counts* never proved matching *results*, and an arm quietly
   doing less work looks exactly like an arm being fast.
6. **Output directory empty, or explicitly approved.** List any existing recordings,
   result rows, derived JSON and app logs, show the user, and **ask before deleting
   anything** — see Step 8. Stale artifacts from an earlier run match the same globs as
   the new ones and get pooled into the analysis without announcing themselves.

## Step 3 — calibration

Before the study, and reported to the user:

1. **Noise floor.** Run the workload 5 times on one JDK, one ref, nothing changed.
   Report min, max, median, spread. Any later delta smaller than this is not a result.
2. **Environment sensitivity**, if the app has a foreground/background distinction. Pick
   one environment and hold it for the entire study.

If the noise floor exceeds the effect size being looked for, say so *before* spending
hours. That conversation is the single most valuable thing this step produces.

## Condition rounds

**Run two rounds by default.** The others are follow-ups, each triggered by a specific
result, and running them unconditionally doubles the machine time to answer questions
nobody asked.

| | Round | Condition | What only this round can tell you | Run it |
|---|---|---|---|---|
| **Core** | **C1** | Default heap, no JFR | Throughput, uncontaminated by profiling overhead | always |
| **Core** | **C3** | Default heap + JFR `settings=profile` | Heap, GC, allocation, pauses as each JDK sizes itself | always |
| Follow-up | **C2** | Pinned heap, no JFR | Whether a C1 throughput delta is codegen or GC ergonomics | only if C1 shows a delta |
| Follow-up | **C4** | Pinned heap + JFR | Whether a C3 pause delta is the collector or just a smaller eden | only if C3 shows a pause delta worth attributing |
| Optional | **C5** | Parallelism sweep | Where an effect lives: compute versus dispatch | only with a concurrency knob and a delta to locate |

Two rounds is not a shortcut; it is the minimum that answers the question. C1 gives the
throughput number and C3 gives everything else, because JFR is required for the memory
rows and distorts the throughput row. That split is forced by the tooling, not chosen.

**Why the follow-ups are conditional.** They are *attribution* rounds: they explain a
difference, so with no difference to explain they return nothing. They also have to be
constrained carefully, or they delete their own measurement — pinning the young generation
with `-Xmn` forces committed heap, young-gen capacity and collection count to match across
arms **by construction**, because you set them. In one matrix those three rows came back
at 1024.0 MiB, 511.8 MiB and 159 collections on both JDKs; only the pause rows carried
information. Use a young-gen *floor* instead (see **JVM options**), which controls the
confound while leaving the rows free to differ. Even then C4 is not a round's worth of
value; it is a targeted test of one question:

- **C1 shows a throughput delta** → run C2. Survives at equal heap = codegen; vanishes =
  the default-heap result was GC sizing all along.
- **C3 shows a pause or GC delta you want to attribute** → run C4. Survives at equal eden
  = a collector change; vanishes = a consequence of eden size.
- **Neither shows a delta** → stop. "No difference outside noise" at n=5 against a
  measured floor is a complete answer, and a pinned-heap round cannot make it more so.

Budget accordingly: two rounds is 2 × versions × 5 runs. Adding both follow-ups doubles
that, so quote the core cost first and the follow-up cost only once a result calls for it.

### JVM options

State the collector explicitly on every run (e.g. `-XX:+UseG1GC`). Never rely on the
default — the default collector is precisely the kind of thing that changes between
releases and would silently become the variable.

**Round options are configuration, not constants.** Define `ROUND_<name>` in `bench.conf`
to override any round or add one of your own; `{JFR}` expands to the flight-recorder
options. Three things worth knowing:

- **Prefer a young-gen floor over `-Xmn` when controlling for heap.** `-Xmn` pins the
  young generation and turns adaptive sizing off entirely, which answers "how do they
  compare with G1's sizing disabled" — a question nobody asked, and it makes committed
  heap, young capacity and collection count identical *by construction*, so those rows
  stop carrying information. `-Xms1g -Xmx1g -XX:+UnlockExperimentalVMOptions
  -XX:G1NewSizePercent=40` keeps adaptation alive while stopping one JDK shrinking eden to
  a third of the other, which is the actual confound.
- **A round at the project's own shipping settings is often the most useful one.** "How do
  these JDKs compare under the tuning we actually deploy" is a fair and frequently more
  relevant question than "how do they compare at their own defaults". Run it as a
  *separate named round* and report it separately — never fold tuned and default runs into
  one table, because they answer different questions.
- **Leave the core rounds untuned.** C1 and C3 exist to observe how each JDK sizes the
  heap on its own. Adding a heap flag there deletes the finding rather than refining it.

**Verify every flag on every arm before measuring.** An experimental flag can be renamed,
re-defaulted or removed between releases, and `-XX:+UnlockExperimentalVMOptions` does not
make it portable. The runner launches each JVM with the round's options and aborts if any
arm rejects them — a round whose flags do not resolve identically everywhere is not a
comparison. Check the resolved *values* too, not just acceptance: confirm with
`-XX:+PrintFlagsFinal` that the flag reads the same on every arm, since a silently
different default is exactly as damaging as a rejected option and far quieter.

| Round | Added options |
|---|---|
| C1, C5 | *(none — default ergonomics, which is what these rounds measure)* |
| C2, C4 | Heap bounded with a young-gen floor: `-Xms1g -Xmx1g -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=40` |
| C3, C4 | `-XX:FlightRecorderOptions=stackdepth=128 -XX:StartFlightRecording=name=prof,settings=profile,filename=<round>-<jdk>-rep<n>.jfr,dumponexit=true,maxsize=500m` |
- **Add no tuning the app does not ship with.** A README suggesting
  `-XX:MaxGCPauseMillis=20` for comfortable interactive use is suggesting exactly the flag
  that would replace the JDK's ergonomics with yours and destroy C1 and C3.
- **Per-ref flags are a confound.** If one ref needs `--enable-preview` and another
  cannot accept it, that flag differs across arms; it changes verification and can disable
  the default CDS archive. Record it; do not call the arms identical.
- `settings=profile` is required — default settings record **zero** GC events, leaving no
  pause data at all. Profiling also costs throughput unequally by version, which is why
  C1/C3 and C2/C4 are separate rounds and a JFR run is never compared to a non-JFR one.

### Always report the options, and the defaults behind them

Every report states, per round, **the exact JVM arguments read back from the recording**
(`jdk.JVMInformation.jvmArguments`) — not what the runner script intended to pass. A
reader cannot interpret a heap number without knowing whether a heap flag was set, and
"what were `-Xmx` and `-Xmn`?" is the first question any reviewer asks.

For rounds that leave heap to ergonomics, also capture what each JVM *chose* as its
starting point:

```bash
"$(jenv prefix <name>)/bin/java" -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+PrintFlagsFinal -version \
  | grep -E '\b(MaxHeapSize|InitialHeapSize|MinHeapSize|MaxGCPauseMillis|G1HeapRegionSize|G1NewSizePercent|G1MaxNewSizePercent|InitiatingHeapOccupancyPercent|GCTimeRatio|MinHeapFreeRatio|MaxHeapFreeRatio)\b'
```

This distinguishes two very different findings that a committed-heap difference alone
cannot separate:

- **The defaults changed** — the releases ship different flag values, and the difference
  is configuration.
- **The adaptive behaviour changed** — every flag is identical and the JVMs still diverge
  at runtime, which is a real change in the collector's sizing policy.

The second is a much stronger result, and it is only claimable if you checked. In one
comparison the flags were identical on both releases (same 512 MiB initial heap, same
8 GiB max, same 5/60 young-gen bounds, same 200 ms pause target) and the two JVMs still
settled at 620 MiB and 300 MiB committed — one growing above its initial heap, the other
shrinking below it. Without the flag dump that reads as "they changed the defaults", which
would have been wrong.

## Step 4 — run the matrix

Use the bundled runner. It owns everything that is the same in every project and easy to
get wrong — jenv resolution, GraalVM rejection, `.java-version` neutralising and restore,
per-ref worktree builds, `java -version` verification per arm, interleaving, round
options, flag verification, and one JSON row per run:

```bash
scripts/run-matrix.sh --rounds C1,C3 --reps 5 --units <workload size>
```

It reads two project files, which are the only things you write per project:

| File | Contents |
|---|---|
| `jdks.conf` | one line per arm: `label  jenv-name  ref` — the ref is a tag, branch or commit |
| `bench.conf` | `ENV`, `BUILD_CMD`, `RUN_CMD`, the output regexes, and any `ROUND_<name>` overrides |

`RUN_CMD` takes `{JAVA}`, `{OPTS}`, `{UNITS}` and `{WORKTREE}` placeholders — use
`{WORKTREE}` to run the artifact built from the arm's own ref; `BUILD_CMD` takes
`{WORKTREE}`. Rounds may be overridden or invented with `ROUND_<name>`, using `{JFR}`
where the recorder options belong.

What it does per arm, and why each step is there rather than left to the operator:

```bash
git worktree add --detach /tmp/jdkcmp-<label> <ref>   # not `git checkout`: a dirty tree
                                                      # cannot leak into an arm, and
                                                      # --detach lets two arms share a ref
jenv global <jenv-name>
java -version                                    # MUST report the intended version,
                                                 # evaluated in the project directory
JAVA_HOME="$(jenv prefix <name>)" <build>
<run with round options and driver>
```

That `java -version` line is not ceremony — it is what catches `.java-version` shadowing,
a stale alias, and a GraalVM resolution. It is recorded per arm in `results.jsonl`
alongside the exact `jvm_opts` that run used, so the report never has to reconstruct
either from the script.

**Interleave by repetition; do not run each JDK to completion in turn.** Blocked order
(`A A A A A B B B B B`) silently charges one arm for the machine warming up, and a round
is long enough that it will. Switching jenv costs milliseconds, so make repetition the
outer loop:

```
rep 1: [switch→21, run] [switch→25, run] [switch→27, run]
rep 2: [switch→21, run] [switch→25, run] [switch→27, run]
```

Build once per ref up front and reuse, so per-rep cost is just the switch and the run.

- **5 iterations per version per round.** Not 1, not 3. GC young-gen sizing has been
  observed to be *bimodal per JVM start* — the same artifact landing on two heap modes a
  factor of 1.7 apart. At n=1 that produces a confident, wrong story.
- **Drift check every round.** Regress throughput on rep index within each arm. A
  monotonic decline means the session drifted, most likely thermally. The interleave keeps
  the *comparison* valid, but if the decline exceeds the claimed effect, the round is
  inconclusive — re-run from cold.
- **Discard and re-run, never average away.** A run failing an identity gate or showing
  external CPU load is not a data point.
- **Calibrate a readiness gate before trusting it to reject.** Identity gates are
  binary — the JVM either is the intended one or is not. Environment gates (window focus,
  foreground state) are inferences, and a badly designed one throws away good data. Sample
  such state *periodically through the run*, not only at its start and end: a two-instant
  sample catches a moment, not a condition. Before discarding anything on an environment
  gate, check whether it actually correlates with the metric, **within round and within
  JDK** — one gate rejected half a matrix, and the rejected runs turned out to differ by
  −0.8%, −1.9%, +2.7% and +3.2%, signs flipping, i.e. not at all. Pooling across rounds
  made it look like a 7% effect purely because the rejected runs were concentrated in the
  round that had profiling enabled. If the correlation is absent, keep the runs, report
  the gate status alongside, and fix the gate.
- **Report every iteration.** Use median and IQR over all reps, never best-of-N —
  best-of-N is a biased maximum carrying no variance estimate.
- **Restore branch, `jenv global` and `.java-version`** at the end, including on failure.

## Step 5 — parse

Use `scripts/parse_jfr.py` — it reduces each event family independently, never filters by
wall clock (families in one recording can sit on different clock bases, so a naive time
window returns nothing), and writes a `null` plus a `reason` for anything missing rather
than substituting a zero. Run it per recording:

```bash
python3 scripts/parse_jfr.py RECORDING.jfr --units <workload size> --jfr-bin "$(jenv prefix <name>)/bin/jfr" -o derived.json
```

Read `references/jfr-metrics.md` for the full metric set, the identity gates every run
must pass, and what each number means.

Read `references/jfr-version-compat.md` **before comparing any metric across versions** —
JFR's schema and event semantics change between releases, and some metrics are simply not
comparable across a given boundary. That file also defines how to record a difference and
why each one needs a tracked task rather than a quiet special case.

Before trusting a change to the parser, validate it by re-deriving a known-good result and
diffing against the recorded table. That check has caught real errors, including a pause
figure no event in the recording supported.

### Subtract the measurement cost before reading a profiled round

Compute the profiling overhead **per JDK** by comparing the same heap condition with and
without JFR (C1→C3, C2→C4):

```
JDK A: C1 median → C3 median = −8.0%
JDK B: C1 median → C3 median = −11.9%
```

If those differ, the profiled round's throughput row is mostly measurement artefact and
must not be quoted as a JDK difference — in that example the profiled round showed the
older JDK 3% ahead purely because JFR cost the newer one more. Report throughput from the
JFR-free round and use the profiled round for memory, GC and allocation, which is what it
is for. Note also that this per-JDK cost can invert between projects and releases, so
carry the measured numbers rather than a remembered direction.

## Step 6 — compare, and Step 7 — report

Read `references/reporting.md` for the table format, the acceptance criteria that decide
when a delta may be called a finding, and the structure of the written assessment.

The short version: one table per round, all versions side by side, generated from the
parsed data and never hand-transcribed; a `Comparable?` column carrying the source diff between refs,
vendor boundary and schema boundary for each metric; then prose covering what is
established, what is merely suggested and what run would settle it, what is not
attributable and why, and the single highest-value next experiment with its cost.

Do not round a finding up. "No difference outside noise" is a result, and at n=5 with a
measured noise floor it is a strong one.

## Step 8 — cleanup, and starting from a clean slate

**Start every run from an empty output directory, and always ask before deleting.**

Stale artifacts are not harmless clutter. Recordings and result rows from an earlier run
sit in the same directory, match the same glob, and get silently pooled into the next
analysis — which is how a harness dataset once ended up answering a question about the
GUI, and how rows at one workload size got averaged with rows at another. Whichever run
wrote them, they describe a different study.

### Before a run

1. **Inventory what is already there** — recordings, console logs, `results.jsonl`,
   derived JSON, app logs — and show the user the list with counts and total size.
2. **Ask whether to delete it.** Never delete on your own initiative, and never as a
   silent side effect of starting a run. The user may want a previous study kept, or
   archived under another name, or may have results in there you know nothing about.
3. **On approval, delete and start clean.** On refusal, write the new run to a
   *different* output directory (`--out`) rather than mixing generations of data in one
   place. Mixing is the failure this step exists to prevent; refusing to delete is not a
   reason to accept it.

An archive is often the right middle path — move the previous set aside with a name that
says what it was (`results-gui-2M-20260918.jsonl`) rather than deleting outright. Offer it.

### Before deleting anything, confirm the evidence survives

Recordings are the only artifact that cannot be regenerated cheaply — a deleted one costs
a full round, and the replacement is a different session, so it is not the same evidence.
Check all three before proposing deletion:

1. Every kept run has a derived JSON with the recording's SHA-256 and the `jvmVersion`
   read out of it. Those are the permanent record; commit them.
2. No derived file holds a `null` caused by a *parser failure* rather than a recorded
   capability gap. A parse error means the recording still contains something unread, and
   deleting it forecloses ever fixing the parser against real data.
3. Tables are generated from the current derived set, and the log entry is written.

If any of those fail, say so and propose keeping the recordings until they pass.

### After a run

Restore branch, `jenv global` and `.java-version` to their pre-study values — the runner
does this on every exit path, including failure and interrupt, but verify it happened.
