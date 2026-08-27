package com.conway.engine

import java.util.concurrent.TimeUnit

/**
 * Throughput measurement for the current run.
 *
 * Timing uses [System.nanoTime] rather than wall-clock milliseconds, and the rate is a
 * [Double], so short runs report a meaningful figure instead of being truncated to zero
 * by integer division.
 *
 * @property generation      total generations computed since the last clear
 * @property generationsInRun generations computed since the run started
 * @property elapsedNanos    nanoseconds elapsed since the run started
 * @property partitions      number of row bands used for the most recent generation
 */
data class SimulationStats(
    val generation: Long,
    val generationsInRun: Long,
    val elapsedNanos: Long,
    val partitions: Int,
) {
    /** Generations per second over the current run, or zero if nothing has been timed yet. */
    val generationsPerSecond: Double
        get() {
            if (elapsedNanos <= 0L || generationsInRun <= 0L) return 0.0
            return generationsInRun * TimeUnit.SECONDS.toNanos(1L).toDouble() / elapsedNanos
        }

    /** Elapsed run time in seconds. */
    val elapsedSeconds: Double
        get() = elapsedNanos / TimeUnit.SECONDS.toNanos(1L).toDouble()

    companion object {
        private val IDLE = SimulationStats(0L, 0L, 0L, 1)

        fun idle(): SimulationStats = IDLE
    }
}