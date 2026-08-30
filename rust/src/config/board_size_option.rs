/// A board size offered in the user interface.
///
/// `cells` is the simulated grid edge in cells; `label` is what the control
/// displays. The two differ: the presets simulate 201, 401 and 801 cells but
/// are shown as 200, 400 and 800, because the round number is the useful thing
/// to read and the extra cell is an implementation detail of the grid.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BoardSizeOption {
    cells: usize,
    label: String,
}

impl BoardSizeOption {
    /// # Panics
    ///
    /// Panics unless `cells` is positive.
    pub fn new(cells: usize, label: impl Into<String>) -> BoardSizeOption {
        assert!(cells > 0, "cells must be positive but was {cells}");
        BoardSizeOption {
            cells,
            label: label.into(),
        }
    }

    pub fn cells(&self) -> usize {
        self.cells
    }

    pub fn label(&self) -> &str {
        &self.label
    }

    /// The sizes the control offers, in ascending order.
    ///
    /// Always contains the configured grid. A grid set through
    /// `CONWAY_GRID_SIZE` that is not one of the presets is added rather than
    /// silently replaced by one, so the setting keeps working; because there is
    /// no round number to show for it, it is labelled with its actual size.
    ///
    /// # Panics
    ///
    /// Panics unless `configured` is positive.
    pub fn options(configured: usize) -> Vec<BoardSizeOption> {
        assert!(configured > 0, "configured cells must be positive but was {configured}");
        const PRESETS: [(usize, &str); 3] = [(201, "200"), (401, "400"), (801, "800")];

        let mut options: Vec<BoardSizeOption> = PRESETS
            .iter()
            .map(|&(cells, label)| BoardSizeOption::new(cells, label))
            .collect();
        if !PRESETS.iter().any(|&(cells, _)| cells == configured) {
            options.push(BoardSizeOption::new(configured, configured.to_string()));
        }
        options.sort_by_key(BoardSizeOption::cells);
        options
    }

    /// The option for `cells`, or `None` if no option has that size.
    pub fn find(options: &[BoardSizeOption], cells: usize) -> Option<BoardSizeOption> {
        options.iter().find(|option| option.cells == cells).cloned()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn presets_simulate_an_odd_grid_but_display_the_round_number() {
        for (cells, label) in [(201usize, "200"), (401, "400"), (801, "800")] {
            let option = BoardSizeOption::find(&BoardSizeOption::options(201), cells).unwrap();
            assert_eq!(option.cells(), cells, "the simulated grid");
            assert_eq!(option.label(), label, "what the control shows");
        }
    }

    #[test]
    fn offers_the_presets_in_ascending_order() {
        let options = BoardSizeOption::options(201);

        assert_eq!(options.len(), 3);
        let sizes: Vec<usize> = options.iter().map(BoardSizeOption::cells).collect();
        assert_eq!(sizes, [201, 401, 801]);
        let labels: Vec<&str> = options.iter().map(BoardSizeOption::label).collect();
        assert_eq!(labels, ["200", "400", "800"]);
    }

    #[test]
    fn keeps_a_configured_size_that_is_not_a_preset() {
        let options = BoardSizeOption::options(1601);

        assert_eq!(options.len(), 4);
        let sizes: Vec<usize> = options.iter().map(BoardSizeOption::cells).collect();
        assert_eq!(sizes, [201, 401, 801, 1601]);
        let custom = BoardSizeOption::find(&options, 1601).unwrap();
        assert_eq!(custom.label(), "1601", "a size with no round equivalent is labelled with its actual value");
    }

    #[test]
    fn sorts_a_small_configured_size_first() {
        let options = BoardSizeOption::options(51);

        assert_eq!(options[0].cells(), 51);
        let sizes: Vec<usize> = options.iter().map(BoardSizeOption::cells).collect();
        assert_eq!(sizes, [51, 201, 401, 801]);
    }

    #[test]
    fn does_not_duplicate_a_preset() {
        let options = BoardSizeOption::options(401);

        assert_eq!(options.len(), 3);
        assert_eq!(options.iter().filter(|o| o.cells() == 401).count(), 1);
    }

    #[test]
    fn find_locates_offered_sizes_only() {
        let options = BoardSizeOption::options(201);
        for option in &options {
            assert_eq!(BoardSizeOption::find(&options, option.cells()).as_ref(), Some(option));
        }
        assert_eq!(BoardSizeOption::find(&options, 12_345), None);
    }

    #[test]
    #[should_panic(expected = "must be positive")]
    fn non_positive_sizes_are_rejected() {
        let _ = BoardSizeOption::options(0);
    }

    #[test]
    #[should_panic(expected = "must be positive")]
    fn a_non_positive_option_itself_is_rejected() {
        let _ = BoardSizeOption::new(0, "0");
    }
}