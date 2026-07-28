package com.conway;

import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conway.config.LifeConfig;
import com.conway.engine.SimulationEngine;
import com.conway.model.Pattern;
import com.conway.model.PatternLoader;
import com.conway.ui.LifeFrame;

/** Application entry point: builds the engine, seeds the board and shows the window. */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private static final long STARTUP_SEED_TIMEOUT_SECONDS = 5L;

    private Main() {
    }

    public static void main(String[] args) {
        // Covers the event dispatch thread too: since Java 7 it delegates uncaught
        // exceptions to the default handler. Without this a failure on either the
        // simulation thread or the EDT disappears silently.
        Thread.setDefaultUncaughtExceptionHandler(
                (thread, error) -> LOG.error("Uncaught exception on thread {}", thread.getName(), error));

        LifeConfig config = LifeConfig.fromSystemProperties();
        SimulationEngine engine = new SimulationEngine(
                config.gridSize(), config.gridSize(), config.maxPartitions());

        seedBoard(engine, config.gridSize());

        // Swing components must be created and touched on the event dispatch thread.
        // The previous entry point built the frame on the main thread.
        SwingUtilities.invokeLater(() -> {
            applySystemLookAndFeel();
            LifeFrame frame = new LifeFrame(config, engine);
            frame.setVisible(true);
            frame.startRendering();
            LOG.info("Application started");
        });
    }

    /**
     * Places two Gosper glider guns, the second rotated by 180 degrees in the opposite
     * corner, reproducing the historical starting board. Positions are derived from the
     * grid size rather than hard-coded, and {@code withPattern} clips, so this is safe
     * for any configured grid.
     */
    static void seedBoard(SimulationEngine engine, int gridSize) {
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

    private static void applySystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | UnsupportedOperationException
                 | javax.swing.UnsupportedLookAndFeelException e) {
            LOG.debug("Falling back to the default look and feel", e);
        }
    }
}
