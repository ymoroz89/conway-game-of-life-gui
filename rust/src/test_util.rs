use std::time::{Duration, Instant};

use crate::model::{Generation, Pattern};

/// Builds a board from plaintext rows, where `O` is alive and `.` is dead.
/// All rows must be the same length.
pub(crate) fn board(rows: &[&str]) -> Generation {
    let pattern = Pattern::parse("test-board", rows).expect("test board must parse");
    Generation::empty(pattern.width(), pattern.height()).with_pattern(&pattern, 0, 0)
}

/// Renders the alive cells of a board back to the same plaintext form.
pub(crate) fn render(generation: &Generation) -> Vec<String> {
    (0..generation.height())
        .map(|row| {
            (0..generation.width())
                .map(|col| if generation.is_alive(row, col) { 'O' } else { '.' })
                .collect()
        })
        .collect()
}

/// Builds a reproducible pseudo-random board with the given live-cell
/// probability, using a small deterministic xorshift PRNG so the arrangement
/// is identical across runs (not identical to the Java `java.util.Random`
/// arrangement, merely reproducible).
pub(crate) fn random(width: usize, height: usize, seed: u64, density: f64) -> Generation {
    let mut rng = Rng::new(seed);
    let mut generation = Generation::empty(width, height);
    for row in 0..height {
        for col in 0..width {
            if rng.next_f64() < density {
                generation = generation.with_cell(row, col, true);
            }
        }
    }
    generation
}

/// Polls `condition` until it is true or `timeout` elapses, panicking on
/// timeout.
pub(crate) fn await_cond(condition: impl Fn() -> bool, timeout: Duration) {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if condition() {
            return;
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    panic!("condition was not met within {timeout:?}");
}

struct Rng {
    state: u64,
}

impl Rng {
    fn new(seed: u64) -> Rng {
        Rng {
            state: seed.wrapping_mul(0x9E37_79B9_7F4A_7C15).wrapping_add(1),
        }
    }

    fn next_u64(&mut self) -> u64 {
        let mut x = self.state;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.state = x;
        x
    }

    fn next_f64(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 / (1u64 << 53) as f64
    }
}