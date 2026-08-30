//! Headless throughput comparison across partition counts.
//!
//! The application's premise is that splitting a generation across threads is
//! worthwhile. That claim needs evidence, and this benchmark is it — a direct
//! port of the Java `GenerationBenchmark`.
//!
//! Run from the crate root:
//!
//! ```bash
//! cargo run --release --bin bench -- [gridSize] [generations]
//! ```
//!
//! Arguments are optional: grid size (default 201) and generations per
//! measurement (default 300).

use std::time::Instant;

use conway_life::engine::LifeRules;
use conway_life::model::{Generation, PatternLoader};

const DEFAULT_GRID_SIZE: usize = 201;
const DEFAULT_GENERATIONS: usize = 300;
const WARMUP_GENERATIONS: usize = 400;
const REPETITIONS: usize = 5;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let grid_size = args
        .get(1)
        .map(|s| s.parse().expect("grid size must be an integer"))
        .unwrap_or(DEFAULT_GRID_SIZE);
    let generations = args
        .get(2)
        .map(|s| s.parse().expect("generation count must be an integer"))
        .unwrap_or(DEFAULT_GENERATIONS);

    let cores = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1);
    let max_partitions = grid_size.min(cores * 2);
    println!(
        "Board {grid_size}x{grid_size}, {generations} generations per measurement, best of {REPETITIONS}, {cores} available processors"
    );

    let seed = seeded_board(grid_size);
    let rules = LifeRules;

    // Warm up so the first measurement is not skewed by lazy one-time setup
    // (font caches, page faults, rayon's thread pool).
    let mut warm = seed.clone();
    for _ in 0..WARMUP_GENERATIONS {
        warm = rules.next(&warm, max_partitions);
    }

    println!("\npartition(s) | generations/s");
    println!("------------|--------------");
    for partitions in candidate_partitions(max_partitions) {
        let mut best = f64::INFINITY;
        for _ in 0..REPETITIONS {
            // Reset to an identical board for every measurement.
            let mut board = seed.clone();
            let start = Instant::now();
            for _ in 0..generations {
                board = rules.next(&board, partitions);
            }
            best = best.min(start.elapsed().as_secs_f64());
        }
        let rate = generations as f64 / best;
        println!("{partitions:>11} | {rate:>12.1}");
    }
}

/// A board worth measuring: the two-gun seeded start-up layout, built directly
/// (same placement the `SimulationSeeder` performs through an engine).
fn seeded_board(grid_size: usize) -> Generation {
    let gun = PatternLoader::gosper_glider_gun().expect("bundled gun must parse");
    let rotated = gun.rotate180();
    Generation::empty(grid_size, grid_size)
        .with_pattern(&gun, 1, 1)
        .with_pattern(
            &rotated,
            grid_size as i32 - gun.height() as i32 - 1,
            grid_size as i32 - gun.width() as i32 - 1,
        )
}

/// Partition counts worth comparing: 1, 2, 4, ... up to the hardware cap.
fn candidate_partitions(max_partitions: usize) -> Vec<usize> {
    let mut counts = vec![1usize];
    let mut next = 2usize;
    while next <= max_partitions {
        counts.push(next);
        next *= 2;
    }
    if *counts.last().unwrap() != max_partitions {
        counts.push(max_partitions);
    }
    counts
}