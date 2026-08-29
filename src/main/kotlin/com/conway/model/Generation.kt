package com.conway.model

/**
 * An immutable snapshot of the board at a single point in the simulation.
 *
 * This type is the mechanism that removes the data race between the simulation thread
 * and the UI thread. All properties are effectively final and the backing arrays are
 * never mutated after construction, so publishing an instance through a `@Volatile`
 * field guarantees every reader observes a fully initialised, internally consistent
 * generation. Readers never see a half-applied generation, and no locking is needed on
 * the render path.
 *
 * Cells carry two independent flags:
 * - [alive] — participates in the Game of Life rules.
 * - [everAlive] — purely presentational; records whether the cell has ever been alive
 *   so the renderer can distinguish "died here" from "never populated". It is monotonic
 *   within a run and is reset only by a clear.
 *
 * Both are stored as flat `BooleanArray`s indexed `row * width + col` rather than as a
 * nested 2-D array, which removes ~40k object headers per board and gives the rules
 * engine sequential memory access.
 *
 * The population is maintained as a field rather than recomputed on demand. The rules
 * engine folds the count into a pass it already makes, so the hot path pays nothing
 * extra.
 */
class Generation private constructor(
    val width: Int,
    val height: Int,
    private val alive: BooleanArray,
    private val everAlive: BooleanArray,
    val number: Long,
    val activeRegion: ActiveRegion,
    val population: Int,
) {
    companion object {
        /** Creates an empty generation numbered zero. */
        fun empty(width: Int, height: Int): Generation {
            require(width > 0 && height > 0) {
                "Board dimensions must be positive but were ${width}x$height"
            }
            val cells = Math.multiplyExact(width, height)
            return Generation(
                width, height, BooleanArray(cells), BooleanArray(cells),
                0L, ActiveRegion.EMPTY, 0,
            )
        }

        /** Wraps caller-supplied arrays and computes the active region and population. */
        fun adopt(
            width: Int, height: Int,
            alive: BooleanArray, everAlive: BooleanArray,
            number: Long,
        ): Generation {
            val expected = Math.multiplyExact(width, height)
            require(alive.size == expected && everAlive.size == expected) {
                "Array length does not match ${width}x$height"
            }
            return Generation(
                width, height, alive, everAlive, number,
                computeActiveRegion(width, height, alive), countAlive(alive),
            )
        }

        /**
         * Wraps caller-supplied arrays with a pre-computed active region and population.
         *
         * This is the hot path. The rules engine already sweeps the destination arrays
         * while computing a generation, so it counts the population in that pass and
         * supplies the result here rather than forcing a second scan.
         */
        fun adopt(
            width: Int, height: Int,
            alive: BooleanArray, everAlive: BooleanArray,
            number: Long, activeRegion: ActiveRegion, population: Int,
        ): Generation {
            val expected = Math.multiplyExact(width, height)
            require(alive.size == expected && everAlive.size == expected) {
                "Array length does not match ${width}x$height"
            }
            require(population >= 0) { "population must not be negative but was $population" }
            return Generation(width, height, alive, everAlive, number, activeRegion, population)
        }

        private fun computeActiveRegion(width: Int, height: Int, alive: BooleanArray): ActiveRegion {
            var minRow = Int.MAX_VALUE
            var maxRow = -1
            var minCol = Int.MAX_VALUE
            var maxCol = -1
            for (r in 0 until height) {
                val rowBase = r * width
                for (c in 0 until width) {
                    if (alive[rowBase + c]) {
                        minRow = minOf(minRow, r)
                        maxRow = maxOf(maxRow, r)
                        minCol = minOf(minCol, c)
                        maxCol = maxOf(maxCol, c)
                    }
                }
            }
            return ActiveRegion(minRow, maxRow, minCol, maxCol)
        }

        private fun countAlive(alive: BooleanArray): Int = alive.count { it }
    }
fun isAlive(row: Int, col: Int): Boolean {
        checkBounds(row, col)
        return alive[row * width + col]
    }

    fun wasEverAlive(row: Int, col: Int): Boolean {
        checkBounds(row, col)
        return everAlive[row * width + col]
    }

    /**
     * Direct view of the alive flags, indexed `row * width + col`. Callers must treat the
     * result as strictly read-only; mutating it breaks the immutability contract that makes
     * cross-thread publication safe.
     */
    fun aliveCells(): BooleanArray = alive

    /** Direct view of the ever-alive flags. Same contract as [aliveCells]. */
    fun everAliveCells(): BooleanArray = everAlive

    /** Returns a copy of this generation with a single cell forced to the given state. */
    fun withCell(row: Int, col: Int, live: Boolean): Generation {
        checkBounds(row, col)
        val newAlive = alive.copyOf()
        val newEver = everAlive.copyOf()
        val index = row * width + col
        newAlive[index] = live
        newEver[index] = live
        // Not the hot path — recompute both active region and population from scratch.
        return adopt(width, height, newAlive, newEver, number)
    }

    /**
     * Returns a copy of this generation with [pattern] stamped in with its top-left corner
     * at (row, col). Cells that fall outside the board are clipped rather than throwing.
     */
    fun withPattern(pattern: Pattern, row: Int, col: Int): Generation {
        val newAlive = alive.copyOf()
        val newEver = everAlive.copyOf()
        for (r in 0 until pattern.height) {
            val boardRow = row + r
            if (boardRow < 0 || boardRow >= height) continue
            for (c in 0 until pattern.width) {
                val boardCol = col + c
                if (boardCol < 0 || boardCol >= width || !pattern.isAlive(r, c)) continue
                val index = boardRow * width + boardCol
                newAlive[index] = true
                newEver[index] = true
            }
        }
        return adopt(width, height, newAlive, newEver, number)
    }

    private fun checkBounds(row: Int, col: Int) {
        if (row < 0 || row >= height || col < 0 || col >= width) {
            throw IndexOutOfBoundsException(
                "Cell ($row, $col) is outside ${height}x$width board",
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Generation) return false
        return width == other.width &&
            height == other.height &&
            number == other.number &&
            population == other.population &&
            activeRegion == other.activeRegion &&
            alive.contentEquals(other.alive) &&
            everAlive.contentEquals(other.everAlive)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + number.hashCode()
        result = 31 * result + population
        result = 31 * result + alive.contentHashCode()
        result = 31 * result + everAlive.contentHashCode()
        return result
    }

    override fun toString(): String =
        "Generation[$number, ${width}x$height, population=$population]"
}