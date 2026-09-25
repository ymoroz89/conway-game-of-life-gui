package com.conway.engine.support;

import java.util.concurrent.TimeUnit;

/**
 * Throughput measurement for the current run.
 *
 * <p>Timing uses {@link System#nanoTime()} rather than wall-clock milliseconds, and
 * the rate is a {@code double}, so short runs report a meaningful figure instead of
 * being truncated to zero by integer division.
 *
 * @param generation      total generations computed since the last clear
 * @param generationsInRun generations computed since the run started
 * @param elapsedNanos    nanoseconds elapsed since the run started
 * @param partitions      number of row bands used for the most recent generation
 */
public record SimulationStats(long generation, long generationsInRun, long elapsedNanos, int partitions) {

    private static final SimulationStats IDLE = new SimulationStats(0L, 0L, 0L, 1);

    public static SimulationStats idle() {
        return IDLE;
    }

    /** Generations per second over the current run, or zero if nothing has been timed yet. */
    public double generationsPerSecond() {
        if (elapsedNanos <= 0L || generationsInRun <= 0L) {
            return 0.0d;
        }
        return generationsInRun * (double) TimeUnit.SECONDS.toNanos(1L) / elapsedNanos;
    }

    /** Elapsed run time in seconds. */
    public double elapsedSeconds() {
        return elapsedNanos / (double) TimeUnit.SECONDS.toNanos(1L);
    }
}
