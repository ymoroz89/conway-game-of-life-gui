package com.conway.engine

/**
 * Chooses how many row bands a generation should be split across.
 *
 * The obvious default, `availableProcessors()`, is wrong for this workload. A generation
 * is a barrier: every band must finish before the next generation starts, so the cost of
 * splitting is paid per band per generation while the benefit only appears once a band
 * carries enough rows to outweigh dispatch and join overhead. On a small board that
 * trade never pays off, and requesting one band per core is measurably slower than
 * requesting four.
 *
 * The optimum tracks the *board*, not the core count: at every size the best result lands
 * at approximately fifty rows per band. That single invariant is the rule implemented
 * here.
 *
 * The cap deliberately permits more bands than cores. On asymmetric cores an efficiency
 * core running an equal share of the work delays the barrier for everyone; smaller bands
 * let the faster cores absorb proportionally more and cut that straggler cost.
 *
 * The constant is empirical and was calibrated on one machine and one JDK. Both the
 * result and the constant can be overridden; see `conway.partitions` and
 * `conway.minRowsPerPartition`.
 */
object PartitionPlanner {
    /** Rows a band needs before splitting it again pays for itself. */
    const val DEFAULT_MIN_ROWS_PER_PARTITION = 50

    /** Recommends a partition count for a board of [rows] rows. */
    fun recommend(rows: Int, maxPartitions: Int): Int =
        recommend(rows, maxPartitions, DEFAULT_MIN_ROWS_PER_PARTITION)

    /**
     * Recommends a partition count using an explicit rows-per-band threshold.
     *
     * @throws IllegalArgumentException if any argument is not positive
     */
    fun recommend(rows: Int, maxPartitions: Int, minRowsPerPartition: Int): Int {
        require(rows > 0) { "rows must be positive but was $rows" }
        require(maxPartitions > 0) { "maxPartitions must be positive but was $maxPartitions" }
        require(minRowsPerPartition > 0) {
            "minRowsPerPartition must be positive but was $minRowsPerPartition"
        }
        val byWorkload = rows / minRowsPerPartition
        return byWorkload.coerceIn(1, maxPartitions)
    }

    /**
     * The upper bound the machine supports: twice the core count, never more than the
     * number of rows. Oversubscription is intentional, for the straggler reason above.
     */
    fun hardwareCap(rows: Int): Int {
        require(rows > 0) { "rows must be positive but was $rows" }
        val cores = Runtime.getRuntime().availableProcessors()
        return minOf(rows, maxOf(2, cores * 2))
    }
}