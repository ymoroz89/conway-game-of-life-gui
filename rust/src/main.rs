//! Application entry point: builds the engine, seeds the board and shows the
//! egui window. Mirrors the Java `Main` + `LifeGuiLauncher`.

use conway_life::config::LifeConfig;
use conway_life::engine::{SimulationEngine, SimulationSeeder};
use conway_life::ui::LifeApp;

fn main() -> eframe::Result<()> {
    let config = LifeConfig::from_env();
    eprintln!(
        "conway-life: board {}x{}, cell {} px, target {} fps, partitions {} of at most {}",
        config.grid_size(),
        config.grid_size(),
        config.cell_size_px(),
        config.target_fps(),
        config.default_partitions(),
        config.max_partitions()
    );

    let engine = SimulationEngine::new(config.grid_size(), config.grid_size(), config.max_partitions());
    // The engine starts single-threaded; adopt the count chosen for this board
    // and machine before the first generation is computed.
    engine.set_partitions(config.default_partitions());
    SimulationSeeder::seed_board(&engine, config.grid_size());

    let native_options = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_title("Game of Life by Conway")
            .with_inner_size([1280.0, 800.0])
            .with_min_inner_size([640.0, 480.0]),
        ..Default::default()
    };

    eframe::run_native(
        "conway-life",
        native_options,
        Box::new(move |cc| Ok(Box::new(LifeApp::new(cc, config, engine)))),
    )
}