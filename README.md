# Conway's Game of Life

A Game of Life desktop application with a multithreaded rules engine, written
in **Rust** (backend + GUI) — a single-writer actor engine, immutable
snapshots, band-parallel rule evaluation, and a dark-themed GUI built on
[egui/eframe](https://github.com/emilk/egui).

The whole application lives in [rust/](rust/).

## Building and running

```bash
cd rust
cargo run --release
```

The window opens with two Gosper glider guns in opposite corners. Click any
cell to toggle it, including while the simulation is running.

| Control | Effect |
| --- | --- |
| Board size | Board edge in cells: 200, 400 or 800. Changing it restarts the simulation on a fresh board, and is only available while stopped |
| Parallel partitions | Number of row bands each generation is split across; defaults to a value chosen for the board and machine, and re-derived whenever the board size changes |
| Start / Stop | Run continuously, or pause |
| Next generation | Advance exactly one generation |
| Clear | Empty the board and reset the counter |
| Quit | Close the application |

Live cells are mint, cells that have died fade to plum, and cells where
nothing has ever lived stay near-black. The status bar shows generation,
throughput in generations/second, population, the partition count and the
lifecycle state.

## Configuration

Settings are read from environment variables with a `CONWAY_` prefix:

| Variable | Default | Range | Meaning |
| --- | --- | --- | --- |
| `CONWAY_GRID_SIZE` | 201 | 3..10 000 | Board edge in cells |
| `CONWAY_BOARD_SIZE_PX` | 1000 | 50..20 000 | Target board edge in pixels |
| `CONWAY_TARGET_FPS` | 60 | 1..240 | Rendering target |
| `CONWAY_MAX_PARTITIONS` | machine-derived | 1..grid | Upper bound on row bands |
| `CONWAY_PARTITIONS` | machine-derived | 1..max | Starting partition count |
| `CONWAY_MIN_ROWS_PER_PARTITION` | 50 | 1..grid | Rows a band needs before splitting again pays for itself |

Example:

```bash
CONWAY_GRID_SIZE=401 cargo run --release
```

## Architecture

The crate is split into three modules:

* `src/engine` — the simulation. `SimulationEngine` is a single-writer actor:
  every mutation is a queued command processed by one simulation thread, and
  the board is published as an immutable `EngineSnapshot` (a `Generation`
  behind an `Arc` plus its statistics) under a lock. `LifeRules` computes each
  generation across parallel row bands with rayon, using the same
  ~50-rows-per-band rule the engine has been calibrated with.
* `src/model` — `Generation` (flat alive/ever-alive buffers, active region,
  population), `Pattern`/`.cells` parsing and the bundled Gosper glider gun.
* `src/ui` — the egui application: sidebar controls, status bar, and a board
  that renders one texel per cell into a texture updated only when the
  published generation changes, then maps clicks back onto cells.

The simulation is never throttled by the repaint rate: the engine publishes
independently and the UI polls the latest snapshot once per frame.

## Tests and benchmarking

```bash
cd rust
cargo test                 # rules, partition equivalence, engine actor, pattern/model, geometry
cargo run --release --bin bench -- [gridSize] [generations]
```

The benchmark times generations per second across 1, 2, 4, ... partitions on a
seeded board, best of five after a warm-up run. Use `cargo run --release` and
enlarge the window (or set `CONWAY_GRID_SIZE`) for the interactive experience;
the default 201 board is intentionally light so edits stay fluid.
