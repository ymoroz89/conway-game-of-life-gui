package com.conway.engine

import com.conway.model.Generation
import com.conway.testsupport.board
import com.conway.testsupport.random
import com.conway.testsupport.render
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class LifeRulesTest {
    companion object {
        private lateinit var executor: ExecutorService
        private lateinit var rules: LifeRules

        private const val INJECTED_FAILURE_MESSAGE = "injected band failure"

        @BeforeAll
        @JvmStatic
        fun setUp() {
            executor = Executors.newFixedThreadPool(4)
            rules = LifeRules(executor)
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            // ExecutorService has been AutoCloseable since Java 19: close() shuts the pool
            // down and waits for it to terminate, replacing the manual shutdown block.
            executor.close()
        }
    }

    @Test
    @DisplayName("Ever-alive flags survive the board dying out")
    fun everAliveSurvivesExtinction() {
        // A lone cell dies at once, leaving an empty board and an empty active region. The
        // flags are documented as monotonic within a run and are what the renderer draws as
        // "died here", so an empty board must not erase them. The early return for an empty
        // region used to hand back a freshly zeroed array, wiping every trail the moment the
        // population reached zero.
        var board = Generation.empty(8, 8).withCell(3, 3, true)

        for (i in 0 until 3) {
            board = rules.next(board, 1)
        }

        assertEquals(0, board.population, "the cell should have died of underpopulation")
        assertTrue(board.wasEverAlive(3, 3), "its trail must outlive it")
    }

    @Test
    @DisplayName("Ever-alive flags accumulate across generations")
    fun everAliveAccumulates() {
        // A glider moves, so cells it has left must stay marked while new ones are added.
        val start = board(
            ".O........",
            "..O.......",
            "OOO.......",
            "..........",
            "..........",
            "..........",
        )

        var later = start
        for (i in 0 until 4) {
            later = rules.next(later, 2)
        }

        assertTrue(later.wasEverAlive(2, 0), "a cell the glider has vacated stays marked")
        assertTrue(later.population > 0, "the glider is still alive")
        for (row in 0 until later.height) {
            for (col in 0 until later.width) {
                if (start.wasEverAlive(row, col)) {
                    assertTrue(later.wasEverAlive(row, col), "flag lost at ($row, $col)")
                }
            }
        }
    }

    @Test
    @DisplayName("A block is a still life")
    fun blockIsStable() {
        val start = board(
            "....",
            ".OO.",
            ".OO.",
            "....",
        )

        assertEquals(render(start), render(rules.next(start, 1)))
    }

    @Test
    @DisplayName("A blinker oscillates with period two")
    fun blinkerOscillates() {
        val vertical = board(
            ".....",
            "..O..",
            "..O..",
            "..O..",
            ".....",
        )
        val horizontal = listOf(
            ".....",
            ".....",
            ".OOO.",
            ".....",
            ".....",
        )

        val afterOne = rules.next(vertical, 1)
        assertEquals(horizontal, render(afterOne))
        assertEquals(render(vertical), render(rules.next(afterOne, 1)))
    }

    @Test
    @DisplayName("A glider translates by one cell diagonally every four generations")
    fun gliderTranslates() {
        var result = board(
            ".O........",
            "..O.......",
            "OOO.......",
            "..........",
            "..........",
            "..........",
            "..........",
            "..........",
            "..........",
            "..........",
        )
        repeat(4) {
            result = rules.next(result, 1)
        }

        assertEquals(
            listOf(
                "..........",
                "..O.......",
                "...O......",
                ".OOO......",
                "..........",
                "..........",
                "..........",
                "..........",
                "..........",
                "..........",
            ),
            render(result),
        )
    }

    @Test
    @DisplayName("An isolated cell dies of underpopulation")
    fun lonelyCellDies() {
        val start = board(
            "...",
            ".O.",
            "...",
        )

        assertEquals(0, rules.next(start, 1).population)
    }

    @Test
    @DisplayName("Overcrowded cells die")
    fun overcrowdedCellDies() {
        val start = board(
            "OOO",
            "OOO",
            "OOO",
        )

        // Only the four corners survive: each has exactly three neighbours.
        assertEquals(
            listOf(
                "O.O",
                "...",
                "O.O",
            ),
            render(rules.next(start, 1)),
        )
    }

    @Test
    @DisplayName("The grid does not wrap: neighbours off the edge count as dead")
    fun bordersDoNotWrap() {
        // Were the board toroidal, these three corner cells would each see enough
        // neighbours to survive and to breed a fourth cell.
        val start = board(
            "O.O",
            "...",
            "O.O",
        )

        assertEquals(0, rules.next(start, 1).population)
    }

    @Test
    @DisplayName("A block straddling the corner survives, confirming border cells are evaluated")
    fun cornerBlockSurvives() {
        val start = board(
            "OO..",
            "OO..",
            "....",
            "....",
        )

        assertEquals(render(start), render(rules.next(start, 1)))
    }

    @Test
    @DisplayName("The generation counter increments")
    fun generationNumberIncrements() {
        val start = board("...", ".O.", "...")

        assertEquals(0L, start.number)
        assertEquals(1L, rules.next(start, 1).number)
        assertEquals(2L, rules.next(rules.next(start, 1), 1).number)
    }

    @Test
    @DisplayName("Cells that have died remain marked as previously alive")
    fun everAliveIsRetainedAfterDeath() {
        val start = board(
            "...",
            ".O.",
            "...",
        )

        val next = rules.next(start, 1)
        assertFalse(next.isAlive(1, 1))
        assertTrue(next.wasEverAlive(1, 1))
        assertFalse(next.wasEverAlive(0, 0))
    }

    @Test
    @DisplayName("The population count matches the rendered board across generations and partition counts")
    fun populationMatchesRenderedBoard() {
        // The population is folded into the ever-alive pass and summed across bands, so it
        // must agree with a direct count of the rendered board at every partition count.
        val start = board(
            ".O........",
            "..O.......",
            "OOO.......",
            "..........",
            "..........",
            "..........",
        )

        for (partitions in intArrayOf(1, 2, 4)) {
            var current = start
            for (i in 0 until 6) {
                current = rules.next(current, partitions)
                var expected = 0
                for (row in render(current)) {
                    for (ch in row) {
                        if (ch == 'O') expected++
                    }
                }
                assertEquals(
                    expected, current.population,
                    "population with $partitions partition(s) at generation $i",
                )
            }
        }
    }

    @Test
    fun optimizedMatchesReference() {
        val width = 50
        val height = 50
        val initial = BooleanArray(width * height)
        // Blinker
        initial[10 * width + 10] = true
        initial[11 * width + 10] = true
        initial[12 * width + 10] = true

        val gen0 = Generation.adopt(width, height, initial, initial.copyOf(), 0L)
        val gen1 = rules.next(gen0, 1)

        // Expected blinker horizontal
        assertTrue(gen1.isAlive(11, 9))
        assertTrue(gen1.isAlive(11, 10))
        assertTrue(gen1.isAlive(11, 11))
        assertFalse(gen1.isAlive(10, 10))
        assertFalse(gen1.isAlive(12, 10))

        // Check wasEverAlive
        assertTrue(gen1.wasEverAlive(10, 10))
        assertTrue(gen1.wasEverAlive(11, 9))
        assertTrue(gen1.wasEverAlive(11, 10))
        assertTrue(gen1.wasEverAlive(11, 11))
        assertTrue(gen1.wasEverAlive(12, 10))
    }

    @Test
    @DisplayName("A failing dispatched band is reported as SimulationException with the worker's own cause")
    fun failingDispatchedBandSurfacesWrapped() {
        // Band zero runs on the calling thread and cannot be made to fail from outside,
        // so the injection hits every dispatched band. The contract this pins is the one
        // the engine relies on: however a generation fails, it escapes as a
        // SimulationException whose cause is the worker's own exception, so the engine's
        // single failure path produces the same diagnostic no matter which band broke.
        failingBandExecutor().use { failing ->
            val failingRules = LifeRules(failing)
            // Dense and tall enough that the requested count is honoured: 120 active rows
            // allow two bands of 50+ rows, so exactly one task is dispatched and one runs
            // inline, and the dispatched one fails.
            val start = random(120, 120, Random(42L), 0.35)

            val failure = assertThrows(SimulationException::class.java) {
                failingRules.next(start, 4)
            }

            assertEquals(INJECTED_FAILURE_MESSAGE, failure.cause?.message)
            assertInstanceOf(IllegalStateException::class.java, failure.cause)
        }
    }

    /**
     * An executor whose workers always fail, so any dispatched band throws on arrival.
     *
     * `LifeRules.next()` dispatches with `executor.submit(Callable)`; replacing the task
     * with one that throws simulates a worker crashing inside `computeBand` while keeping
     * the failure on the worker thread, which is what exercises the `ExecutionException`
     * wrapping path.
     */
    private fun failingBandExecutor(): ExecutorService =
        object : ThreadPoolExecutor(
            0, 4, 1L, TimeUnit.MINUTES, SynchronousQueue<Runnable>(),
        ) {
            override fun <T : Any> submit(task: Callable<T>): Future<T> =
                super.submit(Callable<T> { throw IllegalStateException(INJECTED_FAILURE_MESSAGE) })
        }
}
