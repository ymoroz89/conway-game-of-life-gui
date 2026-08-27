package com.conway.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CellGeometryTest {

    private val geometry = CellGeometry(10, 20)

    @Test
    @DisplayName("Coordinates map to the containing cell")
    fun mapsToContainingCell() {
        // 200px wide viewport over 20 columns: 10px per column.
        assertEquals(0, geometry.columnAt(0, 200))
        assertEquals(0, geometry.columnAt(9, 200))
        assertEquals(1, geometry.columnAt(10, 200))
        assertEquals(19, geometry.columnAt(199, 200))

        // 100px tall viewport over 10 rows: 10px per row.
        assertEquals(0, geometry.rowAt(0, 100))
        assertEquals(5, geometry.rowAt(50, 100))
        assertEquals(9, geometry.rowAt(99, 100))
    }

    @Test
    @DisplayName("Coordinates past the last cell report no cell rather than an invalid index")
    fun pastTheEndReturnsNoCell() {
        // The previous implementation returned the column count here, which is one
        // past the last valid index and produced an out-of-bounds access downstream.
        assertEquals(CellGeometry.NO_CELL, geometry.columnAt(200, 200))
        assertEquals(CellGeometry.NO_CELL, geometry.columnAt(5_000, 200))
        assertEquals(CellGeometry.NO_CELL, geometry.rowAt(100, 100))
        assertEquals(CellGeometry.NO_CELL, geometry.rowAt(5_000, 100))
    }

    @Test
    @DisplayName("Negative coordinates report no cell")
    fun negativeCoordinatesReturnNoCell() {
        assertEquals(CellGeometry.NO_CELL, geometry.columnAt(-1, 200))
        assertEquals(CellGeometry.NO_CELL, geometry.rowAt(-1, 100))
    }

    @Test
    @DisplayName("A collapsed viewport reports no cell instead of dividing by zero")
    fun zeroSizedViewportReturnsNoCell() {
        assertEquals(CellGeometry.NO_CELL, geometry.columnAt(0, 0))
        assertEquals(CellGeometry.NO_CELL, geometry.rowAt(0, 0))
    }

    @Test
    @DisplayName("Every pixel of a viewport maps to a valid cell")
    fun everyPixelMapsWithinBounds() {
        for (viewport in intArrayOf(20, 37, 200, 803, 1000)) {
            for (x in 0 until viewport) {
                val column = geometry.columnAt(x, viewport)
                if (column < 0 || column >= 20) {
                    throw AssertionError(
                        "x=$x in viewport $viewport mapped to column $column",
                    )
                }
            }
        }
    }

    @Test
    @DisplayName("Non-positive grid extents are rejected")
    fun rejectsInvalidGrid() {
        assertThrows(IllegalArgumentException::class.java) { CellGeometry(0, 5) }
        assertThrows(IllegalArgumentException::class.java) { CellGeometry(5, -1) }
    }
}
