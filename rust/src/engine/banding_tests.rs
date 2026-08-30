use crate::engine::life_rules::LifeRules;
use crate::model::{ActiveRegion, Generation};

/// Spans `rows` rows starting at the top; columns are irrelevant to banding.
fn spanning(rows: usize) -> ActiveRegion {
    ActiveRegion::new(0, rows as i32 - 1, 0, 10)
}

#[test]
fn bands_are_capped_by_active_rows() {
    // Too little work to be worth splitting: run inline.
    assert_eq!(LifeRules::bands_for(4, 1000, spanning(1)), 1);
    assert_eq!(LifeRules::bands_for(16, 1000, spanning(5)), 1);
    assert_eq!(LifeRules::bands_for(16, 1000, spanning(49)), 1);
    // Exactly enough for a second band at 50 rows each.
    assert_eq!(LifeRules::bands_for(16, 1000, spanning(100)), 2);
    assert_eq!(LifeRules::bands_for(16, 1000, spanning(200)), 4);
    assert_eq!(LifeRules::bands_for(16, 1000, spanning(800)), 16);
    // Never more bands than were requested.
    assert_eq!(LifeRules::bands_for(4, 1000, spanning(800)), 4);
    assert_eq!(LifeRules::bands_for(1, 1000, spanning(800)), 1);
}

#[test]
fn sparse_board_runs_inline() {
    // The regression case: the partition count is sized for an
    // 801-row board, but a blinker occupies five rows. Sixteen bands there ran
    // 5.4x slower than inline.
    assert_eq!(LifeRules::bands_for(16, 801, spanning(5)), 1);
}

#[test]
fn dense_board_uses_every_requested_band() {
    assert_eq!(LifeRules::bands_for(16, 801, spanning(801)), 16);
    assert_eq!(LifeRules::bands_for(4, 201, spanning(201)), 4);
}

#[test]
fn degenerate_inputs_yield_one_band() {
    assert_eq!(LifeRules::bands_for(16, 801, ActiveRegion::EMPTY), 1);
    // Zero partitions clamp to a single band; negative counts are unreachable
    // for a `usize`, and `next` applies the same clamp.)
    assert_eq!(LifeRules::bands_for(0, 801, spanning(800)), 1);
}

#[test]
fn bands_stay_within_bounds() {
    for height in 1..=200usize {
        for requested in [1usize, 2, 7, 64] {
            let bands = LifeRules::bands_for(requested, height, spanning(height.max(1)));
            assert!(bands >= 1, "at least one band");
            assert!(bands <= requested, "never more than requested");
            assert!(bands <= height, "never more bands than rows");
        }
    }
}

#[test]
fn empty_board_advances_to_a_new_generation() {
    let rules = LifeRules;
    let empty = Generation::empty(10, 10);
    let next = rules.next(&empty, 4);
    assert_eq!(next.number(), 1);
    assert_eq!(next.population(), 0);
    assert!(next.active_region().is_empty());
}