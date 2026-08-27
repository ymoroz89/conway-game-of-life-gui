package com.conway

import androidx.compose.ui.window.application
import com.conway.config.LifeConfig
import com.conway.ui.App
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.conway.MainKt")

/**
 * Application entry point. Builds the configuration, then hands control to the Compose
 * desktop application, which owns the window, the engine and their lifecycle together.
 */
fun main() {
    // A default handler catches failures on any thread the Compose runtime spawns; without
    // it, failures on the UI thread would disappear silently.
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        LOG.error("Uncaught exception on thread {}", thread.name, error)
    }

    val config = LifeConfig.fromSystemProperties()

    application {
        App(
            config = config,
            onClose = { exitApplication() },
        )
    }
}