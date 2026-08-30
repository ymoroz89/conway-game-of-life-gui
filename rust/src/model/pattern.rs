use std::error::Error;
use std::fmt;

/// An immutable named arrangement of cells, independent of any board.
///
/// Patterns are data, not code: they are parsed from the widely used plaintext
/// `.cells` format rather than hard-coded as index assignments, so adding a
/// pattern means adding a resource file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Pattern {
    name: String,
    width: usize,
    height: usize,
    cells: Vec<bool>,
}

/// A pattern source that cannot be interpreted as a `.cells` grid.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PatternError(pub String);

impl fmt::Display for PatternError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl Error for PatternError {}

impl Pattern {
    /// Parses the plaintext `.cells` format: lines beginning with `!` are
    /// comments, `.` and space are dead cells, and any other character is
    /// alive. Short lines are padded, so trailing dead cells may be omitted.
    ///
    /// # Errors
    ///
    /// Returns a [`PatternError`] if the input contains no cell rows or has
    /// zero width.
    pub fn parse(name: impl Into<String>, lines: &[&str]) -> Result<Pattern, PatternError> {
        let name = name.into();
        let mut rows: Vec<&str> = lines
            .iter()
            .copied()
            .filter(|line| !line.starts_with('!'))
            .collect();
        while let Some(row) = rows.last() {
            if row.trim().is_empty() {
                rows.pop();
            } else {
                break;
            }
        }
        if rows.is_empty() {
            return Err(PatternError(format!(
                "Pattern '{name}' contains no cell rows"
            )));
        }

        let height = rows.len();
        let width = rows.iter().map(|row| row.len()).max().unwrap_or(0);
        if width == 0 {
            return Err(PatternError(format!("Pattern '{name}' has zero width")));
        }

        let mut cells = vec![false; width * height];
        for (r, row) in rows.iter().enumerate() {
            for (c, ch) in row.chars().enumerate() {
                cells[r * width + c] = !matches!(ch, '.' | ' ');
            }
        }
        Ok(Pattern {
            name,
            width,
            height,
            cells,
        })
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    pub fn width(&self) -> usize {
        self.width
    }

    pub fn height(&self) -> usize {
        self.height
    }

    /// Whether the cell at (row, col) is alive.
    ///
    /// # Panics
    ///
    /// Panics if the coordinates are outside the pattern.
    pub fn is_alive(&self, row: usize, col: usize) -> bool {
        if row >= self.height || col >= self.width {
            panic!(
                "Cell ({row}, {col}) is outside pattern {}x{}",
                self.height, self.width
            );
        }
        self.cells[row * self.width + col]
    }

    /// Number of live cells in the pattern.
    pub fn population(&self) -> usize {
        self.cells.iter().filter(|&&alive| alive).count()
    }

    /// Returns this pattern rotated by 180 degrees. Applying it twice is the
    /// identity.
    pub fn rotate180(&self) -> Pattern {
        let mut rotated = vec![false; self.cells.len()];
        for r in 0..self.height {
            for c in 0..self.width {
                rotated[(self.height - 1 - r) * self.width + (self.width - 1 - c)] =
                    self.cells[r * self.width + c];
            }
        }
        Pattern {
            name: format!("{} (rotated 180)", self.name),
            width: self.width,
            height: self.height,
            cells: rotated,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rows(pattern: &Pattern) -> Vec<String> {
        (0..pattern.height())
            .map(|row| {
                (0..pattern.width())
                    .map(|col| if pattern.is_alive(row, col) { 'O' } else { '.' })
                    .collect()
            })
            .collect()
    }

    #[test]
    fn parses_plaintext() {
        let glider = Pattern::parse("glider", &[".O.", "..O", "OOO"]).unwrap();

        assert_eq!(glider.name(), "glider");
        assert_eq!(glider.width(), 3);
        assert_eq!(glider.height(), 3);
        assert_eq!(glider.population(), 5);
        assert!(glider.is_alive(0, 1));
        assert!(!glider.is_alive(0, 0));
        assert!(glider.is_alive(2, 0));
    }

    #[test]
    fn ignores_comments() {
        let pattern =
            Pattern::parse("commented", &["!Name: something", "!author", "OO", "OO"]).unwrap();

        assert_eq!(pattern.width(), 2);
        assert_eq!(pattern.height(), 2);
        assert_eq!(pattern.population(), 4);
    }

    #[test]
    fn pads_short_rows() {
        let pattern = Pattern::parse("ragged", &["O", "OOOO", "OO"]).unwrap();

        assert_eq!(pattern.width(), 4);
        assert_eq!(pattern.height(), 3);
        assert_eq!(pattern.population(), 7);
        assert!(!pattern.is_alive(0, 3));
        assert!(pattern.is_alive(1, 3));
    }

    #[test]
    fn treats_spaces_as_dead() {
        let pattern = Pattern::parse("spaced", &["O O", "   "]).unwrap();

        assert_eq!(pattern.population(), 2);
        assert!(!pattern.is_alive(0, 1));
    }

    #[test]
    fn rejects_empty_input() {
        assert!(Pattern::parse("empty", &[]).is_err());
        assert!(Pattern::parse("comments-only", &["!just a comment"]).is_err());
    }

    #[test]
    fn rotate180_reverses_both_axes() {
        let original = Pattern::parse("L", &["O..", "O..", "OO."]).unwrap();
        let rotated = original.rotate180();

        assert_eq!(original.width(), rotated.width());
        assert_eq!(original.height(), rotated.height());
        assert_eq!(original.population(), rotated.population());
        assert_eq!(rows(&rotated), [".OO", "..O", "..O"]);
    }

    #[test]
    fn rotate_is_an_involution() {
        let original = Pattern::parse("glider", &[".O.", "..O", "OOO"]).unwrap();

        assert_eq!(rows(&original), rows(&original.rotate180().rotate180()));
    }

    #[test]
    #[should_panic(expected = "outside pattern")]
    fn rejects_out_of_range_access() {
        let pattern = Pattern::parse("block", &["OO", "OO"]).unwrap();
        pattern.is_alive(2, 0);
    }
}