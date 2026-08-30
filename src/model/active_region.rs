/// An immutable integer rectangle, used to track the active region of a board.
///
/// "Active" means the bounding box of every live cell, padded by one cell in
/// each direction when a generation is computed (Life rules can be affected by
/// neighbours one step away).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ActiveRegion {
    pub min_row: i32,
    pub max_row: i32,
    pub min_col: i32,
    pub max_col: i32,
}

impl ActiveRegion {
    pub const EMPTY: ActiveRegion = ActiveRegion {
        min_row: i32::MAX,
        max_row: -1,
        min_col: i32::MAX,
        max_col: -1,
    };

    pub fn new(min_row: i32, max_row: i32, min_col: i32, max_col: i32) -> ActiveRegion {
        ActiveRegion {
            min_row,
            max_row,
            min_col,
            max_col,
        }
    }

    pub fn is_empty(&self) -> bool {
        self.min_row > self.max_row || self.min_col > self.max_col
    }

    /// A region that includes this region and the cell at (row, col).
    pub fn expand_to(&self, row: i32, col: i32) -> ActiveRegion {
        ActiveRegion {
            min_row: self.min_row.min(row),
            max_row: self.max_row.max(row),
            min_col: self.min_col.min(col),
            max_col: self.max_col.max(col),
        }
    }

    /// A region that covers both this and another region.
    pub fn merge(&self, other: ActiveRegion) -> ActiveRegion {
        if other.is_empty() {
            return *self;
        }
        if self.is_empty() {
            return other;
        }
        ActiveRegion {
            min_row: self.min_row.min(other.min_row),
            max_row: self.max_row.max(other.max_row),
            min_col: self.min_col.min(other.min_col),
            max_col: self.max_col.max(other.max_col),
        }
    }

    /// A region expanded by one cell in all directions, clamped to the board.
    pub fn pad(&self, width: usize, height: usize) -> ActiveRegion {
        if self.is_empty() {
            return *self;
        }
        ActiveRegion {
            min_row: 0.max(self.min_row - 1),
            max_row: (height as i32 - 1).min(self.max_row + 1),
            min_col: 0.max(self.min_col - 1),
            max_col: (width as i32 - 1).min(self.max_col + 1),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_region_is_detected() {
        assert!(ActiveRegion::EMPTY.is_empty());
        assert!(!ActiveRegion::new(2, 5, 2, 5).is_empty());
    }

    #[test]
    fn padding_extends_by_one_and_clamps_to_bounds() {
        let region = ActiveRegion::new(5, 10, 5, 10).pad(12, 12);
        assert_eq!(region.min_row, 4);
        assert_eq!(region.max_row, 11);
        assert_eq!(region.min_col, 4);
        assert_eq!(region.max_col, 11);
    }

    #[test]
    fn padding_a_region_touching_the_edge_clamps_instead_of_wrapping() {
        let region = ActiveRegion::new(0, 0, 0, 0).pad(4, 4);
        assert_eq!(region, ActiveRegion::new(0, 1, 0, 1));
    }

    #[test]
    fn merge_with_empty_is_identity() {
        let region = ActiveRegion::new(1, 2, 3, 4);
        assert_eq!(region.merge(ActiveRegion::EMPTY), region);
        assert_eq!(ActiveRegion::EMPTY.merge(region), region);
    }

    #[test]
    fn expand_to_edges() {
        let region = ActiveRegion::EMPTY.expand_to(3, 7);
        assert_eq!(region, ActiveRegion::new(3, 3, 7, 7));
        assert_eq!(region.expand_to(0, 0), ActiveRegion::new(0, 3, 0, 7));
    }
}