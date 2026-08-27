package com.conway.engine

import com.conway.model.Generation

/**
 * The board and its statistics as one immutable bundle.
 *
 * This is the single publication point for simulation results: the engine publishes a
 * generation together with the statistics that belong to it through one `@Volatile`
 * field, so a reader can never observe a new board with stale counters or vice versa.
 *
 * Lifecycle [SimulationEngine.State] is deliberately *not* part of this record: it is
 * written by two threads (the simulation thread and [SimulationEngine.close] on the
 * caller's thread) and bundling it here would let a concurrent close overwrite a newer
 * generation when re-publishing the whole bundle.
 *
 * @property generation the most recently published board
 * @property stats      the throughput measurement that belongs to [generation]
 */
data class EngineSnapshot(val generation: Generation, val stats: SimulationStats) {
    companion object {
        internal fun initial(width: Int, height: Int): EngineSnapshot =
            EngineSnapshot(Generation.empty(width, height), SimulationStats.idle())
    }
}