use super::active_region::ActiveRegion;
use super::pattern::Pattern;

/// An immutable snapshot of the board at a single point in the simulation.
///
/// Cells carry two independent flags:
///
/// * `alive` — participates in the Game of Life rules.
/// * `ever_alive` — purely presentational; it records whether the cell has
///   ever been alive so the renderer can distinguish "died here" from "never
///   populated". It is monotonic within a run and is reset only by a clear.
///
/// Both are stored as flat `bool` slices indexed `row * width + col` rather
/// than as nested vectors, which gives the rules engine sequential memory
/// access.
///
/// The population is maintained as a field rather than recomputed on demand:
/// the status bar reads it at 60 fps, and the rules engine folds the count
/// into a pass it already makes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Generation {
    width: usize,
    height: usize,
    alive: Box<[bool]>,
    ever_alive: Box<[bool]>,
    number: u64,
    active_region: ActiveRegion,
    population: usize,
}

impl Generation {
    /// Creates an empty generation numbered zero.
    ///
    /// # Panics
    ///
    /// Panics unless both dimensions are positive.
    pub fn empty(width: usize, height: usize) -> Generation {
        assert!(
            width > 0 && height > 0,
            "Board dimensions must be positive but were {width}x{height}"
        );
        let cells = width
            .checked_mul(height)
            .expect("board too large to address");
        Generation {
            width,
            height,
            alive: vec![false; cells].into_boxed_slice(),
            ever_alive: vec![false; cells].into_boxed_slice(),
            number: 0,
            active_region: ActiveRegion::EMPTY,
            population: 0,
        }
    }

    /// Wraps caller-supplied storage and computes the active region and
    /// population by scanning.
    ///
    /// # Panics
    ///
    /// Panics if either buffer does not match `width * height`.
    pub fn adopt(
        width: usize,
        height: usize,
        alive: Vec<bool>,
        ever_alive: Vec<bool>,
        number: u64,
    ) -> Generation {
        let expected = width
            .checked_mul(height)
            .expect("board too large to address");
        assert!(
            alive.len() == expected && ever_alive.len() == expected,
            "board buffers must contain {width}x{height} = {expected} cells"
        );
        Generation {
            width,
            height,
            population: count_alive(&alive),
            active_region: compute_active_region(width, height, &alive),
            alive: alive.into_boxed_slice(),
            ever_alive: ever_alive.into_boxed_slice(),
            number,
        }
    }

    /// Wraps caller-supplied storage with an already-computed region and
    /// population. This is the hot path: [`crate::engine::LifeRules`] sums the
    /// band results and knows both without scanning a second time.
    pub(crate) fn adopt_fast(
        width: usize,
        height: usize,
        alive: Vec<bool>,
        ever_alive: Vec<bool>,
        number: u64,
        active_region: ActiveRegion,
        population: usize,
    ) -> Generation {
        let expected = width
            .checked_mul(height)
            .expect("board too large to address");
        debug_assert_eq!(alive.len(), expected);
        debug_assert_eq!(ever_alive.len(), expected);
        Generation {
            width,
            height,
            alive: alive.into_boxed_slice(),
            ever_alive: ever_alive.into_boxed_slice(),
            number,
            active_region,
            population,
        }
    }

/// A copy of this generation with a single cell forced to the given state.
    ///
    /// Used for interactive editing. Turning a cell on marks it ever-alive;
    /// turning it off clears that flag too, so a cell the user removes reverts
    /// to unpopulated rather than rendering as recently dead.
    ///
    /// # Panics
    ///
    /// Panics if the coordinates are outside the board.
    pub fn with_cell(&self, row: usize, col: usize, live: bool) -> Generation {
        self.check_bounds(row, col);
        let mut new_alive = self.alive.to_vec();
        let mut new_ever = self.ever_alive.to_vec();
        let index = row * self.width + col;
        new_alive[index] = live;
        new_ever[index] = live;
        // Not the hot path: recompute both active region and population.
        Generation::adopt(self.width, self.height, new_alive, new_ever, self.number)
    }

