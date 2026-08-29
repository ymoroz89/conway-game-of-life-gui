package com.conway.ui

import androidx.compose.ui.geometry.Rect
import com.conway.model.Generation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.roundToInt

/**
 * Headless tests for the board's pixel renderer and the letterbox/tap mapping.
 *
 * The Swing component these used to exercise through `paint(Graphics)` and dispatched
 * mouse events is gone; the arithmetic it owned now lives in [BoardRenderer] and
 * [letterbox], both of which are plain array/geometry code that needs no display.
 */
class BoardRendererTest {

    companion object {
        private const val ROWS = 10
        private const val COLUMNS = 10
        private const val CELL_SIZE = 4
    }

    @Test
    @DisplayName("Alive, died-here and never-populated cells each render in their theme colour")
    fun rendersCellStates() {
        // (0, 0) is alive; (1, 1) has died here (ever-alive without alive); the rest was
        // never populated.
        val pixels = render(generation(cells(0), cells(0, 11)))

        // Assert against the theme rather than repeating hex literals, so the palette has
        // exactly one definition and this test cannot drift away from what is rendered.
        assertEquals(Theme.BOARD_ALIVE, rgb(pixels, 0, 0), "alive cell")
        assertEquals(Theme.BOARD_TRAIL, rgb(pixels, 1, 1), "cell that has died")
        assertEquals(Theme.BOARD_EMPTY, rgb(pixels, 5, 5), "never populated cell")

        // The separator occupies the trailing edge of each cell, so it sits at the last
        // pixel of the first cell rather than at the origin.
        assertEquals(
            Theme.BOARD_GRID,
            pixelAt(pixels, CELL_SIZE - 1, CELL_SIZE - 1),
            "cell separator",
        )
    }

    @Test
    @DisplayName("A device scale renders the board at physical pixel density")
    fun deviceScaleRendersAtPhysicalResolution() {
        // The Swing board rendered its backing image at the display's scale factor so the
        // blit was 1:1 with physical pixels and cells stayed crisp. Rendering at the logical
        // size and letting the toolkit upscale gives back the blur that was fixed there.
        val retina = BoardRenderer(ROWS, COLUMNS, CELL_SIZE, deviceScale = 2.0)

        assertEquals(COLUMNS * CELL_SIZE * 2, retina.pixelWidth, "buffer width")
        assertEquals(ROWS * CELL_SIZE * 2, retina.pixelHeight, "buffer height")

        val pixels = retina.render(generation(cells(0), cells(0)))

        // A 4px cell at 2x is 8 physical pixels with a 2px separator on the trailing edges.
        assertEquals(Theme.BOARD_ALIVE, pixelAt(pixels, 0, 0, retina.pixelWidth), "cell interior")
        assertEquals(Theme.BOARD_ALIVE, pixelAt(pixels, 5, 5, retina.pixelWidth), "last interior pixel")
        assertEquals(Theme.BOARD_GRID, pixelAt(pixels, 7, 7, retina.pixelWidth), "scaled separator")
    }

    @Test
    @DisplayName("The board keeps its aspect ratio and centres itself in a non-square viewport")
    fun boardIsLetterboxedRatherThanStretched() {
        // Maximising the window used to stretch a square board across the full width,
        // making cells rectangular and skewing the diagonals the simulation produces.
        val viewport = letterbox(400f, 200f, pixelWidth(), pixelHeight())

        assertTrue(viewport != null)
        assertEquals(200, viewport!!.width.roundToInt(), "width should be limited by the shorter axis")
        assertEquals(200, viewport.height.roundToInt(), "board must stay square")
        assertEquals(100, viewport.left.roundToInt(), "board should be centred horizontally")
        assertEquals(0, viewport.top.roundToInt())
    }

    @Test
    @DisplayName("Taps map through the letterboxed board, not the viewport bounds")
    fun tapsMapThroughLetterbox() {
        // 400x200 viewport; the board is drawn as 200x200 at x=100, so 20px per cell.
        val dest = letterbox(400f, 200f, pixelWidth(), pixelHeight())!!

        val first = cellAt(dest, 100f, 0f)    // first cell of the board
        val second = cellAt(dest, 299f, 199f) // last cell of the board

        assertTrue(first != null)
        assertEquals(0, first!!.first, "row")
        assertEquals(0, first.second, "column")
        assertTrue(second != null)
        assertEquals(9, second!!.first, "row")
        assertEquals(9, second.second, "column")
    }

    @Test
    @DisplayName("Taps in the letterbox margin report no cell")
    fun tapInMarginIsIgnored() {
        val dest = letterbox(400f, 200f, pixelWidth(), pixelHeight())!!

        assertNull(cellAt(dest, 50f, 100f), "left margin")
        assertNull(cellAt(dest, 350f, 100f), "right margin")
    }

