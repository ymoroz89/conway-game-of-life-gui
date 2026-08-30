//! Conway's Game of Life — a multithreaded simulation with an egui GUI.
//!
//! The design:
//!
//! * a **single-writer actor** (`engine::SimulationEngine`) owns the board and
//!   processes every mutation as a queued command, so no two threads ever touch
//!   simulation state;
//! * the actor publishes an immutable [`engine::EngineSnapshot`] (board + the
//!   statistics that belong to it) behind a lock, which is what lets the UI
//!   render at its own pace while the simulation runs;
//! * generations are computed across parallel row bands by
//!   [`engine::LifeRules`] using rayon, with the ~50-rows-per-band
//!   work rule the partition planner was calibrated on.

pub mod config;
pub mod engine;
pub mod model;
pub mod ui;

#[cfg(test)]
pub(crate) mod test_util;