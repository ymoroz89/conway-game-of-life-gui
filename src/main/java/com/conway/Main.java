package com.conway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conway.config.LifeConfig;
import com.conway.engine.SimulationEngine;
import com.conway.engine.SimulationSeeder;
import com.conway.ui.LifeGuiLauncher;

/** Application entry point: builds the engine, seeds the board and shows the window. */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

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

        SimulationSeeder.seedBoard(engine, config.gridSize());

        LifeGuiLauncher.launch(config, engine);
        LOG.info("Application started");
    }
}
