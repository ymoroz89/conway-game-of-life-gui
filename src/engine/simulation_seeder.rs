use std::sync::Arc;
use std::time::Duration;

use crate::engine::simulation_engine::SimulationEngine;
use crate::model::PatternLoader;

/// Convenience for setting up the initial board state.
pub struct SimulationSeeder;

impl SimulationSeeder {
    /// Places two Gosper glider guns, the second rotated by 180 degrees in the
    /// opposite corner, reproducing the historical starting board. Positions
    /// are derived from the grid size rather than hard-coded, and
    /// `Generation::with_pattern` clips, so this is safe for any grid.
    pub fn seed_board(engine: &SimulationEngine, grid_size: usize) {
        let gun = PatternLoader::gosper_glider_gun()
            .expect("the bundled Gosper glider gun must parse");
        engine.place(Arc::new(gun.clone()), 1, 1);
        engine.place(
            Arc::new(gun.rotate180()),
            grid_size as i32 - gun.height() as i32 - 1,
            grid_size as i32 - gun.width() as i32 - 1,
        );

        // Ensures the first frame already shows the seeded board.
        if !engine.sync(Duration::from_secs(5)) {
            eprintln!("conway-life: timed out waiting for the initial board to be seeded");
        }
    }
}