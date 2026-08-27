package com.conway.engine

import com.conway.model.Generation
import com.conway.testsupport.random
import com.conway.testsupport.render
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The central safety net for the parallel rules engine.
 *
 * Partitioning is purely a work-distribution concern: the board that comes out must not
 * depend on how many bands the work was split into. Asserting that property directly
 * catches off-by-one errors at band boundaries, rows that are skipped or computed twice,
 * and any accidental sharing of mutable state between workers — the exact class of defect
 * that partition arithmetic has historically produced here.
 */
class PartitionEquivalenceTest {
    companion object {
        private const val BOARD_SIZE = 37
        private const val GENERATIONS = 12
        private const val SEED = 20_260_728L

        private lateinit var executor: ExecutorService
        private lateinit var rules: LifeRules

        @BeforeAll
        @JvmStatic
        fun setUp() {
            // ExecutorService has been AutoCloseable since Java 19: close() shuts the pool
            // down and waits for it to terminate, replacing the manual shutdown block.
            executor = Executors.newFixedThreadPool(8)
            rules = LifeRules(executor)
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            executor.close()
        }

        private fun seed(): Generation = random(BOARD_SIZE, BOARD_SIZE, Random(SEED), 0.35)

        private fun singleThreadedRun(): List<Generation> {
            val history = ArrayList<Generation>(GENERATIONS)
            var board = seed()
            for (generation in 0 until GENERATIONS) {
                board = rules.next(board, 1)
                history.add(board)
            }
            return history
        }
    }

    @ParameterizedTest(name = "{0} partitions produce the single-threaded result")
    @ValueSource(ints = [2, 3, 4, 5, 7, 8, 16, 36, 37])
    fun partitioningDoesNotChangeTheResult(partitions: Int) {
        val expected = singleThreadedRun()

        var actual = seed()
        for (generation in 0 until GENERATIONS) {
            actual = rules.next(actual, partitions)
            assertEquals(
                render(expected[generation]), render(actual),
                "Diverged from the single-threaded result at generation ${generation + 1} " +
                    "with $partitions partitions",
            )
        }
    }

    @Test
    @DisplayName("Requesting more partitions than there are rows is clamped, not broken")
    fun partitionsAreClampedToRowCount() {
        val expected = singleThreadedRun()

        var actual = seed()
        for (generation in 0 until GENERATIONS) {
            actual = rules.next(actual, BOARD_SIZE * 10)
            assertEquals(
                render(expected[generation]), render(actual),
                "Oversized partition count diverged at generation ${generation + 1}",
            )
        }
    }

    @Test
    @DisplayName("Zero and negative partition counts fall back to a single band")
    fun nonPositivePartitionCountsAreClamped() {
        val start = seed()
        val baseline = rules.next(start, 1)

        assertEquals(render(baseline), render(rules.next(start, 0)))
        assertEquals(render(baseline), render(rules.next(start, -4)))
    }

    @Test
    @DisplayName("Every row is computed exactly once, including on a non-square board")
    fun nonSquareBoardsPartitionCorrectly() {
        val start = random(23, 41, Random(SEED), 0.35)

        var expected = start
        var actual = start
        for (generation in 0 until GENERATIONS) {
            expected = rules.next(expected, 1)
            actual = rules.next(actual, 6)
            assertEquals(
                render(expected), render(actual),
                "Non-square board diverged at generation ${generation + 1}",
            )
        }
    }
}
