use std::time::Duration;

use crate::engine::simulation_engine::SimulationEngine;
use crate::engine::simulation_seeder::SimulationSeeder;

const DEFAULT_GRID: usize = 201;

#[test]
fn seeds_two_guns_on_the_default_grid() {
    let engine = SimulationEngine::new(DEFAULT_GRID, DEFAULT_GRID, 2);
    SimulationSeeder::seed_board(&engine, DEFAULT_GRID);
    assert!(engine.sync(Duration::from_secs(10)));

    let board = engine.snapshot().generation_arc().clone();
    // Two Gosper glider guns, 36 live cells each, no overlap.
    assert_eq!(board.population(), 72);

    // Top-left gun: the pair at the pattern's rows 4-5, columns 0-1, offset by (1, 1).
    assert!(board.is_alive(5, 1));
    assert!(board.is_alive(5, 2));
    assert!(board.is_alive(6, 1));
    assert!(board.is_alive(6, 2));

    // Bottom-right gun, rotated 180 degrees into the opposite corner.
    assert!(board.is_alive(195, 199));
    assert!(board.is_alive(195, 198));
    assert!(board.is_alive(194, 199));
    assert!(board.is_alive(194, 198));

    engine.close();
}

#[test]
fn seeded_board_runs_and_grows() {
    let engine = SimulationEngine::new(DEFAULT_GRID, DEFAULT_GRID, 4);
    SimulationSeeder::seed_board(&engine, DEFAULT_GRID);
    engine.set_partitions(4);
    assert!(engine.sync(Duration::from_secs(10)));

    let initial = engine.snapshot().generation().population();

    // A glider gun emits one glider every 30 generations, so after 60 the
    // population must have grown. This exercises the full 201x201 board
    // through the parallel path end to end.
    for _ in 0..60 {
        engine.step();
    }
    assert!(engine.sync(Duration::from_secs(30)));

    let board = engine.snapshot().generation_arc().clone();
    assert_eq!(board.number(), 60);
    assert!(
        board.population() > initial,
        "expected the guns to emit gliders, but population went from {initial} to {}",
        board.population()
    );
    assert_ne!(engine.state(), crate::engine::State::Failed);

    engine.close();
}

#[test]
fn small_grid_is_seeded_without_failing() {
    let engine = SimulationEngine::new(12, 12, 2);
    SimulationSeeder::seed_board(&engine, 12);
    assert!(engine.sync(Duration::from_secs(10)));

    // Clipped rather than thrown: the engine is still usable.
    assert_ne!(engine.state(), crate::engine::State::Failed);
    engine.step();
    assert!(engine.sync(Duration::from_secs(10)));
    assert_eq!(engine.snapshot().generation().number(), 1);

    engine.close();
}