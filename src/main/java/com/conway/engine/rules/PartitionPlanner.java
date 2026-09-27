package com.conway.engine.rules;

import com.conway.model.ActiveRegion;

/**
 * Chooses how many row bands a generation should be split across.
 *
 * <p>The obvious default, {@code availableProcessors()}, is wrong for this workload.
 * A generation is a barrier: every band must finish before the next generation starts,
 * so the cost of splitting is paid per band per generation while the benefit only
 * appears once a band carries enough rows to outweigh dispatch and join overhead. On a
 * small board that trade never pays off, and requesting one band per core is measurably
 * slower than requesting four.
 *
 * <p>Measured with {@code com.conway.bench.GenerationBenchmark} on a 10-core machine
 * (6 performance + 4 efficiency cores), best of 5 runs:
 *
 * <table border="1">
 *   <caption>Speedup over a single partition</caption>
 *   <tr><th>Board</th><th>1</th><th>2</th><th>4</th><th>8</th><th>16</th><th>20</th><th>Best</th></tr>
 *   <tr><td>201</td><td>1.00</td><td>1.13</td><td><b>1.15</b></td><td>1.11</td>
 *       <td>1.08</td><td>1.03</td><td>4</td></tr>
 *   <tr><td>401</td><td>1.00</td><td>1.66</td><td>2.23</td><td><b>2.30</b></td>
 *       <td>2.27</td><td>2.17</td><td>8</td></tr>
 *   <tr><td>801</td><td>1.00</td><td>1.80</td><td>3.13</td><td>3.07</td>
 *       <td><b>3.53</b></td><td>3.52</td><td>16</td></tr>
 *   <tr><td>1601</td><td>1.00</td><td>1.86</td><td>3.39</td><td>3.95</td>
 *       <td>4.92</td><td><b>5.10</b></td><td>20</td></tr>
 * </table>
 *
 * <p>The optimum tracks the <em>board</em>, not the core count: at every size the best
 * result lands at approximately fifty rows per band, and the largest board is still
 * improving when it reaches the hardware cap. That single invariant is the rule
 * implemented here.
 *
 * <p>Note that the cap deliberately permits more bands than cores. On asymmetric cores
 * an efficiency core running an equal share of the work delays the barrier for
 * everyone; smaller bands let the faster cores absorb proportionally more and cut that
 * straggler cost, which is why 16 and 20 bands beat 8 on the larger boards.
 *
 * <p>The constant is empirical and was calibrated on one machine and one JDK. It is a
 * starting point that is right to within one step on every board measured, not a
 * substitute for measuring a deployment that cares. Both the result and the constant
 * can be overridden; see {@code conway.partitions} and {@code conway.minRowsPerPartition}.
 *
 * <p>{@link #recommend} sizes the board as a whole, once. {@link #bandsFor} applies the same
 * rows-per-band rule again on every generation, against the active region rather than the
 * full board, and uses fewer bands when there is not enough live area to fill them. The
 * measurements above were taken before that cap existed, so requesting more partitions than
 * recommended no longer reproduces the slower figures in the table — it is now capped to the
 * recommended count instead.
 */
public final class PartitionPlanner {

    /**
     * Rows a band needs before splitting it again pays for itself. Below this the
     * per-band dispatch and join cost outweighs the work the band performs.
     */
    public static final int DEFAULT_MIN_ROWS_PER_PARTITION = 50;

    private PartitionPlanner() {
    }

    /**
     * Recommends a partition count for a board of {@code rows} rows.
     *
     * @param rows          board height in cells, must be positive
     * @param maxPartitions upper bound, must be positive
     * @return a value in {@code [1, maxPartitions]}; always 1 for a board too small to split
     */
    public static int recommend(int rows, int maxPartitions) {
        return recommend(rows, maxPartitions, DEFAULT_MIN_ROWS_PER_PARTITION);
    }

    /**
     * Recommends a partition count using an explicit rows-per-band threshold.
     *
     * @throws IllegalArgumentException if any argument is not positive
     */
    public static int recommend(int rows, int maxPartitions, int minRowsPerPartition) {
        if (rows <= 0) {
            throw new IllegalArgumentException("rows must be positive but was " + rows);
        }
        if (maxPartitions <= 0) {
            throw new IllegalArgumentException(
                    "maxPartitions must be positive but was " + maxPartitions);
        }
        if (minRowsPerPartition <= 0) {
            throw new IllegalArgumentException(
                    "minRowsPerPartition must be positive but was " + minRowsPerPartition);
        }

        int byWorkload = rows / minRowsPerPartition;
        return Math.clamp(byWorkload, 1, maxPartitions);
    }

    /**
     * How many bands to actually split one generation across.
     *
     * <p>The requested count is sized for the board, but the work in any one generation is
     * bounded by the active region, which can be far smaller. Splitting work that is not
     * there costs a task dispatch and a join per band and returns nothing: on a 201x201
     * board holding a single blinker, sixteen bands ran 5.4 times slower than computing
     * inline. So the request is also capped by how many bands the active rows can usefully
     * fill.
     *
     * <p>The threshold is the one {@link #recommend} uses. That was measured against full
     * boards, where the active region spans nearly everything; this applies the identical
     * rule to the rows that actually carry work.
     *
     * <p>Purely a work-distribution choice: the resulting board is identical whatever this
     * returns, which is what {@code PartitionEquivalenceTest} pins down.
     *
     * @param partitions the requested count, clamped to {@code [1, height]}
     * @param height     board height in rows
     * @param active     the padded active region of the generation about to be computed
     */
    static int bandsFor(int partitions, int height, ActiveRegion active) {
        int requested = Math.clamp(partitions, 1, height);
        if (active.isEmpty()) {
            return 1;
        }
        int activeRows = active.maxRow() - active.minRow() + 1;
        int useful = activeRows / DEFAULT_MIN_ROWS_PER_PARTITION;
        // Math.clamp requires min <= max, so a region too small to fill one band (useful == 0)
        // must be floored to 1 before clamping.
        return Math.clamp(requested, 1, Math.max(1, useful));
    }

    /**
     * The upper bound the machine supports: {@code min(rows, max(2, 2 × cores))}. Twice the
     * core count, at least two, and never more than the number of rows. Oversubscription is
     * intentional, for the straggler reason above.
     *
     * <p>Not {@code Math.clamp(2 × cores, 2, rows)}: that throws for a one-row board, where
     * the floor of two exceeds the row count and the answer must be one.
     */
    public static int hardwareCap(int rows) {
        if (rows <= 0) {
            throw new IllegalArgumentException("rows must be positive but was " + rows);
        }
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.min(rows, Math.max(2, cores * 2));
    }
}
