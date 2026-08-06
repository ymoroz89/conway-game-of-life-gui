package com.conway.engine;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conway.model.Pattern;
import com.conway.model.PatternLoader;

/**
 * Convenience for setting up the initial board state.
 */
public final class SimulationSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(SimulationSeeder.class);
    private static final long STARTUP_SEED_TIMEOUT_SECONDS = 5L;

    private SimulationSeeder() {
    }

    /**
     * Places two Gosper glider guns, the second rotated by 180 degrees in the opposite
     * corner, reproducing the historical starting board. Positions are derived from the
     * grid size rather than hard-coded, and {@code withPattern} clips, so this is safe
     * for any configured grid.
     */
    public static void seedBoard(SimulationEngine engine, int gridSize) {
        Pattern gun = PatternLoader.fromResource(PatternLoader.GOSPER_GLIDER_GUN);
        engine.place(gun, 1, 1);
        engine.place(gun.rotate180(),
                gridSize - gun.height() - 1,
                gridSize - gun.width() - 1);

        try {
            // Ensures the first frame already shows the seeded board.
            if (!engine.sync(STARTUP_SEED_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOG.warn("Timed out waiting for the initial board to be seeded");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while seeding the initial board");
        }
    }
}
