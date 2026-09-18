# Profiling this application across JDK versions

**The protocol now lives in a skill, not in this file.**

```
.claude/skills/jdk-performance-comparison/
```

Say what you want compared and the skill takes it from there:

> Compare 21 and 25.
> Compare 21, 25 and 27.

It discovers the jenv mapping, confirms the branch map with you, builds each arm, runs the
condition rounds, parses the recordings and produces the comparison table.

## Why this file is a pointer

It used to hold the full protocol. So did `JVM-COMPARISON-PROMPT.md` before it. Three
copies of the same method drift apart, and the stale ones get followed — this file still
described `-Xmn512m` pinned rounds and a `tools/bench/run-matrix.sh` that no longer
exists, weeks after both had been replaced. The skill is the single source; this file
exists so the old filename still leads somewhere useful.

The previous full text is in git history (`git log --follow PROFILING-PROMPT.md`).

## Running a comparison

Just say what you want compared. The skill handles discovery, confirmation, the runs, the
parsing and the table:

> Compare 21 and 25.
> Profile 21, 25 and 27 and tell me if the upgrade is worth it.
> Which JDK is faster for this app?

To add or drop a JDK, edit `tools/bench/jdks.conf` — one line per arm, `label jenv-name
branch`. Lines are commented out, not deleted, so the set is easy to widen:

```
21  openjdk64-21.0.11   main
25  openjdk64-25.0.3    java25
# 27  zulu64-27           java27
```

### Bootstrap — needed once, and after any `git clean`

Two build artifacts are not in the repo (`tools/bench/out/` is gitignored), so a fresh
clone must rebuild them before the first run:

```bash
git worktree add /tmp/frozen main && JAVA_HOME="$(jenv prefix openjdk64-21.0.11)" mvn -q -DskipTests clean package -f /tmp/frozen/pom.xml && cp /tmp/frozen/target/GuiLife.jar tools/bench/shared-r21.jar && git worktree remove /tmp/frozen
```

```bash
"$(jenv prefix openjdk64-21.0.11)/bin/javac" -cp tools/bench/shared-r21.jar -d tools/bench/out tools/bench/drive/GuiDrive.java
```

Both must be built by **JDK 21** specifically, so the driver bytecode is major 65 and one
build drives every JVM in the set. Verify:

```bash
javap -v -cp tools/bench/out drive.GuiDrive | grep 'major version'   # expect 65
```

### Running it directly

The skill's runner can also be invoked by hand:

```bash
~/.claude/skills/jdk-performance-comparison/scripts/run-matrix.sh --rounds C1,C3 --reps 5 --units 2000000
```

It reads `tools/bench/jdks.conf` and `tools/bench/bench.conf`, writes one JSON row per run
to `profiling/results.jsonl`, and restores `jenv global` and `.java-version` on every exit
path. Parse the recordings with the skill's `scripts/parse_jfr.py`.

**A GUI round makes the machine unusable while it runs** — each run raises a window and
holds it frontmost for ~100 s. C1+C3 at n=5 for two JDKs is 20 runs, about 35 minutes.

## What stays in this repo

The skill is project-agnostic. Everything specific to Conway lives in `tools/bench/`:

| File | Purpose |
|---|---|
| `jdks.conf` | which JVMs, and the branch each uses — `21 → main`, `25 → java25`, `27 → java27` |
| `bench.conf` | how to build, launch and read this app; round option overrides |
| `drive/GuiDrive.java` | launches the real GUI and issues the same commands the Start/Stop buttons send |
| `shared-r21.jar` | a `release=21` build so one driver bytecode (major 65) runs on every JVM |

**This project measures the shipping GUI application**, not a headless harness: window,
EDT, incremental repaint. `bench.conf` deliberately defines no headless entry point —
harness numbers measure the harness, and on this machine foreground CPU treatment is worth
up to 3.3× against a JDK effect of a few percent.

## Results

`PROFILING-LOG.md` is the running record — every round, what it found, and what was
withdrawn when a later round contradicted it. Read the withdrawals; they are the most
useful part.