    @Test
    @DisplayName("A zero-sized viewport yields no board rectangle instead of failing")
    fun zeroSizedViewportIsSafe() {
        assertNull(letterbox(0f, 0f, pixelWidth(), pixelHeight()))
    }

    @Test
    @DisplayName("Every point inside the board maps to a valid cell")
    fun everyPointInsideTheBoardMapsWithinBounds() {
        // A tap must never produce an index the board cannot accept: the engine ignores
        // out-of-range toggles, but a row or column of `rows` would silently target nothing.
        // Swept across viewport sizes that do not divide evenly by the cell count, which is
        // where the rounding in the mapping would show up.
        for (extent in intArrayOf(20, 37, 200, 803, 1000)) {
            val dest = letterbox(extent.toFloat(), extent.toFloat(), pixelWidth(), pixelHeight())!!
            var x = dest.left
            while (x < dest.right) {
                val cell = cellAt(dest, x, dest.top)
                assertTrue(
                    cell != null && cell.second in 0 until COLUMNS,
                    "x=$x in a ${extent}px viewport mapped to $cell",
                )
                x += 1f
            }
        }
    }

    @Test
    @DisplayName("Points on and past the far edge report no cell rather than an invalid index")
    fun pastTheLastCellReportsNoCell() {
        // The far edge is exclusive: returning the cell *count* here would be one past the
        // last valid index and would read out of bounds downstream.
        val dest = letterbox(400f, 200f, pixelWidth(), pixelHeight())!!

        assertNull(cellAt(dest, dest.right, dest.top), "the right edge itself")
        assertNull(cellAt(dest, dest.left, dest.bottom), "the bottom edge itself")
        assertNull(cellAt(dest, 5_000f, 100f), "far past the right edge")
        assertNull(cellAt(dest, dest.left - 1f, dest.top), "left of the board")
        assertNull(cellAt(dest, dest.left, dest.top - 1f), "above the board")
    }

    @Test
    @DisplayName("Rendering a sequence incrementally matches rendering the last board from scratch")
    fun incrementalRenderMatchesFullRender() {
        // The renderer updates the buffer as a difference against the previous generation
        // instead of redrawing every cell. The whole optimisation rests on the two paths
        // producing identical pixels, so that is asserted directly.
        val first = generation(cells(0), cells(0))       // (0,0) alive
        val second = generation(cells(11), cells(0, 11)) // (0,0) died, (1,1) alive
        val third = generation(cells(22), cells(11, 22)) // (1,1) died, (2,2) alive, (0,0) reverted

        val incremental = renderSequence(first, second, third)
        val fromScratch = renderSequence(third)

        assertImagesEqual(fromScratch, incremental)
    }

    @Test
    @DisplayName("Incremental and full rendering agree across a long random sequence")
    fun incrementalRenderMatchesFullRenderOverManySteps() {
        // Ever-alive is generated independently of alive, so trail cells and
        // alive-without-trail combinations are both exercised.
        val random = Random(20260731L)
        val incremental = BoardRenderer(ROWS, COLUMNS, CELL_SIZE)

        for (step in 0 until 25) {
            val alive = BooleanArray(ROWS * COLUMNS)
            val everAlive = BooleanArray(ROWS * COLUMNS)
            for (i in alive.indices) {
                alive[i] = random.nextBoolean()
                everAlive[i] = alive[i] || random.nextBoolean()
            }
            val board = Generation.adopt(COLUMNS, ROWS, alive, everAlive, step.toLong())

            val stepped = incremental.render(board)
            val fresh = renderSequence(board)

            assertImagesEqual(fresh, stepped, "step $step")
        }
    }

    @Test
    @DisplayName("Re-rendering the same generation touches nothing")
    fun redrawingAnUnchangedGenerationIsANoOp() {
        // A draw is not always caused by a new generation: a resize or a re-layout repaints
        // a board that has not moved. Without the identity check that costs a full
        // rows x columns diff that writes nothing. Scribbling on the buffer and asserting
        // the scribble survives pins the no-op directly, rather than timing it.
        val view = BoardRenderer(ROWS, COLUMNS, CELL_SIZE)
        val board = generation(cells(0), cells(0))

        val pixels = view.render(board)
        val sentinel: Byte = 0x5A
        pixels[0] = sentinel

        assertEquals(sentinel, view.render(board)[0], "an unchanged generation must not be repainted")

        // A genuinely new generation still repaints, so the fast path cannot swallow one.
        // (0, 0) dies but stays ever-alive, so it must go from alive to the trail colour.
        view.render(generation(cells(11), cells(0, 11)))
        assertEquals(Theme.BOARD_TRAIL, rgb(pixels, 0, 0), "a changed generation must repaint")
    }

