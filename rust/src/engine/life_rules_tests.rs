use crate::engine::life_rules::LifeRules;
use crate::model::{Generation, Pattern};
use crate::test_util::{board, random, render};

#[test]
fn ever_alive_flags_survive_the_board_dying_out() {
    // A lone cell dies at once, leaving an empty board and an empty active
    // region. The flags are monotonic within a run and are what the renderer
    // draws as "died here", so an empty board must not erase them.
    let rules = LifeRules;
    let mut board = Generation::empty(8, 8).with_cell(3, 3, true);

    for _ in 0..3 {
        board = rules.next(&board, 1);
    }

    assert_eq!(board.population(), 0, "the cell should have died of underpopulation");
    assert!(board.was_ever_alive(3, 3), "its trail must outlive it");
}

#[test]
fn ever_alive_flags_accumulate_across_generations() {
    // A glider moves, so cells it has left must stay marked while new ones are
    // added.
    let rules = LifeRules;
    let seed = board(&[
        ".O........",
        "..O.......",
        "OOO.......",
        "..........",
        "..........",
        "..........",
    ]);

    let mut later = seed.clone();
    for _ in 0..4 {
        later = rules.next(&later, 2);
    }

    assert!(later.was_ever_alive(2, 0), "a cell the glider has vacated stays marked");
    assert!(later.population() > 0, "the glider is still alive");
    for row in 0..later.height() {
        for col in 0..later.width() {
            if seed.was_ever_alive(row, col) {
                assert!(
                    later.was_ever_alive(row, col),
                    "flag lost at ({row}, {col})"
                );
            }
        }
    }
}

#[test]
fn blinker_oscillates_between_orientations_and_leaves_trails() {
    let rules = LifeRules;
    let seed = board(&[
        "..........",
        "..........",
        "..........",
        ".....O....",
        ".....O....",
        ".....O....",
        "..........",
    ]);

    let horizontal = rules.next(&seed, 1);
    assert_eq!(
        render(&horizontal),
        [
            "..........",
            "..........",
            "..........",
            "..........",
            "....OOO...",
            "..........",
            "..........",
        ]
    );
    assert!(horizontal.is_alive(4, 4));
    assert!(horizontal.is_alive(4, 5));
    assert!(horizontal.is_alive(4, 6));
    assert!(!horizontal.is_alive(3, 5));
    assert!(!horizontal.is_alive(5, 5));

    // Ever-alive flags: old vertical cells and the new horizontal ones.
    assert!(horizontal.was_ever_alive(3, 5));
    assert!(horizontal.was_ever_alive(4, 4));
    assert!(horizontal.was_ever_alive(5, 5));

    // Oscillation returns to the vertical orientation.
    let vertical_again = rules.next(&horizontal, 1);
    assert_eq!(render(&vertical_again), render(&seed));
}

#[test]
fn a_blinker_on_a_large_board_oscillates() {
    // A blinker stamped into a 50x50 board; check the
    // relevant cells of the first generation.
    let rules = LifeRules;
    let mut gen0 = Generation::empty(50, 50)
        .with_cell(10, 10, true)
        .with_cell(11, 10, true)
        .with_cell(12, 10, true);

    let gen1 = rules.next(&gen0, 1);

    assert!(gen1.is_alive(11, 9));
    assert!(gen1.is_alive(11, 10));
    assert!(gen1.is_alive(11, 11));
    assert!(!gen1.is_alive(10, 10));
    assert!(!gen1.is_alive(12, 10));

    assert!(gen1.was_ever_alive(10, 10));
    assert!(gen1.was_ever_alive(11, 9));
    assert!(gen1.was_ever_alive(12, 10));

    // And oscillation continues back to the vertical orientation.
    gen0 = gen1;
    let gen2 = rules.next(&gen0, 1);
    assert!(!gen2.is_alive(11, 9));
    assert!(!gen2.is_alive(11, 11));
    assert!(gen2.is_alive(10, 10));
    assert!(gen2.is_alive(12, 10));
}

#[test]
fn a_block_is_a_still_life() {
    let rules = LifeRules;
    let block = board(&["OO", "OO"]);
    let next = rules.next(&block, 8);

    assert_eq!(render(&next), ["OO", "OO"]);
    assert_eq!(next.population(), 4);
}

#[test]
fn a_single_cell_dies_immediately() {
    let rules = LifeRules;
    let loner = Generation::empty(5, 5).with_cell(2, 2, true);
    let next = rules.next(&loner, 4);

    assert_eq!(next.population(), 0);
}

#[test]
fn population_matches_render() {
    let rules = LifeRules;
    let mut current = random(27, 31, 7, 0.35);
    for generation in 0..8 {
        current = rules.next(&current, 3);
        let rendered = render(&current);
        let counted = rendered
            .iter()
            .flat_map(|row| row.chars())
            .filter(|&ch| ch == 'O')
            .count();
        assert_eq!(
            counted,
            current.population(),
            "population drifted from the board at generation {generation}"
        );
    }
}

#[test]
fn a_glider_moves_diagonally() {
    let rules = LifeRules;
    let glider = Pattern::parse("glider", &[".O.", "..O", "OOO"]).unwrap();
    let current = Generation::empty(6, 6).with_pattern(&glider, 0, 0);

    // Classic first phase: the glider's head curls down-right and (2,2)
    // survives, so generation 1 has five cells just east of the origin.
    let after = rules.next(&current, 1);
    assert_eq!(
        render(&after),
        ["......", "O.O...", ".OO...", ".O....", "......", "......"]
    );

    // After four generations the glider returns to its original phase,
    // translated one cell diagonally (south-east).
    let mut generation = after;
    for _ in 0..3 {
        generation = rules.next(&generation, 1);
    }
    assert_eq!(
        render(&generation),
        ["......", "..O...", "...O..", ".OOO..", "......", "......"]
    );
}

#[test]
fn a_glider_never_dies_and_moves_out_of_its_own_trail() {
    let rules = LifeRules;
    let glider = Pattern::parse("glider", &[".O.", "..O", "OOO"]).unwrap();
    let mut generation = Generation::empty(16, 16).with_pattern(&glider, 0, 0);

    for _ in 0..32 {
        let next = rules.next(&generation, 1);
        assert!(next.population() > 0, "glider died at generation {}", next.number());
        generation = next;
    }
    // It must have travelled clear of its starting column.
    assert!(
        !generation.alive_slice()[0..16].iter().any(|&alive| alive),
        "glider should have left the top row"
    );
}