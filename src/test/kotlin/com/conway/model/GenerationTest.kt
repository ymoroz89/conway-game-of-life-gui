package com.conway.model

import com.conway.testsupport.board
import com.conway.testsupport.render
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GenerationTest {

    @Test
    @DisplayName("An empty board has no live cells and is generation zero")
    fun emptyBoard() {
        val board = Generation.empty(5, 3)

        assertEquals(5, board.width)
        assertEquals(3, board.height)
        assertEquals(0, board.population)
        assertEquals(0L, board.number)
        assertFalse(board.isAlive(0, 0))
        assertFalse(board.wasEverAlive(2, 4))
    }

    @Test
    @DisplayName("Non-positive dimensions are rejected")
    fun invalidDimensionsRejected() {
        assertThrows(IllegalArgumentException::class.java) { Generation.empty(0, 5) }
        assertThrows(IllegalArgumentException::class.java) { Generation.empty(5, 0) }
        assertThrows(IllegalArgumentException::class.java) { Generation.empty(-1, 5) }
    }

    @Test
    @DisplayName("Coordinates outside the board are rejected rather than silently wrapping")
    fun outOfBoundsAccessIsRejected() {
        val board = Generation.empty(4, 3)

        assertThrows(IndexOutOfBoundsException::class.java) { board.isAlive(3, 0) }
        assertThrows(IndexOutOfBoundsException::class.java) { board.isAlive(0, 4) }
        assertThrows(IndexOutOfBoundsException::class.java) { board.isAlive(-1, 0) }
        assertThrows(IndexOutOfBoundsException::class.java) { board.withCell(0, -1, true) }
    }

    @Test
    @DisplayName("withCell returns a new board and leaves the original untouched")
    fun withCellDoesNotMutate() {
        val original = Generation.empty(4, 4)
        val modified = original.withCell(1, 2, true)

        assertEquals(0, original.population)
        assertFalse(original.isAlive(1, 2))
        assertEquals(1, modified.population)
        assertTrue(modified.isAlive(1, 2))
        assertTrue(modified.wasEverAlive(1, 2))
    }

    @Test
    @DisplayName("Clearing a cell also clears its ever-alive marker")
    fun removingACellReturnsItToUnpopulated() {
        val board = Generation.empty(3, 3).withCell(1, 1, true).withCell(1, 1, false)

        assertFalse(board.isAlive(1, 1))
        assertFalse(board.wasEverAlive(1, 1))
    }

    @Test
    @DisplayName("A pattern is stamped at the requested offset")
    fun patternIsPlacedAtOffset() {
        val glider = Pattern.parse("glider", listOf(".O.", "..O", "OOO"))
        val board = Generation.empty(6, 6).withPattern(glider, 1, 2)

        assertEquals(
            listOf(
                "......",
                "...O..",
                "....O.",
                "..OOO.",
                "......",
                "......",
            ),
            render(board),
        )
    }

    @Test
    @DisplayName("A pattern that overhangs the board is clipped, not rejected")
    fun patternIsClipped() {
        val block = Pattern.parse("block", listOf("OO", "OO"))

        val bottomRight = Generation.empty(3, 3).withPattern(block, 2, 2)
        assertEquals(1, bottomRight.population)
        assertTrue(bottomRight.isAlive(2, 2))

        val topLeft = Generation.empty(3, 3).withPattern(block, -1, -1)
        assertEquals(1, topLeft.population)
        assertTrue(topLeft.isAlive(0, 0))

        val entirelyOutside = Generation.empty(3, 3).withPattern(block, 50, 50)
        assertEquals(0, entirelyOutside.population)
    }

    @Test
    @DisplayName("adopt rejects arrays that do not match the declared dimensions")
    fun adoptValidatesArrayLength() {
        assertThrows(IllegalArgumentException::class.java) {
            Generation.adopt(3, 3, BooleanArray(8), BooleanArray(9), 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Generation.adopt(3, 3, BooleanArray(9), BooleanArray(8), 0L)
        }
    }

    @Test
    @DisplayName("adopt with a supplied population uses it without scanning")
    fun adoptWithPopulationUsesSuppliedCount() {
        val alive = BooleanArray(9)
        val everAlive = BooleanArray(9)
        alive[0] = true
        alive[4] = true
        everAlive[0] = true
        everAlive[4] = true

        val board = Generation.adopt(3, 3, alive, everAlive, 0L, ActiveRegion.EMPTY, 2)

        assertEquals(2, board.population)
        assertTrue(board.isAlive(0, 0))
        assertTrue(board.isAlive(1, 1))
    }

    @Test
    @DisplayName("adopt with a supplied population rejects a negative count")
    fun adoptWithPopulationRejectsNegative() {
        assertThrows(IllegalArgumentException::class.java) {
            Generation.adopt(3, 3, BooleanArray(9), BooleanArray(9), 0L, ActiveRegion.EMPTY, -1)
        }
    }

    @Test
    @DisplayName("withCell maintains the population incrementally")
    fun withCellMaintainsPopulation() {
        val board = Generation.empty(3, 3)

        val one = board.withCell(1, 1, true)
        assertEquals(1, one.population)

        val two = one.withCell(2, 2, true)
        assertEquals(2, two.population)

        // Turning an already-live cell on again must not double-count.
        val same = two.withCell(2, 2, true)
        assertEquals(2, same.population)

        val back = two.withCell(1, 1, false)
        assertEquals(1, back.population)
    }

    @Test
    @DisplayName("withPattern counts only cells that newly become alive")
    fun withPatternCountsNewlyAliveCells() {
        val block = Pattern.parse("block", listOf("OO", "OO"))
        val board = Generation.empty(4, 4)

        val stamped = board.withPattern(block, 0, 0)
        assertEquals(4, stamped.population)

        // Overlapping stamp: the two cells already alive must not be double-counted.
        val overlapped = stamped.withPattern(block, 1, 1)
        assertEquals(7, overlapped.population)
    }

    @Test
    @DisplayName("Boards with identical contents and generation numbers are equal")
    fun equality() {
        val first = board("OO.", ".O.", "...")
        val second = board("OO.", ".O.", "...")
        val different = board("OO.", ".OO", "...")

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, different)
    }
}