    /// A copy of this generation with `pattern` stamped in with its top-left
    /// corner at `(row, col)`. Cells that fall outside the board are clipped
    /// rather than rejected, so a pattern larger than the grid degrades
    /// gracefully.
    pub fn with_pattern(&self, pattern: &Pattern, row: i32, col: i32) -> Generation {
        let mut new_alive = self.alive.to_vec();
        let mut new_ever = self.ever_alive.to_vec();
        for r in 0..pattern.height() {
            let board_row = row + r as i32;
            if board_row < 0 || board_row as usize >= self.height {
                continue;
            }
            for c in 0..pattern.width() {
                let board_col = col + c as i32;
                if board_col < 0
                    || board_col as usize >= self.width
                    || !pattern.is_alive(r, c)
                {
                    continue;
                }
                let index = board_row as usize * self.width + board_col as usize;
                new_alive[index] = true;
                new_ever[index] = true;
            }
        }
        // Not the hot path: recompute both active region and population.
        Generation::adopt(self.width, self.height, new_alive, new_ever, self.number)
    }

    pub fn width(&self) -> usize {
        self.width
    }

    pub fn height(&self) -> usize {
        self.height
    }

    pub fn number(&self) -> u64 {
        self.number
    }

    pub fn population(&self) -> usize {
        self.population
    }

    pub fn active_region(&self) -> ActiveRegion {
        self.active_region
    }

    pub fn is_alive(&self, row: usize, col: usize) -> bool {
        self.check_bounds(row, col);
        self.alive[row * self.width + col]
    }

    pub fn was_ever_alive(&self, row: usize, col: usize) -> bool {
        self.check_bounds(row, col);
        self.ever_alive[row * self.width + col]
    }

    /// The flat alive-flag buffer, indexed `row * width + col`.
    pub fn alive_slice(&self) -> &[bool] {
        &self.alive
    }

    /// The flat ever-alive-flag buffer, indexed `row * width + col`.
    pub fn ever_alive_slice(&self) -> &[bool] {
        &self.ever_alive
    }

    fn check_bounds(&self, row: usize, col: usize) {
        assert!(
            row < self.height && col < self.width,
            "Cell ({row}, {col}) is outside {}x{} board",
            self.height,
            self.width
        );
    }
}

fn count_alive(alive: &[bool]) -> usize {
    alive.iter().filter(|&&a| a).count()
}

