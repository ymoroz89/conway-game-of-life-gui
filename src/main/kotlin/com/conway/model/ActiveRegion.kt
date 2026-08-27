package com.conway.model

/**
 * An immutable integer rectangle tracking the live area of the board.
 *
 * The simulation only evaluates within this region, so a sparse board does not pay
 * for the empty rows and columns around it.
 */
data class ActiveRegion(
    val minRow: Int,
    val maxRow: Int,
    val minCol: Int,
    val maxCol: Int,
) {
    val isEmpty: Boolean
        get() = minRow > maxRow || minCol > maxCol

    /** Returns a region that includes this region and the cell at (row, col). */
    fun expandTo(row: Int, col: Int): ActiveRegion = ActiveRegion(
        minOf(minRow, row),
        maxOf(maxRow, row),
        minOf(minCol, col),
        maxOf(maxCol, col),
    )

    /** Returns a region that covers both this and another region. */
    fun merge(other: ActiveRegion): ActiveRegion {
        if (other.isEmpty) return this
        if (this.isEmpty) return other
        return ActiveRegion(
            minOf(minRow, other.minRow),
            maxOf(maxRow, other.maxRow),
            minOf(minCol, other.minCol),
            maxOf(maxCol, other.maxCol),
        )
    }

    /**
     * Returns a region expanded by one cell in all directions, clamped to the board.
     * Life rules mean a cell can be influenced by neighbours one step away.
     */
    fun pad(width: Int, height: Int): ActiveRegion {
        if (isEmpty) return this
        return ActiveRegion(
            maxOf(0, minRow - 1),
            minOf(height - 1, maxRow + 1),
            maxOf(0, minCol - 1),
            minOf(width - 1, maxCol + 1),
        )
    }

    companion object {
        val EMPTY = ActiveRegion(Int.MAX_VALUE, -1, Int.MAX_VALUE, -1)
    }
}