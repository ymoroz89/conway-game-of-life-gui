package com.conway.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class PartitionPlannerTest {

    /** The cap on the machine the constant was calibrated against: 10 cores, 2x oversubscribed. */
    private val cap = 20

    @ParameterizedTest(name = "a {0}-row board is best split {1} ways")
    @CsvSource(
        "201, 4",
        "401, 8",
        "801, 16",
        "1601, 20",
    )
    @DisplayName("The recommendation reproduces the partition count each board measured fastest at")
    fun matchesMeasuredOptimum(rows: Int, expected: Int) {
        // These are the measured optima from GenerationBenchmark, not restatements of the
        // formula. If the heuristic is retuned it must still land on the fastest count, so
        // this test is what stops the constant drifting away from the evidence for it.
        assertEquals(expected, PartitionPlanner.recommend(rows, cap))
    }

    @ParameterizedTest(name = "a {0}-row board stays single-threaded")
    @ValueSource(ints = [1, 2, 10, 49])
    @DisplayName("A board too small to give any band enough work is not split at all")
    fun tinyBoardsRunOnOneThread(rows: Int) {
        assertEquals(
            1, PartitionPlanner.recommend(rows, cap),
            "splitting this board costs more in dispatch than the work is worth",
        )
    }

    @Test
    @DisplayName("The recommendation never exceeds the cap, however large the board")
    fun neverExceedsTheCap() {
        assertEquals(4, PartitionPlanner.recommend(10_000, 4))
        assertEquals(1, PartitionPlanner.recommend(10_000, 1), "a single-partition cap must still yield a usable value")
    }

    @Test
    @DisplayName("The rows-per-band threshold is honoured when supplied explicitly")
    fun honoursExplicitThreshold() {
        assertEquals(20, PartitionPlanner.recommend(201, cap, 10))
        assertEquals(2, PartitionPlanner.recommend(201, cap, 100))
        assertEquals(1, PartitionPlanner.recommend(201, cap, 500))
    }

    @Test
    @DisplayName("The result is always a usable partition count")
    fun resultIsAlwaysInRange() {
        for (rows in 1..2000) {
            val partitions = PartitionPlanner.recommend(rows, cap)
            assertTrue(partitions in 1..cap, "out of range for $rows rows: $partitions")
            assertTrue(partitions <= rows, "more bands than rows for $rows rows")
        }
    }

    @Test
    @DisplayName("More rows never recommends fewer partitions")
    fun isMonotonicInBoardSize() {
        var previous = 0
        for (rows in 1..2000) {
            val partitions = PartitionPlanner.recommend(rows, cap)
            assertTrue(partitions >= previous, "recommendation fell from $previous to $partitions at $rows")
            previous = partitions
        }
    }

    @Test
    @DisplayName("Non-positive arguments are rejected rather than silently clamped")
    fun rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException::class.java) { PartitionPlanner.recommend(0, cap) }
        assertThrows(IllegalArgumentException::class.java) { PartitionPlanner.recommend(-1, cap) }
        assertThrows(IllegalArgumentException::class.java) { PartitionPlanner.recommend(201, 0) }
        assertThrows(IllegalArgumentException::class.java) { PartitionPlanner.recommend(201, cap, 0) }
        assertThrows(IllegalArgumentException::class.java) { PartitionPlanner.hardwareCap(0) }
    }

    @Test
    @DisplayName("The hardware cap is positive, respects the board and allows oversubscription")
    fun hardwareCapIsSane() {
        val cap = PartitionPlanner.hardwareCap(201)
        assertTrue(cap >= 2, "cap should permit at least two bands: $cap")
        assertTrue(cap <= 201, "cap should never exceed the row count: $cap")
        assertEquals(4, PartitionPlanner.hardwareCap(4), "a tiny board caps at its own row count")
    }
}
