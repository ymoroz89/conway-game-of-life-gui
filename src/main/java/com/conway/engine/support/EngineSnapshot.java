package com.conway.engine.support;

import com.conway.engine.SimulationEngine;

import com.conway.model.Generation;

/**
 * The board and its statistics as one immutable bundle.
 *
 * <p>This is the single publication point for simulation results: the engine publishes
 * a generation together with the statistics that belong to it through one
 * {@code volatile} field, so a reader can never observe a new board with stale counters
 * or vice versa. All fields are {@code final} and the record is immutable, so publishing
 * an instance through a {@code volatile} reference gives every reader a fully
 * initialised, internally consistent bundle without any locking.
 *
 * <p>Lifecycle {@link SimulationEngine.State} is deliberately <em>not</em> part of this
 * record: it is written by two threads (the simulation thread and {@code close()} on the
 * caller's thread) and bundling it here would let a concurrent close overwrite a newer
 * generation when re-publishing the whole bundle. Momentary skew between a low-frequency
 * latch and the board is benign; the incoherence that matters — generation number versus
 * its population — is what this type eliminates.
 *
 * @param generation the most recently published board
 * @param stats      the throughput measurement that belongs to {@code generation}
 */
public record EngineSnapshot(Generation generation, SimulationStats stats) {

    public static EngineSnapshot initial(int width, int height) {
        return new EngineSnapshot(Generation.empty(width, height), SimulationStats.idle());
    }
}