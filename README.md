# Conway's Game of Life

An implementation of Conway's Game of Life written in **Kotlin** with a
**Compose Multiplatform** desktop UI and a multithreaded rules engine.

| | |
| --- | --- |
| Language | Kotlin 2.4.10, JVM bytecode target 25 |
| UI | JetBrains Compose Multiplatform for Desktop 1.12.0 (Skia renderer) |
| Build | Gradle 9.7.1, Kotlin DSL |
| Concurrency | Virtual threads (simulation actor and band workers) |

## Running

```bash
./gradlew run
```

Or build a self-contained native application bundle:

```bash
./gradlew createDistributable   # produces build/compose/binaries/main/app
```

The board starts with two Gosper glider guns in opposite corners. Click any cell to
toggle it, including while the simulation is running.

| Control | Effect |
| --- | --- |
| Board size | Board edge in cells: 200, 400 or 800. Changing it restarts the simulation on a fresh board, and is only available while stopped |
| Parallel partitions | Number of row bands each generation is split across; defaults to a value chosen for the board and machine, and re-derived whenever the board size changes |
| Start / Stop | Run continuously, or pause |
| Next generation | Advance exactly one generation |
| Clear | Empty the board and reset the counter |

Live cells are mint, cells that have died fade to plum, and cells where nothing has
ever lived stay near-black.

## Configuration

Every setting is a system property; the defaults are shown. Properties are forwarded
from the Gradle command line to the application JVM:

```bash
./gradlew run -Dconway.gridSize=401 -Dconway.log.level=DEBUG
```

| Property | Default | Meaning |
| --- | --- | --- |
| `conway.gridSize` | `201` | Board width and height in cells; also the size selected on startup. A value that is not one of the presets is added to the board-size control rather than replaced |
| `conway.boardSizePx` | `1000` | Target board size in pixels; cell size is derived |
| `conway.targetFps` | `60` | Render-loop rate, independent of the simulation rate |
| `conway.maxPartitions` | `min(gridSize, 2 x cores)` | Upper bound for the partition control |
| `conway.partitions` | chosen per board and machine | Partition count to start with; overrides the recommendation |
| `conway.minRowsPerPartition` | `50` | Rows a band needs before splitting again pays off; retunes the recommendation |
| `conway.log.dir` | `logs` | Log directory |
| `conway.log.level` | `INFO` | Root log level |

## Design

The application is a single-writer actor with an immutable-snapshot read path; that
architecture carried over from the original Java/Swing codebase unchanged.

- **`SimulationEngine`** owns the board. It is the only thread that ever mutates
  simulation state. Everything else - the UI, tests - submits commands to a queue.
  Pausing blocks on `BlockingQueue.take()`, so a queued step request can never be
  lost and a spurious wakeup can never advance the board. Both the simulation actor
  and the band workers run on virtual threads.
- **`Generation`** is an immutable snapshot published through a single `@Volatile`
  field together with its statistics (`EngineSnapshot`). Readers see generation N or
  N+1 in full, always with the throughput numbers that belong to it, and need no
  lock. Cells are flat `BooleanArray`s indexed `row * width + col`.
- **`LifeRules`** splits a generation into contiguous row bands. Bands write to
  disjoint slices, so no synchronisation is needed between workers; `Future.get()`
  supplies the happens-before edge. A failing band aborts the generation rather than
  silently producing a wrong board.
- **`BoardRenderer`** diff-updates an `IntArray` pixel buffer (one entry per physical
  pixel) and mirrors it into Skia-managed RGBA storage; `BoardCanvas` draws it with a
  single nearest-neighbour scaled blit, letterboxed so cells stay square and crisp.
  The renderer and its backing image are rebuilt only when the board dimensions or
  the cell size change, not on every frame.
- **`App`** pulls the newest snapshot inside a `withFrameNanos` render loop - the
  Compose analogue of the old Swing timer. The simulation is not throttled by the
  frame rate, and the frame rate is not swamped by the simulation.

