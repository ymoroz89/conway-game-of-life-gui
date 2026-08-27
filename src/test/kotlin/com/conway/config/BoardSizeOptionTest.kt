package com.conway.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class BoardSizeOptionTest {

    @ParameterizedTest(name = "a {0}-cell grid is shown as {1}")
    @CsvSource("201, 200", "401, 400", "801, 800")
    @DisplayName("Presets simulate an odd grid but display the round number")
    fun presetsDisplayRoundNumbers(cells: Int, label: String) {
        val option = BoardSizeOption.find(BoardSizeOption.options(201), cells)

        assertEquals(cells, option.cells, "the simulated grid")
        assertEquals(label, option.label, "what the control shows")
        assertEquals(label, option.toString(), "the control renders toString()")
    }

    @Test
    @DisplayName("The three presets are offered in ascending order")
    fun offersThePresetsInOrder() {
        val options = BoardSizeOption.options(201)

        assertEquals(3, options.size)
        assertEquals(listOf(201, 401, 801), options.map { it.cells })
        assertEquals(listOf("200", "400", "800"), options.map { it.label })
    }

    @Test
    @DisplayName("A grid size set through the system property is offered alongside the presets")
    fun keepsAConfiguredSizeThatIsNotAPreset() {
        // -Dconway.gridSize=1601 must keep working; silently snapping it to a preset would
        // make the property look broken.
        val options = BoardSizeOption.options(1601)

        assertEquals(4, options.size)
        assertEquals(listOf(201, 401, 801, 1601), options.map { it.cells })
        assertEquals(
            "1601",
            BoardSizeOption.find(options, 1601).label,
            "a size with no round equivalent is labelled with its actual value",
        )
    }

    @Test
    @DisplayName("A configured size below the presets sorts to the front")
    fun sortsASmallConfiguredSizeFirst() {
        val options = BoardSizeOption.options(51)

        assertEquals(51, options[0].cells)
        assertEquals(listOf(51, 201, 401, 801), options.map { it.cells })
    }

    @Test
    @DisplayName("A configured size equal to a preset is not duplicated")
    fun doesNotDuplicateAPreset() {
        val options = BoardSizeOption.options(401)

        assertEquals(3, options.size)
        assertEquals(1, options.count { it.cells == 401 })
    }

    @Test
    @DisplayName("The returned list is immutable")
    fun listIsImmutable() {
        val options = BoardSizeOption.options(201)

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (options as MutableList<BoardSizeOption>).add(BoardSizeOption(9, "9"))
        }
    }

    @Test
    @DisplayName("Every offered size can be looked up; unknown sizes are rejected")
    fun findLocatesOfferedSizesOnly() {
        val options = BoardSizeOption.options(201)

        for (option in options) {
            assertTrue(BoardSizeOption.find(options, option.cells) === option)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoardSizeOption.find(options, 12345)
        }
    }

    @Test
    @DisplayName("Non-positive sizes are rejected")
    fun rejectsInvalidSizes() {
        assertThrows(IllegalArgumentException::class.java) { BoardSizeOption.options(0) }
        assertThrows(IllegalArgumentException::class.java) { BoardSizeOption.options(-1) }
        assertThrows(IllegalArgumentException::class.java) { BoardSizeOption(0, "0") }
        // The Java original also asserted that a null label is rejected. Kotlin's non-null
        // types make that input unrepresentable at compile time, so there is nothing left
        // to test: the compiler now provides the guarantee the assertion used to pin.
    }
}
