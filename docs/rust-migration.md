# Rust rework: design notes

This document maps the Java/Swing implementation (still in `src/main/java`) to
its Rust port in `rust/`, and calls out where the port deliberately diverges.

## Why a port, not a wrapper

The Java version is a Swing GUI calling a Java actor engine. Rewriting both
layers in Rust removes the JVM GC pauses (irrelevant at this workload but nice
to have), removes the virtual-thread executor in favour of rayon's work
stealing, and produces a single static-ish binary. The behavioural contract —
board rules, editing semantics, threading guarantees, the 50-rows-per-band
partition heuristic — is preserved so the existing test suite could be ported
almost line for line.

## Source mapping

| Java | Rust | Notes |
| --- | --- | --- |
| `engine.SimulationEngine` | `engine::SimulationEngine` | Same single-writer actor. `LinkedBlockingQueue` + virtual thread -> `std::sync::mpsc` + a named OS thread. `volatile EngineSnapshot` -> `Mutex<EngineSnapshot>` with `Arc<Generation>`; the UI briefly locks to read, then renders from the immutable `Arc`. |
| `engine.LifeRules` | `engine::LifeRules` | Band computation over row ranges. `ExecutorService.submit`/`Future.get` -> rayon `par_iter` over disjoint slices of the two destination buffers, so aliasing is impossible by construction. The calling thread still runs one band by virtue of rayon's join-scope work stealing. |
| `engine.PartitionPlanner` | `engine::PartitionPlanner` | Identical `rows / 50` recommendation and `min(grid, 2 × cores)` cap, using `available_parallelism()`. |
| `engine.SimulationSeeder` | `engine::SimulationSeeder` | Same two-gun placement, derived from the grid size. |
| `model.Generation` | `model::Generation` | Same flat `bool` buffers (`Box<[bool]>`), active region, fused population count, monotonic ever-alive flags. `withCell`/`withPattern` semantics match, including "removing a cell forgets its trail". |
| `model.Pattern`/`PatternLoader` | `model::Pattern`/`PatternLoader` | `.cells` parser ports directly; the Gosper gun is embedded with `include_str!`. |
| `config.LifeConfig`, `BoardSizeOption` | `config::LifeConfig`, `config::BoardSizeOption` | System properties `-Dconway.*` become `CONWAY_*` environment variables; same defaults, ranges and clamping. |
| `ui.LifeFrame` | `ui::app::LifeApp` | egui panels replace Swing layout; status bar, sidebar and enabled/disabled rules are direct ports. |
| `ui.BoardView` + `CellGeometry` | `ui::board_render` + `ui::cell_geometry` | The `BufferedImage`/`int[]` pixel buffer becomes a `Vec<Color32>` + egui texture (one texel per cell, NEAREST upscale). Diff-rendering (only rewritten cells) is kept. Clicks map through the drawn square. |
| `ui.Theme` | `ui::theme::Theme` | The exact palette constants; button fills/typography are applied to egui's dark visuals. |

## Deliberate differences

* **Synchronisation.** The Javadoc's "single volatile publish" becomes a
  `Mutex<EngineSnapshot>`. The lock is held only to clone an `Arc` — never for
  rendering or command processing — and the rendered data stays immutable.
* **No listeners.** The Swing frame registered engine callbacks; the egui app
  polls `snapshot()` and `take_errors()` once per frame instead, which is
  simpler and matches immediate-mode rendering.
* **Partition semantics.** `set_partitions` still clamps to `[1, max]`, and
  `LifeRules::bands_for` still caps bands by ~50 active rows per band, so a
  sparse board is never split.
* **Failure handling.** A generation panic (none are expected) is caught with
  `catch_unwind`, the engine enters `Failed`, and the GUI shows an error
  dialog — the Rust analogue of `SimulationException`.
* **Benchmark randomness.** The tests use a small xorshift PRNG, so boards are
  reproducible run-to-run but not bit-identical to Java's `java.util.Random`.

The Java test files under `src/test/java` were ported to `#[cfg(test)]` modules
next to their code; the load-bearing `PartitionEquivalenceTest` survives as
`engine::partition_equivalence_tests`.