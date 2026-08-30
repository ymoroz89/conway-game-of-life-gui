//! Conway's Game of Life — a Rust rework of the Java/Swing implementation that
//! previously lived in this repository.
//!
//! The design mirrors the original:
//!
//! * a **single-writer actor** (`engine::SimulationEngine`) owns the board and
//!   processes every mutation as a queued command, so no two threads ever touch
//!   simulation state;
//! * the actor publishes an immutable [`engine::EngineSnapshot`] (board + the
//!   statistics that belong to it) behind a lock, which is what lets the UI
//!   render at its own pace while the simulation runs;
//! * generations are computed across parallel row bands by
//!   [`engine::LifeRules`] using rayon, with the same ~50-rows-per-band
//!   work rule the Java `PartitionPlanner` was calibrated on.

pub mod config;
pub mod engine;
pub mod model;
pub mod ui;

#[cfg(test)]
pub(crate) mod test_util;