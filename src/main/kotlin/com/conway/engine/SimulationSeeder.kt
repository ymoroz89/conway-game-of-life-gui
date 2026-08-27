package com.conway.engine

import com.conway.model.PatternLoader
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/** Convenience for setting up the initial board state. */
object SimulationSeeder {

    private val LOG = LoggerFactory.getLogger(SimulationSeeder::class.java)
    private const val STARTUP_SEED_TIMEOUT_SECONDS = 5L

    /**
     * Places two Gosper glider guns, the second rotated by 180 degrees in the opposite
     * corner, reproducing the historical starting board. Positions are derived from the
     * grid size rather than hard-coded, and `withPattern` clips, so this is safe for any
     * configured grid.
     */
    fun seedBoard(engine: SimulationEngine, gridSize: Int) {
        val gun = PatternLoader.fromResource(PatternLoader.GOSPER_GLIDER_GUN)
        engine.place(gun, 1, 1)
        engine.place(gun.rotate180(), gridSize - gun.height - 1, gridSize - gun.width - 1)

        try {
            // Ensures the first frame already shows the seeded board.
            if (!engine.sync(STARTUP_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOG.warn("Timed out waiting for the initial board to be seeded")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LOG.warn("Interrupted while seeding the initial board")
        }
    }
}