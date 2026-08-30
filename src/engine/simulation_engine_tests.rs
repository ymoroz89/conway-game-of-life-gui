use std::sync::Arc;
use std::time::Duration;

use crate::engine::simulation_engine::{SimulationEngine, State};
use crate::engine::simulation_stats::SimulationStats;
use crate::model::Pattern;
use crate::test_util::await_cond;

const SIZE: usize = 32;
/// Tall enough for the engine to actually use several bands: it allows one band
/// per 50 active rows, so e.g. eight bands need at least 400.
const BANDED_SIZE: usize = 420;
const TIMEOUT: Duration = Duration::from_secs(10);

fn blinker() -> Arc<Pattern> {
    Arc::new(Pattern::parse("blinker", &["OOO"]).expect("blinker must parse"))
}

fn new_engine() -> SimulationEngine {
    SimulationEngine::new(SIZE, SIZE, 4)
}

#[test]
fn a_step_advances_exactly_one_generation() {
    let engine = new_engine();
    assert_eq!(engine.snapshot().generation().number(), 0);

    engine.step();
    assert!(engine.sync(TIMEOUT));

    assert_eq!(engine.snapshot().generation().number(), 1);
    engine.close();
}

#[test]
fn queued_step_requests_are_never_dropped() {
    // Commands are queued rather than signalled, so every request must be
    // applied exactly once.
    let engine = new_engine();
    let steps = 200;
    for _ in 0..steps {
        engine.step();
    }
    assert!(engine.sync(Duration::from_secs(20)));

    assert_eq!(engine.snapshot().generation().number(), steps as u64);
    engine.close();
}

#[test]
fn start_advances_continuously_and_pause_stops_it() {
    let engine = new_engine();
    engine.place(blinker(), 4, 4);
    engine.start();

    await_cond(|| engine.snapshot().generation().number() > 25, TIMEOUT);
    assert_eq!(engine.state(), State::Running);

    engine.pause();
    assert!(engine.sync(TIMEOUT));
    let settled = engine.snapshot().generation().number();

    std::thread::sleep(Duration::from_millis(200));
    assert_eq!(
        engine.snapshot().generation().number(),
        settled,
        "the board advanced after the engine was paused"
    );
    assert_eq!(engine.state(), State::Paused);
    engine.close();
}

#[test]
fn throughput_statistics_are_recorded_for_a_run() {
    let engine = new_engine();
    engine.place(blinker(), 4, 4);
    engine.start();
    await_cond(
        || engine.snapshot().stats().generations_in_run > 50,
        TIMEOUT,
    );
    engine.pause();
    assert!(engine.sync(TIMEOUT));

    let stats: SimulationStats = engine.snapshot().stats();
    assert!(stats.elapsed_nanos > 0, "elapsed time was not measured");
    assert!(
        stats.generations_per_second() > 0.0,
        "rate should be positive but was {}",
        stats.generations_per_second()
    );
    engine.close();
}

#[test]
fn toggling_a_cell_flips_it_and_is_idempotent_in_pairs() {
    let engine = new_engine();
    engine.toggle_cell(3, 7);
    assert!(engine.sync(TIMEOUT));
    assert!(engine.snapshot().generation().is_alive(3, 7));
    assert!(engine.snapshot().generation().was_ever_alive(3, 7));

    engine.toggle_cell(3, 7);
    assert!(engine.sync(TIMEOUT));
    assert!(!engine.snapshot().generation().is_alive(3, 7));
    assert!(
        !engine.snapshot().generation().was_ever_alive(3, 7),
        "removing a cell should return it to unpopulated, not recently dead"
    );
    engine.close();
}

#[test]
fn out_of_bounds_toggles_are_ignored_rather_than_throwing() {
    let engine = new_engine();
    engine.toggle_cell(-1, 0);
    engine.toggle_cell(0, SIZE as i32);
    engine.toggle_cell(SIZE as i32, SIZE as i32);
    assert!(engine.sync(TIMEOUT));

    assert_eq!(engine.snapshot().generation().population(), 0);
    assert_ne!(engine.state(), State::Failed);
    engine.close();
}

#[test]
fn clear_empties_the_board_and_resets_the_generation_counter() {
    let engine = new_engine();
    engine.place(blinker(), 4, 4);
    engine.step();
    engine.step();
    assert!(engine.sync(TIMEOUT));
    assert!(engine.snapshot().generation().population() > 0);

    engine.clear();
    assert!(engine.sync(TIMEOUT));

    let cleared = engine.snapshot().generation_arc().clone();
    assert_eq!(cleared.population(), 0);
    assert_eq!(cleared.number(), 0);
    engine.close();
}

#[test]
fn a_placed_pattern_larger_than_the_board_is_clipped_not_rejected() {
    let small = SimulationEngine::new(4, 4, 2);
    small.place(blinker(), 3, 3);
    assert!(small.sync(TIMEOUT));
    assert_ne!(small.state(), State::Failed);
    assert_eq!(small.snapshot().generation().population(), 1);
    small.close();
}

