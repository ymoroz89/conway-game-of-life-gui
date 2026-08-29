package com.conway

import androidx.compose.ui.window.application
import com.conway.config.LifeConfig
import com.conway.ui.App
import com.conway.ui.createEngine
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.conway.MainKt")

/**
 * Application entry point: builds the configuration, creates and seeds the engine, then
 * hands both to the Compose desktop application, which owns the window and the engine's
 * lifecycle from there on.
 */
fun main() {
    // A default handler catches failures on any thread the Compose runtime spawns; without
    // it, failures on the UI thread would disappear silently.
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        LOG.error("Uncaught exception on thread {}", thread.name, error)
    }

    val config = LifeConfig.fromSystemProperties()
    // Built here, not inside the composition. Creating an engine starts a simulation thread
    // and a worker executor and then blocks until the seeded board has been applied; none
    // of that belongs in a phase Compose is free to skip, restart or abandon.
    val engine = createEngine(config.gridSize, config)

    LOG.info("Application started")
    application {
        App(
            config = config,
            initialEngine = engine,
            onClose = { exitApplication() },
        )
    }
}