The grid is bounded, not toroidal: neighbours off the edge count as dead.

Patterns are data, in the plaintext [`.cells`](src/main/resources/patterns) format,
not hard-coded index assignments.

## Testing

```bash
./gradlew test
```

Tests are headless and run on a build agent with no display.

The load-bearing test is
[`PartitionEquivalenceTest`](src/test/kotlin/com/conway/engine/PartitionEquivalenceTest.kt):
partitioning is a work-distribution concern, so the resulting board must not depend on
how many bands the work was split into. Asserting that directly catches off-by-one
errors at band boundaries, rows computed twice or skipped, and accidental sharing of
mutable state between workers.

## Benchmark

```bash
./gradlew bench
# or with arguments: grid size, generations per measurement, executor mode
./gradlew bench --args="401 300 virtual"
```

Measured on a 201x201 board, 10 available cores, best of 5 runs of 300 generations:

| Partitions | Generations/s | Speedup |
| --- | --- | --- |
| 1 | 12,401 | 1.00x |
| 2 | 15,520 | 1.25x |
| 4 | 16,238 | 1.31x |
| 8 | 16,241 | 1.31x |
| 16 | 15,464 | 1.25x |
| 20 | 15,442 | 1.25x |

**Parallelism buys about 1.3x on 10 cores, and adding partitions past 8 makes things
worse.** A single generation on this board takes roughly 80 microseconds, which is
small enough that task dispatch and the join barrier dominate. The simulation is
already about 200x faster than the 60 fps display, so on the default board the
threading has no effect on what you see; decoupling rendering from the simulation is
what matters.

### How the default partition count is chosen

Parallelism pays off as a function of the *board*, not the core count. Speedup over a
single partition, same machine and harness:

| Board | 1 | 2 | 4 | 8 | 16 | 20 | Best |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 201 | 1.00x | 1.13x | **1.15x** | 1.11x | 1.08x | 1.03x | 4 |
| 401 | 1.00x | 1.66x | 2.23x | **2.30x** | 2.27x | 2.17x | 8 |
| 801 | 1.00x | 1.80x | 3.13x | 3.07x | **3.53x** | 3.52x | 16 |
| 1601 | 1.00x | 1.86x | 3.39x | 3.95x | 4.92x | **5.10x** | 20 |

At every size the fastest result lands at roughly **fifty rows per band**, and the
largest board is still improving when it hits the cap. That invariant is the rule
[`PartitionPlanner`](src/main/kotlin/com/conway/engine/PartitionPlanner.kt) implements:
`clamp(1, rows / 50, maxPartitions)`. It reproduces the measured optimum at all four
sizes, and it keeps small boards single-threaded instead of paying dispatch cost for
work that does not exist.

Two consequences worth knowing:

- Defaulting to `availableProcessors()` would be **slower** than the chosen value on the
  default board - 10 partitions sits past the peak, on the declining side of the curve.
- The cap deliberately allows more bands than cores. These are asymmetric cores
  (6 performance + 4 efficiency); since a generation ends at a barrier, an efficiency
  core given an equal share delays everyone, and smaller bands let the faster cores
  absorb proportionally more.

The constant was calibrated on one machine and one JDK. Retune it for a deployment that
cares with `-Dconway.minRowsPerPartition=N`, or bypass it with `-Dconway.partitions=N`.

The engine applies the same rule again per generation, against the *active region* rather
than the whole board, and computes inline when there is too little live area to fill the
bands. A sparse board is therefore never split: on a 201x201 grid holding a single blinker,
sixteen bands ran six times slower than computing inline. A consequence for the table above:
at 201 every partition count from 4 upwards now runs four bands, so differences between those
rows are measurement noise rather than scaling.

Treat these figures as indicative. This is a simple harness, not
[JMH](https://openjdk.org/projects/code-tools/jmh/) - use JMH before drawing any
conclusion more precise than "does adding threads help at all".
