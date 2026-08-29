package com.conway.engine

import com.conway.model.ActiveRegion
import com.conway.model.Generation
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * Computes successive generations of Conway's Game of Life.
 *
 * The board is split into contiguous bands of rows. Each band writes only to its own
 * disjoint slice of the destination arrays, so no synchronisation is needed between
 * workers; [Future.get] supplies the happens-before edge that makes every write visible
 * to the thread that assembles the result.
 *
 * The calling thread computes one band itself rather than waiting for the pool to
 * compute all of them, so the degree of parallelism is unchanged but one hand-off per
 * generation is avoided. Boundaries are dead: the grid does not wrap.
 */
class LifeRules(private val executor: ExecutorService) {

    /**
     * Computes the generation following `current`. The result is identical for every
     * value of [partitions]; the parameter only controls how the work is distributed.
     *
     * @throws SimulationException if a worker fails
     */
    @Throws(InterruptedException::class)
    fun next(current: Generation, partitions: Int): Generation {
        val width = current.width
        val height = current.height
        val src = current.aliveCells()
        val srcEver = current.everAliveCells()
        val dst = BooleanArray(src.size)
        val dstEver = BooleanArray(src.size)

        val active = current.activeRegion.pad(width, height)
        if (active.isEmpty) {
            // Nothing alive, so nothing to compute -- but the ever-alive flags are monotonic
            // within a run and must be carried forward.
            System.arraycopy(srcEver, 0, dstEver, 0, srcEver.size)
            return Generation.adopt(width, height, dst, dstEver, current.number + 1,
                ActiveRegion.EMPTY, 0)
        }

        val bands = bandsFor(partitions, height, active)
        // Filled in band order, exactly once each. A list of non-null results rather than a
        // pre-sized nullable array: every band must contribute, and a missing one has to be
        // a failure rather than a silently under-counted population and active region.
        val bandResults = ArrayList<BandResult>(bands)

        if (bands == 1) {
            bandResults.add(computeBand(src, srcEver, dst, dstEver, width, height, 0, height, active))
        } else {
            val baseSize = height / bands
            val remainder = height % bands
            val from = IntArray(bands)
            val to = IntArray(bands)
            var start = 0
            for (i in 0 until bands) {
                val size = baseSize + if (i < remainder) 1 else 0
                from[i] = start
                to[i] = start + size
                start = to[i]
            }

            // Dispatch every band but the first, then compute that one here rather than
            // blocking for all of them. The calling thread is the simulation thread, which
            // otherwise parks for the whole generation.
            val futures = ArrayList<Future<BandResult>>(bands - 1)
            for (i in 1 until bands) {
                val bandFrom = from[i]
                val bandTo = to[i]
                futures.add(executor.submit(
                    Callable {
                        computeBand(src, srcEver, dst, dstEver, width, height, bandFrom, bandTo, active)
                    },
                ))
            }

            try {
                bandResults.add(
                    computeBand(src, srcEver, dst, dstEver, width, height, from[0], to[0], active),
                )
            } catch (e: RuntimeException) {
                cancelAll(futures)
                throw SimulationException("Generation computation failed", e)
            }

            for (i in 1 until bands) {
                try {
                    bandResults.add(futures[i - 1].get())
                } catch (e: ExecutionException) {
                    cancelAll(futures.subList(i, futures.size))
                    throw SimulationException("Generation computation failed", e.cause ?: e)
                }
            }
        }

        var nextActive = ActiveRegion.EMPTY
        var population = 0
        for (band in bandResults) {
            nextActive = nextActive.merge(band.region)
            population += band.population
        }
        return Generation.adopt(width, height, dst, dstEver, current.number + 1, nextActive, population)
    }

    /** Marks pending band futures as cancelled because a sibling band failed. Bands that
     *  already completed are no-ops: [Future.cancel] on a done task does nothing. */
    private fun cancelAll(futures: List<Future<BandResult>>) {
        for (future in futures) {
            future.cancel(true)
        }
    }

    /**
     * Only [bandsFor] lives here. The Java original made all of the helpers `static`, but a
     * Kotlin companion is an object with an instance, so moving [computeBand] and [ruleAt]
     * in would route the hot path through a synthetic accessor and a receiver it does not
     * need. [bandsFor] earns the hop because it is a pure decision function the banding
     * tests call directly; the per-cell work stays private to the class.
     */
    companion object {
        /**
         * How many bands to actually split this generation across. The requested count is
         * sized for the board, but the work in any one generation is bounded by the active
         * region, which can be far smaller; so the requested count is also capped by how many
         * bands the active rows can usefully fill.
         */
        internal fun bandsFor(partitions: Int, height: Int, active: ActiveRegion): Int {
            val requested = partitions.coerceIn(1, height)
            if (active.isEmpty) return 1
            val activeRows = active.maxRow - active.minRow + 1
            val useful = activeRows / PartitionPlanner.DEFAULT_MIN_ROWS_PER_PARTITION
            return requested.coerceIn(1, maxOf(1, useful))
        }
    }

