package com.conway.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CellGeometryTest {

    private final CellGeometry geometry = new CellGeometry(10, 20);

    @Test
    @DisplayName("Coordinates map to the containing cell")
    void mapsToContainingCell() {
        // 200px wide viewport over 20 columns: 10px per column.
        assertEquals(0, geometry.columnAt(0, 200));
        assertEquals(0, geometry.columnAt(9, 200));
        assertEquals(1, geometry.columnAt(10, 200));
        assertEquals(19, geometry.columnAt(199, 200));

        // 100px tall viewport over 10 rows: 10px per row.
        assertEquals(0, geometry.rowAt(0, 100));
        assertEquals(5, geometry.rowAt(50, 100));
        assertEquals(9, geometry.rowAt(99, 100));
    }

    @Test
    @DisplayName("Coordinates past the last cell report no cell rather than an invalid index")
    void pastTheEndReturnsNoCell() {
        // The previous implementation returned the column count here, which is one
        // past the last valid index and produced an out-of-bounds access downstream.
        assertEquals(CellGeometry.NO_CELL, geometry.columnAt(200, 200));
        assertEquals(CellGeometry.NO_CELL, geometry.columnAt(5_000, 200));
        assertEquals(CellGeometry.NO_CELL, geometry.rowAt(100, 100));
        assertEquals(CellGeometry.NO_CELL, geometry.rowAt(5_000, 100));
    }

    @Test
    @DisplayName("Negative coordinates report no cell")
    void negativeCoordinatesReturnNoCell() {
        assertEquals(CellGeometry.NO_CELL, geometry.columnAt(-1, 200));
        assertEquals(CellGeometry.NO_CELL, geometry.rowAt(-1, 100));
    }

    @Test
    @DisplayName("A collapsed viewport reports no cell instead of dividing by zero")
    void zeroSizedViewportReturnsNoCell() {
        assertEquals(CellGeometry.NO_CELL, geometry.columnAt(0, 0));
        assertEquals(CellGeometry.NO_CELL, geometry.rowAt(0, 0));
    }

    @Test
    @DisplayName("Every pixel of a viewport maps to a valid cell")
    void everyPixelMapsWithinBounds() {
        for (int viewport : new int[] {20, 37, 200, 803, 1000}) {
            for (int x = 0; x < viewport; x++) {
                int column = geometry.columnAt(x, viewport);
                if (column < 0 || column >= 20) {
                    throw new AssertionError(
                            "x=" + x + " in viewport " + viewport + " mapped to column " + column);
                }
            }
        }
    }

    @Test
    @DisplayName("Non-positive grid extents are rejected")
    void rejectsInvalidGrid() {
        assertThrows(IllegalArgumentException.class, () -> new CellGeometry(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new CellGeometry(5, -1));
    }
}
