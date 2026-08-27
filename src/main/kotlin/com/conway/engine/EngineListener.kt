package com.conway.engine

/**
 * Receives low-frequency notifications from the simulation engine.
 *
 * Per-generation updates are deliberately *not* pushed through this interface. The
 * renderer pulls the latest [Generation][com.conway.model.Generation] on its own
 * schedule, which keeps the simulation rate independent of the frame rate.
 *
 * Callbacks run on the simulation thread. Implementations that touch UI state must
 * marshal to the UI thread themselves.
 */
interface EngineListener {
    /** Invoked when the engine moves between paused, running, stopped and failed. */
    fun onStateChanged(state: SimulationEngine.State) = Unit

    /** Invoked when a generation could not be computed. The engine enters [SimulationEngine.State.FAILED]. */
    fun onError(error: Throwable) = Unit
}