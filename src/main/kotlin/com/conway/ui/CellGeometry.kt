package com.conway.ui

/**
 * Maps pixel coordinates within a viewport onto cell indices.
 *
 * Extracted from the board component so the arithmetic can be unit tested without a
 * display, and so the row and column cases share one implementation instead of two
 * near-identical copies.
 *
 * Out-of-range input returns [NO_CELL]. Points past the last cell also return `NO_CELL`
 * rather than the cell <em>count</em>, which is not a valid index.
 */
class CellGeometry(private val rows: Int, private val columns: Int) {

    init {
        require(rows > 0 && columns > 0) { "Grid must have positive extents but was ${rows}x$columns" }
    }

    /** @return the row containing [y], or [NO_CELL] if it lies outside the board */
    fun rowAt(y: Int, viewportHeight: Int): Int = indexAt(y, viewportHeight, rows)

    /** @return the column containing [x], or [NO_CELL] if it lies outside the board */
    fun columnAt(x: Int, viewportWidth: Int): Int = indexAt(x, viewportWidth, columns)

    private fun indexAt(position: Int, extent: Int, count: Int): Int {
        if (extent <= 0 || position < 0 || position >= extent) return NO_CELL
        val index = (position * count.toLong() / extent).toInt()
        return if (index < 0 || index >= count) NO_CELL else index
    }

    companion object {
        /** Returned when a coordinate does not fall on a cell. */
        const val NO_CELL = -1
    }
}