fn compute_active_region(width: usize, height: usize, alive: &[bool]) -> ActiveRegion {
    let mut region = ActiveRegion::EMPTY;
    for row in 0..height {
        for col in 0..width {
            if alive[row * width + col] {
                region = region.expand_to(row as i32, col as i32);
            }
        }
    }
    region
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_util::{board, render};

    #[test]
    fn an_empty_board_has_no_live_cells_and_is_generation_zero() {
        let generation = Generation::empty(5, 3);

        assert_eq!(generation.width(), 5);
        assert_eq!(generation.height(), 3);
        assert_eq!(generation.population(), 0);
        assert_eq!(generation.number(), 0);
        assert!(!generation.is_alive(0, 0));
        assert!(!generation.was_ever_alive(2, 4));
    }

    #[test]
    #[should_panic(expected = "must be positive")]
    fn non_positive_dimensions_are_rejected() {
        let _ = Generation::empty(0, 5);
    }

    #[test]
    #[should_panic(expected = "must be positive")]
    fn zero_height_is_rejected() {
        let _ = Generation::empty(5, 0);
    }

    #[test]
    #[should_panic(expected = "outside")]
    fn out_of_bounds_access_is_rejected() {
        let generation = Generation::empty(4, 3);
        generation.is_alive(3, 0);
    }

    #[test]
    #[should_panic(expected = "outside")]
    fn with_cell_out_of_bounds_is_rejected() {
        let generation = Generation::empty(4, 3);
        generation.with_cell(0, usize::MAX, true);
    }

    #[test]
    fn with_cell_returns_a_new_board_and_leaves_the_original_untouched() {
        let original = Generation::empty(4, 4);
        let modified = original.with_cell(1, 2, true);

        assert_eq!(original.population(), 0);
        assert!(!original.is_alive(1, 2));
        assert_eq!(modified.population(), 1);
        assert!(modified.is_alive(1, 2));
        assert!(modified.was_ever_alive(1, 2));
    }

    #[test]
    fn clearing_a_cell_also_clears_its_ever_alive_marker() {
        let board = Generation::empty(3, 3)
            .with_cell(1, 1, true)
            .with_cell(1, 1, false);

        assert!(!board.is_alive(1, 1));
        assert!(!board.was_ever_alive(1, 1));
    }

    #[test]
    fn a_pattern_is_stamped_at_the_requested_offset() {
        let glider = Pattern::parse("glider", &[".O.", "..O", "OOO"]).unwrap();
        let generation = Generation::empty(6, 6).with_pattern(&glider, 1, 2);

        assert_eq!(
            render(&generation),
            ["......", "...O..", "....O.", "..OOO.", "......", "......"]
        );
    }

    #[test]
    fn a_pattern_that_overhangs_the_board_is_clipped_not_rejected() {
        let block = Pattern::parse("block", &["OO", "OO"]).unwrap();

        let bottom_right = Generation::empty(3, 3).with_pattern(&block, 2, 2);
        assert_eq!(bottom_right.population(), 1);
        assert!(bottom_right.is_alive(2, 2));

        let top_left = Generation::empty(3, 3).with_pattern(&block, -1, -1);
        assert_eq!(top_left.population(), 1);
        assert!(top_left.is_alive(0, 0));

        let entirely_outside = Generation::empty(3, 3).with_pattern(&block, 50, 50);
        assert_eq!(entirely_outside.population(), 0);
    }

    #[test]
    #[should_panic(expected = "must contain")]
    fn adopt_rejects_buffers_that_do_not_match_the_declared_dimensions() {
        let _ = Generation::adopt(3, 3, vec![false; 8], vec![false; 9], 0);
    }

    #[test]
    fn adopt_with_a_supplied_population_uses_it_without_scanning() {
        let mut alive = vec![false; 9];
        let mut ever_alive = vec![false; 9];
        alive[0] = true;
        alive[4] = true;
        ever_alive[0] = true;
        ever_alive[4] = true;

        let generation =
            Generation::adopt_fast(3, 3, alive, ever_alive, 0, ActiveRegion::EMPTY, 2);

        assert_eq!(generation.population(), 2);
        assert!(generation.is_alive(0, 0));
        assert!(generation.is_alive(1, 1));
    }

    #[test]
    fn with_cell_maintains_the_population_incrementally() {
        let base = Generation::empty(3, 3);

        let one = base.with_cell(1, 1, true);
        assert_eq!(one.population(), 1);

        let two = one.with_cell(2, 2, true);
        assert_eq!(two.population(), 2);

        // Turning an already-live cell on again must not double-count.
        let same = two.with_cell(2, 2, true);
        assert_eq!(same.population(), 2);

        let back = two.with_cell(1, 1, false);
        assert_eq!(back.population(), 1);
    }

    #[test]
    fn with_pattern_counts_only_cells_that_newly_become_alive() {
        let block = Pattern::parse("block", &["OO", "OO"]).unwrap();
        let base = Generation::empty(4, 4);

        let stamped = base.with_pattern(&block, 0, 0);
        assert_eq!(stamped.population(), 4);

        // Overlapping stamp: the two cells already alive must not be double-counted.
        let overlapped = stamped.with_pattern(&block, 1, 1);
        assert_eq!(overlapped.population(), 7);
    }

    #[test]
    fn boards_with_identical_contents_and_generation_numbers_are_equal() {
        let first = board(&["OO.", ".O.", "..."]);
        let second = board(&["OO.", ".O.", "..."]);
        let different = board(&["OO.", ".OO", "..."]);

        assert_eq!(first, second);
        assert_ne!(first, different);
    }
}