package com.conway.ui

import com.conway.model.Generation
import kotlin.math.roundToInt

/**
 * Renders a [Generation] into an opaque ARGB `IntArray`, one entry per pixel.
 *
 * This is the Swing-era board component's painting core, made headless: cell colours are
 * written straight into a flat pixel buffer, drawing only the cells whose colour changed
 * since the previous generation, so a repaint is proportional to the change rather than
 * the board area. The caller then scales the buffer once to the viewport. It only touches
 * arrays, so it is unit-testable without a display.
 */
class BoardRenderer(
    val rows: Int,
    val columns: Int,
    private val cellSize: Int,
    deviceScale: Double = 1.0,
) {
    init {
        require(cellSize >= 1) { "cellSize must be at least 1 but was $cellSize" }
    }

    private val scaledCellSize = maxOf(1, (cellSize * deviceScale).roundToInt())
    private val scaledSeparator = maxOf(1, deviceScale.roundToInt())

    val pixelWidth: Int = columns * scaledCellSize
    val pixelHeight: Int = rows * scaledCellSize

    private val pixels = IntArray(pixelWidth * pixelHeight)
    private var rendered: Generation? = null

    /** Renders [generation] into [pixels], returning it. Only changed cells are rewritten. */
    fun render(generation: Generation): IntArray {
        val previous = rendered
        if (previous != null && previous.width == generation.width && previous.height == generation.height) {
            renderChanges(previous, generation)
        } else {
            renderAll(generation)
        }
        rendered = generation
        return pixels
    }

    /** Redraws every cell, plus the separators and any area the board does not cover. */
    private fun renderAll(generation: Generation) {
        val boardRows = minOf(rows, generation.height)
        val boardColumns = minOf(columns, generation.width)
        val alive = generation.aliveCells()
        val everAlive = generation.everAliveCells()
        val stride = pixelWidth
        val inner = innerCellSize()

        // Covers the separators and any area not written below. Only the full path needs
        // this: separators never change, so an incremental update must not repaint them.
        pixels.fill(Theme.BOARD_GRID)

        for (row in 0 until boardRows) {
            val cellRowBase = row * generation.width
            for (col in 0 until boardColumns) {
                writePixel(row, col, colorOf(alive, everAlive, cellRowBase + col), inner, stride)
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
        val stride = pixelWidth
        val inner = innerCellSize()
        val width = current.width

        for (row in 0 until boardRows) {
            val cellRowBase = row * width
            for (col in 0 until boardColumns) {
                val index = cellRowBase + col
                val rgb = colorOf(alive, everAlive, index)
                if (rgb != colorOf(previousAlive, previousEver, index)) {
                    writePixel(row, col, rgb, inner, stride)
                }
            }
        }
    }

    private fun writePixel(row: Int, col: Int, rgb: Int, inner: Int, stride: Int) {
        val y0 = row * scaledCellSize
        val x0 = col * scaledCellSize
        for (y in 0 until inner) {
            val offset = (y0 + y) * stride + x0
            pixels.fill(rgb, offset, offset + inner)
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
    }
}

/**
 * Converts an opaque ARGB buffer into the premultiplied RGBA bytes Skia expects for an
 * `RGBA_8888` image, in row-major order with [width] * 4 bytes per row. Writes into [out]
 * (replacing its contents in place) and returns it.
 */
fun IntArray.toRgbaPremulBytes(width: Int, height: Int, out: ByteArray = ByteArray(size * 4)): ByteArray {
    check(size == width * height) { "Pixel buffer size ${size} != ${width}x$height" }
    check(out.size == size * 4) { "Output buffer size ${out.size} != ${size} * 4" }
    var target = 0
    for (pixel in this) {
        // Inputs are opaque, so premultiplication is the identity; alpha is 255.
        out[target] = (pixel shr 16 and 0xFF).toByte()
        out[target + 1] = (pixel shr 8 and 0xFF).toByte()
        out[target + 2] = (pixel and 0xFF).toByte()
        out[target + 3] = 255.toByte()
        target += 4
    }
    return out
}