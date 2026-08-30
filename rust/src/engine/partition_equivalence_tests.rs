use crate::engine::life_rules::LifeRules;
use crate::model::Generation;
use crate::test_util::{random, render};

/// The central safety net for the parallel rules engine.
///
/// Partitioning is purely a work-distribution concern: the board that comes
/// out must not depend on how many bands the work was split into. Asserting
/// that property directly catches off-by-one errors at band boundaries, rows
/// that are skipped or computed twice, and any accidental sharing of mutable
/// state between workers.
const BOARD_SIZE: usize = 37;
const GENERATIONS: usize = 12;
const SEED: u64 = 20_260_728;

#[test]
fn partitioning_does_not_change_the_result() {
    let rules = LifeRules;
    let expected = single_threaded_run(&rules);

    for partitions in [2usize, 3, 4, 5, 7, 8, 16, 36, 37] {
        let mut actual = seed();
        for (generation, expected_generation) in expected.iter().enumerate() {
            actual = rules.next(&actual, partitions);
            assert_eq!(
                expected_generation, &actual,
                "diverged from the single-threaded result at generation {} with {} partitions",
                generation + 1,
                partitions
            );
        }
    }
}

#[test]
fn requesting_more_partitions_than_rows_is_clamped_not_broken() {
    let rules = LifeRules;
    let expected = single_threaded_run(&rules);

    let mut actual = seed();
    for (generation, expected_generation) in expected.iter().enumerate() {
        actual = rules.next(&actual, BOARD_SIZE * 10);
        assert_eq!(
            expected_generation, &actual,
            "oversized partition count diverged at generation {generation}"
        );
    }
}

#[test]
fn non_positive_partition_counts_are_clamped() {
    let rules = LifeRules;
    let start = seed();
    let baseline = rules.next(&start, 1);

    assert_eq!(baseline, rules.next(&start, 0));
}

#[test]
fn non_square_boards_partition_correctly() {
    let rules = LifeRules;
    // 23 wide x 41 tall, so the wide-interior path is exercised across bands.
    let start = random(23, 41, SEED, 0.35);

    let mut expected = start.clone();
    let mut actual = start;
    for generation in 0..GENERATIONS {
        expected = rules.next(&expected, 1);
        actual = rules.next(&actual, 6);
        assert_eq!(
            render(&expected),
            render(&actual),
            "non-square board diverged at generation {generation}"
        );
    }
}

fn seed() -> Generation {
    random(BOARD_SIZE, BOARD_SIZE, SEED, 0.35)
}

fn single_threaded_run(rules: &LifeRules) -> Vec<Generation> {
    let mut board = seed();
    (0..GENERATIONS)
        .map(|_| {
            board = rules.next(&board, 1);
            board.clone()
        })
        .collect()
}