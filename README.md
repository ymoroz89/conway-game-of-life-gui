# Conway's Game of Life

A Swing implementation of Conway's Game of Life with a multithreaded rules engine.

## Running

```bash
mvn clean package
```

```bash
java -jar target/GuiLife.jar
```

The jar is self-contained; no sibling `libs/` directory is needed.

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

Every setting is a system property; the defaults are shown.

| Property | Default | Meaning |
| --- | --- | --- |
| `conway.gridSize` | `201` | Board width and height in cells; also the size selected on startup. A value that is not one of the presets is added to the board-size control rather than replaced |
| `conway.boardSizePx` | `1000` | Target board size in pixels; cell size is derived |
| `conway.targetFps` | `60` | Repaint rate, independent of the simulation rate |
| `conway.maxPartitions` | `min(gridSize, 2 × cores)` | Upper bound for the partition control |
| `conway.partitions` | chosen per board and machine | Partition count to start with; overrides the recommendation |
| `conway.minRowsPerPartition` | `50` | Rows a band needs before splitting again pays off; retunes the recommendation |
| `conway.log.dir` | `logs` | Log directory |
| `conway.log.level` | `INFO` | Root log level |

```bash
java -Dconway.gridSize=401 -Dconway.log.level=DEBUG -jar target/GuiLife.jar
```

## Design

The application is a single-writer actor with an immutable-snapshot read path.

- **`SimulationEngine`** owns the board. It is the only thread that ever mutates
  simulation state. Everything else — the UI, tests — submits commands to a queue.
  Pausing blocks on `BlockingQueue.take()`, so a queued step request can never be
  lost and a spurious wakeup can never advance the board.
- **`Generation`** is an immutable snapshot published through a `volatile` field.
  Readers see generation N or N+1 in full, never a mixture, and need no lock. Cells
  are flat `boolean[]` indexed `row * width + col`.
- **`LifeRules`** splits a generation into contiguous row bands. Bands write to
  disjoint slices, so no synchronisation is needed between workers; `invokeAll`
  supplies the happens-before edge. A failing band aborts the generation rather than
  silently producing a wrong board.
- **`BoardView`** writes colours into the `int[]` backing one `BufferedImage` and
  performs a single scaled blit.
- **`LifeFrame`** pulls the newest snapshot on a fixed timer. The simulation is not
  throttled by the repaint rate, and the repaint rate is not swamped by the
  simulation.

The grid is bounded, not toroidal: neighbours off the edge count as dead.

Patterns are data, in the plaintext [`.cells`](src/main/resources/patterns) format,
not hard-coded index assignments.

## Testing

```bash
mvn test
```

Tests are headless and run on a build agent with no display.

The load-bearing test is
[`PartitionEquivalenceTest`](src/test/java/com/conway/engine/PartitionEquivalenceTest.java):
partitioning is a work-distribution concern, so the resulting board must not depend on
how many bands the work was split into. Asserting that directly catches off-by-one
errors at band boundaries, rows computed twice or skipped, and accidental sharing of
mutable state between workers.

## Benchmark

```bash
java -cp target/GuiLife.jar com.conway.bench.GenerationBenchmark
```

Optional arguments: grid size, generations per measurement.

Measured on a 201×201 board, 10 available cores, best of 5 runs of 300 generations:

| Partitions | Generations/s | Speedup |
| --- | --- | --- |
| 1 | 12,401 | 1.00× |
| 2 | 15,520 | 1.25× |
| 4 | 16,238 | 1.31× |
| 8 | 16,241 | 1.31× |
| 16 | 15,464 | 1.25× |
| 20 | 15,442 | 1.25× |

**Parallelism buys about 1.3× on 10 cores, and adding partitions past 8 makes things
worse.** A single generation on this board takes roughly 80 µs, which is small enough
that task dispatch and the join barrier dominate. The simulation is already about 200×
faster than the 60 fps display, so on the default board the threading has no effect on
what you see; decoupling rendering from the simulation is what matters.

### How the default partition count is chosen

Parallelism pays off as a function of the *board*, not the core count. Speedup over a
single partition, same machine and harness:

| Board | 1 | 2 | 4 | 8 | 16 | 20 | Best |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 201 | 1.00× | 1.13× | **1.15×** | 1.11× | 1.08× | 1.03× | 4 |
| 401 | 1.00× | 1.66× | 2.23× | **2.30×** | 2.27× | 2.17× | 8 |
| 801 | 1.00× | 1.80× | 3.13× | 3.07× | **3.53×** | 3.52× | 16 |
| 1601 | 1.00× | 1.86× | 3.39× | 3.95× | 4.92× | **5.10×** | 20 |

At every size the fastest result lands at roughly **fifty rows per band**, and the
largest board is still improving when it hits the cap. That invariant is the rule
[`PartitionPlanner`](src/main/java/com/conway/engine/PartitionPlanner.java) implements:
`clamp(1, rows / 50, maxPartitions)`. It reproduces the measured optimum at all four
sizes, and it keeps small boards single-threaded instead of paying dispatch cost for
work that does not exist.

Two consequences worth knowing:

- Defaulting to `availableProcessors()` would be **slower** than the chosen value on the
  default board — 10 partitions sits past the peak, on the declining side of the curve.
- The cap deliberately allows more bands than cores. These are asymmetric cores
  (6 performance + 4 efficiency); since a generation ends at a barrier, an efficiency
  core given an equal share delays everyone, and smaller bands let the faster cores
  absorb proportionally more.

The constant was calibrated on one machine and one JDK. Retune it for a deployment that
cares with `-Dconway.minRowsPerPartition=N`, or bypass it with `-Dconway.partitions=N`.

Treat these figures as indicative. This is a simple harness, not
[JMH](https://openjdk.org/projects/code-tools/jmh/) — use JMH before drawing any
conclusion more precise than "does adding threads help at all".

## Security scanning

```bash
mvn -Psecurity verify
```

Opt-in, because the first run downloads the full NVD data set. CI runs it weekly.
