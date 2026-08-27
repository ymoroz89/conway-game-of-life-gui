package com.conway.engine

import com.conway.model.ActiveRegion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Band selection. Correctness of the resulting board is covered by
 * `PartitionEquivalenceTest`; this pins down how much parallelism is used, which is a
 * performance decision the board cannot reveal.
 */
class LifeRulesBandingTest {

    /** Spans `rows` rows starting at the top; columns are irrelevant to banding. */
    private fun spanning(rows: Int): ActiveRegion = ActiveRegion(0, rows - 1, 0, 10)

    @ParameterizedTest(name = "{0} active rows with {1} requested -> {2} band(s)")
    @CsvSource(
        // Too little work to be worth splitting: run inline.
        "1, 4, 1",
        "5, 16, 1",
        "49, 16, 1",
        // Exactly enough for a second band at 50 rows each.
        "100, 16, 2",
        "200, 16, 4",
        "800, 16, 16",
        // Never more bands than were requested.
        "800, 4, 4",
        "800, 1, 1",
    )
    @DisplayName("Bands are capped by the rows that actually carry work")
    fun bandsAreCappedByActiveRows(activeRows: Int, requested: Int, expected: Int) {
        assertEquals(expected, LifeRules.bandsFor(requested, 1000, spanning(activeRows)))
    }

    @Test
    @DisplayName("A sparse board on a large grid is computed inline rather than split")
    fun sparseBoardRunsInline() {
        // The case that regressed: the partition count is sized for an 801-row board, but a
        // blinker occupies five rows. Sixteen bands there ran 5.4x slower than inline.
        assertEquals(1, LifeRules.bandsFor(16, 801, spanning(5)))
    }

    @Test
    @DisplayName("A dense board still uses every requested band")
    fun denseBoardUsesEveryRequestedBand() {
        assertEquals(16, LifeRules.bandsFor(16, 801, spanning(801)))
        assertEquals(4, LifeRules.bandsFor(4, 201, spanning(201)))
    }

    @Test
    @DisplayName("An empty region and degenerate inputs still yield a usable band count")
    fun degenerateInputsYieldOneBand() {
        assertEquals(1, LifeRules.bandsFor(16, 801, ActiveRegion.EMPTY))
        assertEquals(1, LifeRules.bandsFor(0, 801, spanning(800)), "zero partitions")
        assertEquals(1, LifeRules.bandsFor(-5, 801, spanning(800)), "negative partitions")
    }

    @Test
    @DisplayName("The band count never exceeds the board height or the request")
    fun bandsStayWithinBounds() {
        for (height in 1..200) {
            for (requested in intArrayOf(1, 2, 7, 64)) {
                val bands = LifeRules.bandsFor(requested, height, spanning(maxOf(1, height)))
                assertTrue(bands >= 1, "at least one band")
                assertTrue(bands <= requested || requested < 1, "never more than requested")
                assertTrue(bands <= height, "never more bands than rows")
            }
        }
    }
}