#[test]
fn changing_the_partition_count_does_not_disturb_the_board() {
    let engine = new_engine();
    engine.place(blinker(), 4, 4);
    engine.set_partitions(4);
    engine.step();
    assert!(engine.sync(TIMEOUT));
    let with_four = engine.snapshot().generation_arc().clone();

    engine.clear();
    engine.place(blinker(), 4, 4);
    engine.set_partitions(1);
    engine.step();
    assert!(engine.sync(TIMEOUT));

    assert_eq!(
        with_four,
        engine.snapshot().generation_arc().clone(),
        "the board changed because the partition count did"
    );
    engine.close();
}

#[test]
fn the_partition_count_is_clamped_to_the_configured_maximum() {
    let engine = new_engine();
    engine.set_partitions(10);
    assert!(engine.sync(TIMEOUT));

    assert_eq!(engine.snapshot().stats().partitions, 4);
    engine.close();
}

#[test]
fn concurrent_edits_during_a_run_are_benign() {
    // Hammer toggles from several threads while the simulation runs; the
    // single-writer actor must keep every reader consistent.
    let engine = new_engine();
    engine.place(blinker(), 4, 4);
    engine.start();
    await_cond(|| engine.snapshot().generation().number() > 5, TIMEOUT);

    let threads = 4usize;
    let edits_per_thread = 250usize;
    std::thread::scope(|scope| {
        let mut handles = Vec::new();
        for t in 0..threads {
            let engine = &engine;
            handles.push(scope.spawn(move || {
                for i in 0..edits_per_thread {
                    engine.toggle_cell(
                        ((t * 7 + i) % SIZE) as i32,
                        ((t * 5 + i) % SIZE) as i32,
                    );
                    let snapshot = engine.snapshot();
                    assert_eq!(
                        snapshot.generation().width(),
                        SIZE,
                        "observed a malformed snapshot"
                    );
                }
            }));
        }
        for handle in handles {
            handle.join().expect("editor thread panicked");
        }
    });

    engine.pause();
    assert!(engine.sync(TIMEOUT));
    assert_ne!(engine.state(), State::Failed);
    engine.close();
}

#[test]
fn snapshots_are_immutable_so_a_held_reference_never_changes() {
    let engine = new_engine();
    engine.place(blinker(), 4, 4);
    assert!(engine.sync(TIMEOUT));

    let held = engine.snapshot();
    let population = held.generation().population();
    let number = held.generation().number();

    engine.step();
    engine.step();
    assert!(engine.sync(TIMEOUT));

    assert_eq!(held.generation().population(), population);
    assert_eq!(held.generation().number(), number);
    assert_ne!(engine.snapshot().generation().number(), number);
    engine.close();
}

#[test]
fn close_stops_the_simulation_thread_and_is_idempotent() {
    let engine = new_engine();
    engine.place(blinker(), 4, 4);
    engine.start();
    await_cond(|| engine.snapshot().generation().number() > 5, TIMEOUT);

    engine.close();
    engine.close();

    assert_eq!(engine.state(), State::Stopped);
    assert!(
        !engine.is_simulation_running(),
        "the simulation thread outlived close()"
    );
}

#[test]
fn commands_issued_after_close_are_ignored() {
    let engine = new_engine();
    engine.close();
    let before = engine.snapshot().generation_arc().clone();

    engine.step();
    engine.start();
    engine.toggle_cell(1, 1);

    assert!(
        !engine.sync(Duration::from_secs(1)),
        "sync should not succeed after close"
    );
    assert_eq!(engine.snapshot().generation_arc().clone(), before);
}

#[test]
fn partition_counts_are_honoured_on_a_banded_board() {
    let engine = SimulationEngine::new(BANDED_SIZE, BANDED_SIZE, 16);
    spanning_blocks(&engine, BANDED_SIZE);

    for partitions in [1usize, 2, 8, 16] {
        engine.set_partitions(partitions);
        for _ in 0..20 {
            engine.step();
        }
        assert!(engine.sync(TIMEOUT));
        assert_eq!(
            engine.snapshot().stats().partitions,
            partitions,
            "stats should report the requested partition count"
        );
        assert_ne!(engine.state(), State::Failed);
    }
    engine.close();
}

/// Two still lifes at opposite ends, so the active region's bounding box spans
/// the whole board permanently.
fn spanning_blocks(engine: &SimulationEngine, size: usize) {
    for row in [1usize, size - 3] {
        engine.toggle_cell(row as i32, 1);
        engine.toggle_cell(row as i32, 2);
        engine.toggle_cell(row as i32 + 1, 1);
        engine.toggle_cell(row as i32 + 1, 2);
    }
}