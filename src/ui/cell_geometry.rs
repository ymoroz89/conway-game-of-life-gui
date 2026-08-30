/// Maps pixel coordinates within a viewport onto cell indices.
///
/// Extracted from the board rendering so the arithmetic can be unit tested
/// without a window, and so the row and column cases share one implementation
/// instead of two near-identical copies.
///
/// Out-of-range input returns `None`. Returning the row or column *count* for
/// points past the last cell would not be a valid index, and produced an
/// out-of-bounds access in any caller that did not separately re-check the
/// bounds.
#[derive(Debug, Clone, Copy)]
pub struct CellGeometry {
    rows: usize,
    columns: usize,
}

impl CellGeometry {
    /// # Panics
    ///
    /// Panics unless both extents are positive.
    pub fn new(rows: usize, columns: usize) -> CellGeometry {
        assert!(
            rows > 0 && columns > 0,
            "Grid must have positive extents but was {rows}x{columns}"
        );
        CellGeometry { rows, columns }
    }

    /// The row containing `y`, or `None` if it lies outside the board.
    pub fn row_at(&self, y: f32, viewport_height: f32) -> Option<usize> {
        Self::index_at(y, viewport_height, self.rows)
    }

    /// The column containing `x`, or `None` if it lies outside the board.
    pub fn column_at(&self, x: f32, viewport_width: f32) -> Option<usize> {
        Self::index_at(x, viewport_width, self.columns)
    }

    fn index_at(position: f32, extent: f32, count: usize) -> Option<usize> {
        if extent <= 0.0 || !position.is_finite() || position < 0.0 || position >= extent {
            return None;
        }
        let index = (position * count as f32 / extent) as usize;
        (index < count).then_some(index)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn geometry() -> CellGeometry {
        CellGeometry::new(10, 20)
    }

    #[test]
    fn maps_to_containing_cell() {
        let geometry = geometry();
        // 200px wide viewport over 20 columns: 10px per column.
        assert_eq!(geometry.column_at(0.0, 200.0), Some(0));
        assert_eq!(geometry.column_at(9.0, 200.0), Some(0));
        assert_eq!(geometry.column_at(10.0, 200.0), Some(1));
        assert_eq!(geometry.column_at(199.0, 200.0), Some(19));

        // 100px tall viewport over 10 rows: 10px per row.
        assert_eq!(geometry.row_at(0.0, 100.0), Some(0));
        assert_eq!(geometry.row_at(50.0, 100.0), Some(5));
        assert_eq!(geometry.row_at(99.0, 100.0), Some(9));
    }

    #[test]
    fn past_the_end_returns_no_cell() {
        let geometry = geometry();
        assert_eq!(geometry.column_at(200.0, 200.0), None);
        assert_eq!(geometry.column_at(5_000.0, 200.0), None);
        assert_eq!(geometry.row_at(100.0, 100.0), None);
        assert_eq!(geometry.row_at(5_000.0, 100.0), None);
    }

    #[test]
    fn negative_coordinates_return_no_cell() {
        let geometry = geometry();
        assert_eq!(geometry.column_at(-1.0, 200.0), None);
        assert_eq!(geometry.row_at(-1.0, 100.0), None);
    }

    #[test]
    fn a_collapsed_viewport_returns_no_cell_instead_of_dividing_by_zero() {
        let geometry = geometry();
        assert_eq!(geometry.column_at(0.0, 0.0), None);
        assert_eq!(geometry.row_at(0.0, 0.0), None);
    }

    #[test]
    fn every_pixel_of_a_viewport_maps_to_a_valid_cell() {
        let geometry = geometry();
        for viewport in [20.0f32, 37.0, 200.0, 803.0, 1000.0] {
            for x in 0..viewport as i32 {
                let x = x as f32;
                let column = geometry.column_at(x, viewport);
                assert!(
                    matches!(column, Some(c) if c < 20),
                    "x={x} in viewport {viewport} mapped to {column:?}"
                );
            }
        }
    }

    #[test]
    #[should_panic(expected = "positive extents")]
    fn rejects_invalid_grid() {
        let _ = CellGeometry::new(0, 5);
    }

    #[test]
    #[should_panic(expected = "positive extents")]
    fn rejects_negative_column_extent() {
        let _ = CellGeometry::new(5, 0);
    }
}