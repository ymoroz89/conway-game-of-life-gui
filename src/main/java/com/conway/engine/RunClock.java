package com.conway.engine;

import com.conway.engine.support.SimulationStats;

/**
 * Throughput accounting for a single run: how many generations have been computed since
 * the user pressed start, and how long that has taken.
 *
 * <p>Split out of {@link SimulationEngine} because measuring a run and owning a board are
 * different reasons to change. Only manual steps versus timed advances, and what counts as
 * the start of a run, are decided here.
 *
 * <h2>Threading</h2>
 * <strong>Not thread-safe, and deliberately so.</strong> An instance is confined to the
 * simulation thread exactly like the board it accompanies, so it needs no synchronization.
 * Values leave that thread only by being copied into an immutable {@link SimulationStats}
 * and published through the engine's {@code volatile} snapshot.
 */
final class RunClock {

    private long generations;
    private long elapsedNanos;
    private long startNanos;

    /** Begins a new measurement window, discarding any previous one. */
    void startRun() {
        generations = 0L;
        elapsedNanos = 0L;
        startNanos = System.nanoTime();
    }

    /**
     * Counts one generation against the current run.
     *
     * <p>Elapsed time is re-read from the clock rather than accumulated per call, so the
     * rate reflects wall time across the whole run and cannot drift.
     */
    void recordGeneration() {
        generations++;
        elapsedNanos = System.nanoTime() - startNanos;
    }

    /** Clears the counters without starting a new window; used when the board is emptied. */
    void reset() {
        generations = 0L;
        elapsedNanos = 0L;
    }

    long generations() {
        return generations;
    }

    long elapsedNanos() {
        return elapsedNanos;
    }
}
