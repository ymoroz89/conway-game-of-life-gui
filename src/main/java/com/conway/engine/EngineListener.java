package com.conway.engine;

/**
 * Receives low-frequency notifications from the simulation engine.
 *
 * <p>Per-generation updates are deliberately <em>not</em> pushed through this
 * interface. The renderer pulls the latest {@link com.conway.model.Generation} on its
 * own schedule, which keeps the simulation rate independent of the frame rate.
 *
 * <p>Callbacks run on the simulation thread. Implementations that touch Swing must
 * marshal to the event dispatch thread themselves.
 */
public interface EngineListener {

    /** Invoked when the engine moves between paused, running, stopped and failed. */
    default void onStateChanged(SimulationEngine.State state) {
    }

    /** Invoked when a generation could not be computed. The engine pauses and enters {@code FAILED}. */
    default void onError(Throwable error) {
    }
}