    private fun computeBand(
        src: BooleanArray, srcEver: BooleanArray,
        dst: BooleanArray, dstEver: BooleanArray,
        width: Int, height: Int, fromRow: Int, toRow: Int,
        activeLimit: ActiveRegion,
    ): BandResult {
        var minRow = Int.MAX_VALUE
        var maxRow = -1
        var minCol = Int.MAX_VALUE
        var maxCol = -1

        var row = maxOf(fromRow, activeLimit.minRow)
        val rowEnd = minOf(toRow, activeLimit.maxRow + 1)

        while (row < rowEnd) {
            val rowBase = row * width
            var colStart = activeLimit.minCol
            val colEnd = activeLimit.maxCol + 1

            if (row > 0 && row < height - 1 && width > 2) {
                val up = rowBase - width
                val down = rowBase + width

                if (colStart == 0) {
                    dst[rowBase] = ruleAt(src, width, height, row, 0)
                    if (dst[rowBase]) {
                        minRow = minOf(minRow, row)
                        maxRow = maxOf(maxRow, row)
                        minCol = minOf(minCol, 0)
                        maxCol = maxOf(maxCol, 0)
                    }
                    colStart = 1
                }

                val effectiveColEnd = minOf(colEnd, width - 1)

                var col = colStart
                while (col < effectiveColEnd) {
                    val i = rowBase + col
                    val neighbours =
                        (if (src[up + col - 1]) 1 else 0) +
                            (if (src[up + col]) 1 else 0) +
                            (if (src[up + col + 1]) 1 else 0) +
                            (if (src[i - 1]) 1 else 0) +
                            (if (src[i + 1]) 1 else 0) +
                            (if (src[down + col - 1]) 1 else 0) +
                            (if (src[down + col]) 1 else 0) +
                            (if (src[down + col + 1]) 1 else 0)
                    dst[i] = if (src[i]) neighbours == 2 || neighbours == 3 else neighbours == 3
                    if (dst[i]) {
                        minRow = minOf(minRow, row)
                        maxRow = maxOf(maxRow, row)
                        minCol = minOf(minCol, col)
                        maxCol = maxOf(maxCol, col)
                    }
                    col++
                }

                if (colEnd == width) {
                    val i = rowBase + width - 1
                    dst[i] = ruleAt(src, width, height, row, width - 1)
                    if (dst[i]) {
                        minRow = minOf(minRow, row)
                        maxRow = maxOf(maxRow, row)
                        minCol = minOf(minCol, width - 1)
                        maxCol = maxOf(maxCol, width - 1)
                    }
                }
            } else {
                var col = colStart
                while (col < colEnd) {
                    val i = rowBase + col
                    dst[i] = ruleAt(src, width, height, row, col)
                    if (dst[i]) {
                        minRow = minOf(minRow, row)
                        maxRow = maxOf(maxRow, row)
                        minCol = minOf(minCol, col)
                        maxCol = maxOf(maxCol, col)
                    }
                    col++
                }
            }
            row++
        }

        // Carry the previous generation's ever-alive flags forward and fold the population
        // count in, fused into one pass per row so the row stays in cache.
        var population = 0
        var everRow = fromRow
        while (everRow < toRow) {
            val rowBase = everRow * width
            System.arraycopy(srcEver, rowBase, dstEver, rowBase, width)
            var col = 0
            while (col < width) {
                val i = rowBase + col
                dstEver[i] = dstEver[i] || dst[i]
                if (dst[i]) population++
                col++
            }
            everRow++
        }

        return BandResult(ActiveRegion(minRow, maxRow, minCol, maxCol), population)
    }

    /** Bounds-checked rule evaluation, used for cells on the border of the board. */
    private fun ruleAt(src: BooleanArray, width: Int, height: Int, row: Int, col: Int): Boolean {
        var neighbours = 0
        val rowStart = maxOf(0, row - 1)
        val rowEnd = minOf(height - 1, row + 1)
        val colStart = maxOf(0, col - 1)
        val colEnd = minOf(width - 1, col + 1)

        for (r in rowStart..rowEnd) {
            val base = r * width
            for (c in colStart..colEnd) {
                if ((r != row || c != col) && src[base + c]) neighbours++
            }
        }
        val alive = src[row * width + col]
        return if (alive) neighbours == 2 || neighbours == 3 else neighbours == 3
    }

    /**
     * What one band computed: the active region of its slice of the destination board,
     * and the number of live cells it wrote. The population is summed across bands by
     * [next] and handed to [Generation.adopt].
     */
    private data class BandResult(val region: ActiveRegion, val population: Int)
}
