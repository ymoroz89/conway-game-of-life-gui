package com.conway.ui

import com.conway.model.Generation
import kotlin.math.roundToInt

/**
 * Renders a [Generation] into a premultiplied RGBA byte buffer, four bytes per pixel.
 *
 * This is the Swing-era board component's painting core, made headless: cell colours are
 * written straight into a flat pixel buffer, drawing only the cells whose colour changed
 * since the previous generation, so a repaint is proportional to the change rather than
 * the board area. The caller then scales the buffer once to the viewport. It only touches
 * arrays, so it is unit-testable without a display.
 *
 * The buffer is in exactly the layout Skia installs for an `RGBA_8888` image, so
 * [BoardImage] can wrap it directly. Holding packed ARGB `Int`s here instead would force a
 * conversion pass over every pixel on every frame, which would cost more than the whole
 * incremental update it wraps and would throw away the point of diffing at all.
 *
 * @param deviceScale physical pixels per logical pixel on the display the board will be
 *                    shown on. Rendering at the device scale makes the blit 1:1 with
 *                    physical pixels, so cells stay crisp instead of being upscaled.
 */
class BoardRenderer(
    val rows: Int,
    val columns: Int,
    private val cellSize: Int,
    deviceScale: Double = 1.0,
) {
    init {
        require(cellSize >= 1) { "cellSize must be at least 1 but was $cellSize" }
        require(deviceScale > 0.0) { "deviceScale must be positive but was $deviceScale" }
    }

    private val scaledCellSize = maxOf(1, (cellSize * deviceScale).roundToInt())
    private val scaledSeparator = maxOf(1, deviceScale.roundToInt())

    val pixelWidth: Int = columns * scaledCellSize
    val pixelHeight: Int = rows * scaledCellSize

    /** Byte offset between the start of one pixel row and the next. */
    private val byteStride = pixelWidth * BYTES_PER_PIXEL

    /**
     * The rendered board, as premultiplied RGBA bytes in row-major order. Owned by this
     * renderer and mutated in place; [BoardImage] uploads it to Skia as it stands.
     */
    val bytes = ByteArray(pixelWidth * pixelHeight * BYTES_PER_PIXEL)

    private var rendered: Generation? = null

    /**
     * Renders [generation] into [bytes], returning it.
     *
     * Three paths, cheapest first. If the buffer already shows this generation there is
     * nothing to do, which covers every draw caused by a resize, a re-layout or a repaint
     * of an unchanged board. If the previous generation is the same size, only the cells
     * whose colour differs are rewritten. Otherwise the whole buffer is redrawn.
     *
     * The first test is reference identity, and that is exact rather than approximate:
     * generations are immutable and the engine publishes a fresh instance for every
     * change, so the same instance means the same pixels.
     */
    fun render(generation: Generation): ByteArray {
        val previous = rendered
        if (previous === generation) {
            return bytes
        }
        if (previous != null && previous.width == generation.width && previous.height == generation.height) {
            renderChanges(previous, generation)
        } else {
            renderAll(generation)
        }
        rendered = generation
        return bytes
    }

    /** Redraws every cell, plus the separators and any area the board does not cover. */
    private fun renderAll(generation: Generation) {
        val boardRows = minOf(rows, generation.height)
        val boardColumns = minOf(columns, generation.width)
        val alive = generation.aliveCells()
        val everAlive = generation.everAliveCells()
        val inner = innerCellSize()

        // Covers the separators and any area not written below. Only the full path needs
        // this: separators never change, so an incremental update must not repaint them.
        fillPixels(0, pixelWidth * pixelHeight, Theme.BOARD_GRID)

        for (row in 0 until boardRows) {
            val cellRowBase = row * generation.width
            for (col in 0 until boardColumns) {
                writeCell(row, col, colorOf(alive, everAlive, cellRowBase + col), inner)
            }
        }
    }

    /**
     * Rewrites only the cells whose colour differs between the two generations. The
     * comparison still visits every cell, but it reads two booleans per cell instead of
     * writing `inner * inner` pixels. Both generations have the same dimensions here.
     */
    private fun renderChanges(previous: Generation, current: Generation) {
        val boardRows = minOf(rows, current.height)
        val boardColumns = minOf(columns, current.width)
        val previousAlive = previous.aliveCells()
        val previousEver = previous.everAliveCells()
        val alive = current.aliveCells()
        val everAlive = current.everAliveCells()
        val inner = innerCellSize()
        val width = current.width

        for (row in 0 until boardRows) {
            val cellRowBase = row * width
            for (col in 0 until boardColumns) {
                val index = cellRowBase + col
                val rgb = colorOf(alive, everAlive, index)
                if (rgb != colorOf(previousAlive, previousEver, index)) {
                    writeCell(row, col, rgb, inner)
                }
            }
        }
    }

    /** Paints the coloured interior of one cell, [inner] pixels square. */
    private fun writeCell(row: Int, col: Int, rgb: Int, inner: Int) {
        val y0 = row * scaledCellSize
        val x0 = col * scaledCellSize
        for (y in 0 until inner) {
            fillPixels((y0 + y) * byteStride + x0 * BYTES_PER_PIXEL, inner, rgb)
        }
    }

    /** Writes [count] consecutive pixels of [rgb] starting at byte offset [start]. */
    private fun fillPixels(start: Int, count: Int, rgb: Int) {
        // Board colours are opaque, so premultiplication is the identity and alpha is 255.
        val r = (rgb shr 16 and 0xFF).toByte()
        val g = (rgb shr 8 and 0xFF).toByte()
        val b = (rgb and 0xFF).toByte()
        val a = 255.toByte()
        var offset = start
        repeat(count) {
            bytes[offset] = r
            bytes[offset + 1] = g
            bytes[offset + 2] = b
            bytes[offset + 3] = a
            offset += BYTES_PER_PIXEL
        }
    }

    /**
     * Edge of the coloured part of a cell, in pixels. One separator along the trailing
     * edges only; the previous inset of one pixel on every side left a 4px cell with a 2x2
     * interior, so three quarters of the board was grid line.
     */
    private fun innerCellSize(): Int {
        val inner = scaledCellSize - if (cellSize >= MIN_CELL_SIZE_FOR_GRID) scaledSeparator else 0
        return maxOf(1, inner)
    }

    private fun colorOf(alive: BooleanArray, everAlive: BooleanArray, index: Int): Int =
        if (alive[index]) {
            Theme.BOARD_ALIVE
        } else {
            if (everAlive[index]) Theme.BOARD_TRAIL else Theme.BOARD_EMPTY
        }

    companion object {
        /**
         * Cells narrower than this are drawn solid. A separator costs one pixel per axis, so
         * below this size it would consume more of the cell than the cell's own colour and the
         * board would read as a grey mesh rather than as a population.
         */
        const val MIN_CELL_SIZE_FOR_GRID = 3

        /** RGBA_8888: one byte per channel. */
        internal const val BYTES_PER_PIXEL = 4
    }
}
