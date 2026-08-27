package com.conway

import com.conway.engine.SimulationEngine
import com.conway.engine.SimulationSeeder
import com.conway.model.Generation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Covers the start-up board.
 *
 * Placement used to be a block of hard-coded indices that assumed a 201-cell grid
 * and threw `ArrayIndexOutOfBoundsException` on anything smaller. Positions are
 * now derived from the grid size, so both the historical layout and small grids need
 * to be checked.
 */
class MainSeedingTest {

    companion object {
        private const val DEFAULT_GRID = 201
    }

    @Test
    @DisplayName("The default grid is seeded with two guns in the historical positions")
    fun seedsTwoGunsOnTheDefaultGrid() {
        SimulationEngine(DEFAULT_GRID, DEFAULT_GRID, 2).use { engine ->
            SimulationSeeder.seedBoard(engine, DEFAULT_GRID)
            assertTrue(engine.sync(10, TimeUnit.SECONDS))

            val board = engine.currentGeneration()
            // Two Gosper glider guns, 36 live cells each, no overlap.
            assertEquals(72, board.population)

            // Top-left gun: the pair at the pattern's rows 4-5, columns 0-1, offset by (1, 1).
            assertTrue(board.isAlive(5, 1))
            assertTrue(board.isAlive(5, 2))
            assertTrue(board.isAlive(6, 1))
            assertTrue(board.isAlive(6, 2))

            // Bottom-right gun, rotated 180 degrees into the opposite corner.
            assertTrue(board.isAlive(195, 199))
            assertTrue(board.isAlive(195, 198))
            assertTrue(board.isAlive(194, 199))
            assertTrue(board.isAlive(194, 198))
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("The seeded default board actually runs and emits gliders")
    fun seededBoardRunsAndGrows() {
        SimulationEngine(DEFAULT_GRID, DEFAULT_GRID, 4).use { engine ->
            SimulationSeeder.seedBoard(engine, DEFAULT_GRID)
            engine.setPartitions(4)
            assertTrue(engine.sync(10, TimeUnit.SECONDS))

            val initialPopulation = engine.currentGeneration().population

            // A glider gun emits one glider every 30 generations, so after 60 the
            // population must have grown. This exercises the full 201x201 board
            // through the parallel path end to end.
            for (i in 0 until 60) {
                engine.step()
            }
            assertTrue(engine.sync(30, TimeUnit.SECONDS))

            val board = engine.currentGeneration()
            assertEquals(60L, board.number)
            assertTrue(board.population > initialPopulation,
                "Expected the guns to emit gliders, but population went from " +
                    "$initialPopulation to ${board.population}")
            assertNotEquals(SimulationEngine.State.FAILED, engine.state)
        }
    }

    @Test
    @DisplayName("A grid too small for the guns is seeded without failing")
    fun smallGridIsSeededWithoutFailing() {
        SimulationEngine(12, 12, 2).use { engine ->
            SimulationSeeder.seedBoard(engine, 12)
            assertTrue(engine.sync(10, TimeUnit.SECONDS))

            // Clipped rather than thrown: the engine is still usable.
            assertNotEquals(SimulationEngine.State.FAILED, engine.state)
            engine.step()
            assertTrue(engine.sync(10, TimeUnit.SECONDS))
            assertEquals(1L, engine.currentGeneration().number)
        }
    }
}
