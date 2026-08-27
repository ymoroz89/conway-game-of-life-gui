package com.conway.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PatternTest {

    @Test
    @DisplayName("Plaintext rows are parsed into cells")
    fun parsesPlaintext() {
        val glider = Pattern.parse("glider", listOf(".O.", "..O", "OOO"))

        assertEquals("glider", glider.name)
        assertEquals(3, glider.width)
        assertEquals(3, glider.height)
        assertEquals(5, glider.population)
        assertTrue(glider.isAlive(0, 1))
        assertFalse(glider.isAlive(0, 0))
        assertTrue(glider.isAlive(2, 0))
    }

    @Test
    @DisplayName("Comment lines are ignored")
    fun ignoresComments() {
        val pattern = Pattern.parse(
            "commented",
            listOf("!Name: something", "!author", "OO", "OO"),
        )

        assertEquals(2, pattern.width)
        assertEquals(2, pattern.height)
        assertEquals(4, pattern.population)
    }

    @Test
    @DisplayName("Short rows are padded with dead cells")
    fun padsShortRows() {
        val pattern = Pattern.parse("ragged", listOf("O", "OOOO", "OO"))

        assertEquals(4, pattern.width)
        assertEquals(3, pattern.height)
        assertEquals(7, pattern.population)
        assertFalse(pattern.isAlive(0, 3))
        assertTrue(pattern.isAlive(1, 3))
    }

    @Test
    @DisplayName("Spaces count as dead cells")
    fun treatsSpacesAsDead() {
        val pattern = Pattern.parse("spaced", listOf("O O", "   "))

        assertEquals(2, pattern.population)
        assertFalse(pattern.isAlive(0, 1))
    }

    @Test
    @DisplayName("Input without cell rows is rejected")
    fun rejectsEmptyInput() {
        assertThrows(IllegalArgumentException::class.java) {
            Pattern.parse("empty", emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            Pattern.parse("comments-only", listOf("!just a comment"))
        }
    }

    @Test
    @DisplayName("Rotating by 180 degrees reverses both axes")
    fun rotate180() {
        val original = Pattern.parse("L", listOf("O..", "O..", "OO."))
        val rotated = original.rotate180()

        assertEquals(original.width, rotated.width)
        assertEquals(original.height, rotated.height)
        assertEquals(original.population, rotated.population)

        assertEquals(listOf(".OO", "..O", "..O"), rows(rotated))
    }

    @Test
    @DisplayName("Rotating twice returns the original arrangement")
    fun rotateIsInvolution() {
        val original = Pattern.parse("glider", listOf(".O.", "..O", "OOO"))

        assertEquals(rows(original), rows(original.rotate180().rotate180()))
    }

    @Test
    @DisplayName("Out-of-range cell access is rejected")
    fun rejectsOutOfRangeAccess() {
        val pattern = Pattern.parse("block", listOf("OO", "OO"))

        assertThrows(IndexOutOfBoundsException::class.java) { pattern.isAlive(2, 0) }
        assertThrows(IndexOutOfBoundsException::class.java) { pattern.isAlive(0, -1) }
    }

    private fun rows(pattern: Pattern): List<String> =
        List(pattern.height) { row ->
            buildString(pattern.width) {
                for (col in 0 until pattern.width) {
                    append(if (pattern.isAlive(row, col)) 'O' else '.')
                }
            }
        }
}