    @Test
    @DisplayName("Cell separators survive incremental updates")
    fun separatorsAreNotOverwrittenByIncrementalUpdates() {
        // The full-redraw path clears the buffer to the separator colour; the incremental
        // path must not paint over it when it rewrites a cell.
        val view = BoardRenderer(ROWS, COLUMNS, CELL_SIZE)

        view.render(generation(cells(0), cells(0)))
        val image = view.render(generation(cells(0, 1), cells(0, 1)))

        assertEquals(
            Theme.BOARD_GRID,
            pixelAt(image, CELL_SIZE - 1, CELL_SIZE - 1),
            "separator after an incremental update",
        )
    }

    @Test
    @DisplayName("A board of different dimensions falls back to a full redraw")
    fun differentDimensionsTriggerFullRedraw() {
        val view = BoardRenderer(ROWS, COLUMNS, CELL_SIZE)

        view.render(generation(cells(0), cells(0)))

        // Smaller than the view: the area the board no longer covers must be repainted
        // rather than left showing the previous board.
        val smaller = Generation.empty(COLUMNS / 2, ROWS / 2).withCell(0, 0, true)
        val image = view.render(smaller)

        assertEquals(Theme.BOARD_ALIVE, rgb(image, 0, 0), "the smaller board still draws")
        assertEquals(
            Theme.BOARD_GRID,
            rgb(image, ROWS - 1, COLUMNS - 1),
            "area outside the smaller board is cleared, not stale",
        )
    }

    // ---- Helpers ----

    private val renderer = BoardRenderer(ROWS, COLUMNS, CELL_SIZE)

    private fun pixelWidth(): Int = renderer.pixelWidth

    private fun pixelHeight(): Int = renderer.pixelHeight

    /** Flat cell indices, as `row * COLUMNS + col` is the board's own indexing. */
    private fun cells(vararg indices: Int): IntArray = indices

    private fun generation(aliveIndices: IntArray, everAliveIndices: IntArray): Generation {
        val alive = BooleanArray(ROWS * COLUMNS)
        val everAlive = BooleanArray(ROWS * COLUMNS)
        for (index in aliveIndices) {
            alive[index] = true
        }
        for (index in everAliveIndices) {
            everAlive[index] = true
        }
        return Generation.adopt(COLUMNS, ROWS, alive, everAlive, 0L)
    }

    /** Renders with a fresh renderer: a from-scratch draw of one generation. */
    private fun render(generation: Generation): ByteArray =
        BoardRenderer(ROWS, COLUMNS, CELL_SIZE).render(generation)

    /** Renders each generation in turn into one renderer, returning the final buffer. */
    private fun renderSequence(vararg generations: Generation): ByteArray {
        val view = BoardRenderer(ROWS, COLUMNS, CELL_SIZE)
        var pixels = ByteArray(0)
        for (generation in generations) {
            pixels = view.render(generation)
        }
        return pixels
    }

    /**
     * The pixel at (x, y) recomposed into the packed ARGB form the theme declares, so the
     * assertions read against [Theme] rather than against raw RGBA bytes.
     */
    private fun pixelAt(pixels: ByteArray, x: Int, y: Int, stride: Int = pixelWidth()): Int {
        val offset = (y * stride + x) * 4
        return (pixels[offset + 3].toInt() and 0xFF shl 24) or
            (pixels[offset].toInt() and 0xFF shl 16) or
            (pixels[offset + 1].toInt() and 0xFF shl 8) or
            (pixels[offset + 2].toInt() and 0xFF)
    }

    /** Reads the centre pixel of a cell, clear of the separator. */
    private fun rgb(pixels: ByteArray, row: Int, col: Int): Int = pixelAt(
        pixels,
        col * CELL_SIZE + CELL_SIZE / 2,
        row * CELL_SIZE + CELL_SIZE / 2,
    )

    private fun cellAt(dest: Rect, x: Float, y: Float): Pair<Int, Int>? =
        cellAt(dest, x, y, ROWS, COLUMNS)

    private fun assertImagesEqual(expected: ByteArray, actual: ByteArray, context: String = "") {
        assertEquals(expected.size, actual.size, "buffer size $context")
        for (i in expected.indices) {
            val pixel = i / 4
            assertEquals(
                expected[i], actual[i],
                "byte ${i % 4} of pixel (${pixel % pixelWidth()}, ${pixel / pixelWidth()}) differs $context",
            )
        }
    }
}
