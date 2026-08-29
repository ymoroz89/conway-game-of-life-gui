package com.conway.model

/**
 * An immutable named arrangement of cells, independent of any board.
 *
 * Patterns are data, not code: they are parsed from the widely used plaintext
 * `.cells` format rather than hard-coded as index assignments, so adding a pattern
 * means adding a resource file and does not require touching the board or the
 * container.
 */
class Pattern private constructor(
    val name: String,
    val width: Int,
    val height: Int,
    private val cells: BooleanArray,
) {
    /**
     * Parses the plaintext `.cells` format: lines beginning with `!` are comments,
     * `.` and space are dead cells, and any other character is alive. Short lines are
     * padded, so trailing dead cells may be omitted.
     *
     * @throws IllegalArgumentException if the input contains no cell rows
     */
    companion object {
        private const val DEAD = '.'
        private const val COMMENT = '!'

        fun parse(name: String, lines: List<String>): Pattern {
            val rows = ArrayList<String>()
            for (line in lines) {
                if (!line.startsWith(COMMENT.toString())) {
                    rows.add(line)
                }
            }
            while (rows.isNotEmpty() && rows.last().isBlank()) {
                rows.removeAt(rows.size - 1)
            }
            require(rows.isNotEmpty()) { "Pattern '$name' contains no cell rows" }

            val height = rows.size
            var width = 0
            for (row in rows) {
                width = maxOf(width, row.length)
            }
            require(width > 0) { "Pattern '$name' has zero width" }

            val cells = BooleanArray(width * height)
            for (r in 0 until height) {
                val row = rows[r]
                for (c in 0 until row.length) {
                    val ch = row[c]
                    cells[r * width + c] = ch != DEAD && ch != ' '
                }
            }
            return Pattern(name, width, height, cells)
        }
    }

    fun isAlive(row: Int, col: Int): Boolean {
        checkBounds(row, col)
        return cells[row * width + col]
    }

    /** Number of live cells in the pattern. */
    val population: Int
        get() = cells.count { it }

    /** Returns this pattern rotated by 180 degrees. Applying it twice is the identity. */
    fun rotate180(): Pattern {
        val rotated = BooleanArray(cells.size)
        for (r in 0 until height) {
            for (c in 0 until width) {
                rotated[(height - 1 - r) * width + (width - 1 - c)] = cells[r * width + c]
            }
        }
        return Pattern("$name (rotated 180)", width, height, rotated)
    }

    private fun checkBounds(row: Int, col: Int) {
        if (row < 0 || row >= height || col < 0 || col >= width) {
            throw IndexOutOfBoundsException(
                "Cell ($row, $col) is outside pattern ${height}x$width",
            )
        }
    }

    override fun toString(): String =
        "Pattern[$name, ${height}x$width, population=$population]"
}