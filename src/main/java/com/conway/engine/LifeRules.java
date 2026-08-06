package com.conway.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import com.conway.model.ActiveRegion;
import com.conway.model.Generation;

/**
 * Computes successive generations of Conway's Game of Life.
 *
 * <p>The board is split into contiguous bands of rows. Each band writes only to its
 * own disjoint slice of the destination arrays, so no synchronisation is needed
 * between workers; {@link Future#get()} then supplies the happens-before edge that
 * makes every write visible to the thread that assembles the result.
 *
 * <p>The calling thread computes one band itself rather than waiting for the pool to
 * compute all of them, so the degree of parallelism is unchanged but one hand-off per
 * generation is avoided.
 *
 * <p>Boundaries are dead: the grid does not wrap.
 *
 * <p>This class is stateless apart from the executor it borrows, and is safe to call
 * from any thread. In practice it is only ever called from the simulation thread.
 */
public final class LifeRules {

    private final ExecutorService executor;

    public LifeRules(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Computes the generation following {@code current}.
     *
     * <p>The result is identical for every value of {@code partitions}; the parameter
     * only controls how the work is distributed.
     *
     * @param partitions number of row bands to compute concurrently, clamped to
     *                   {@code [1, height]}. A value of one is computed inline on the
     *                   calling thread, giving an honest single-threaded baseline.
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws SimulationException  if a worker fails
     */
    public Generation next(Generation current, int partitions) throws InterruptedException {
        Objects.requireNonNull(current, "current");

        final int width = current.width();
        final int height = current.height();
        final boolean[] src = current.aliveCells();
        final boolean[] srcEver = current.everAliveCells();
        final boolean[] dst = new boolean[src.length];
        final boolean[] dstEver = new boolean[src.length];

        ActiveRegion active = current.activeRegion().pad(width, height);
        if (active.isEmpty()) {
            // Nothing alive, so nothing to compute -- but the ever-alive flags are monotonic
            // within a run and must be carried forward. Returning the freshly zeroed array
            // here erased every trail the moment the population reached zero.
            System.arraycopy(srcEver, 0, dstEver, 0, srcEver.length);
            return Generation.adopt(width, height, dst, dstEver, current.number() + 1,
                    ActiveRegion.EMPTY, 0);
        }

        int bands = bandsFor(partitions, height, active);
        BandResult[] bandResults = new BandResult[bands];

        if (bands == 1) {
            bandResults[0] = computeBand(src, srcEver, dst, dstEver, width, height, 0, height, active);
        } else {
            int baseSize = height / bands;
            int remainder = height % bands;
            int[] from = new int[bands];
            int[] to = new int[bands];
            int start = 0;
            for (int i = 0; i < bands; i++) {
                int size = baseSize + (i < remainder ? 1 : 0);
                from[i] = start;
                to[i] = start + size;
                start = to[i];
            }

            // Dispatch every band but the first, then compute that one here rather than
            // blocking for all of them. The calling thread is the simulation thread, which
            // otherwise parks for the whole generation -- a wall-clock profile measured it
            // blocked 88% of the time while the pool did the work. Running a band on it
            // keeps the same degree of parallelism with one fewer hand-off per generation.
            List<Future<BandResult>> futures = new ArrayList<>(bands - 1);
            for (int i = 1; i < bands; i++) {
                final int bandFrom = from[i];
                final int bandTo = to[i];
                futures.add(executor.submit(() ->
                        computeBand(src, srcEver, dst, dstEver, width, height, bandFrom, bandTo, active)));
            }

            try {
                bandResults[0] =
                        computeBand(src, srcEver, dst, dstEver, width, height, from[0], to[0], active);
            } catch (RuntimeException e) {
                // Dispatched bands surface as ExecutionException below and are wrapped. This
                // one runs on the calling thread, so without wrapping it would escape raw and
                // a failure would report differently depending on which band hit it.
                throw new SimulationException("Generation computation failed", e);
            }

            for (int i = 1; i < bands; i++) {
                try {
                    // Future.get supplies the happens-before edge for the dispatched bands;
                    // band zero was written by this thread, so its writes are already visible.
                    bandResults[i] = futures.get(i - 1).get();
                } catch (ExecutionException e) {
                    throw new SimulationException("Generation computation failed", e.getCause());
                }
            }
        }

        ActiveRegion nextActive = ActiveRegion.EMPTY;
        int population = 0;
        for (BandResult result : bandResults) {
            nextActive = nextActive.merge(result.region());
            population += result.population();
        }

        return Generation.adopt(width, height, dst, dstEver, current.number() + 1, nextActive, population);
    }

    /**
     * How many bands to actually split this generation across.
     *
     * <p>The requested count is sized for the board, but the work in any one generation is
     * bounded by the active region, which can be far smaller. Splitting work that is not
     * there costs a task dispatch and a join per band and returns nothing: on a 201x201
     * board holding a single blinker, sixteen bands ran 5.4 times slower than computing
     * inline. So the requested count is also capped by how many bands the active rows can
     * usefully fill.
     *
     * <p>The threshold is the same rows-per-band figure {@link PartitionPlanner} uses. That
     * planner measured it against full boards, where the active region spans nearly
     * everything; this applies the identical rule to the rows that actually carry work.
     *
     * <p>Purely a work-distribution choice: the resulting board is identical whatever this
     * returns, which is what {@code PartitionEquivalenceTest} pins down.
     */
    static int bandsFor(int partitions, int height, ActiveRegion active) {
        int requested = Math.max(1, Math.min(partitions, height));
        if (active.isEmpty()) {
            return 1;
        }
        int activeRows = active.maxRow() - active.minRow() + 1;
        int useful = activeRows / PartitionPlanner.DEFAULT_MIN_ROWS_PER_PARTITION;
        return Math.max(1, Math.min(requested, useful));
    }

    private static BandResult computeBand(boolean[] src, boolean[] srcEver,
                                          boolean[] dst, boolean[] dstEver,
                                          int width, int height, int fromRow, int toRow,
                                          ActiveRegion activeLimit) {
        int minRow = Integer.MAX_VALUE, maxRow = -1, minCol = Integer.MAX_VALUE, maxCol = -1;

        int rowStart = Math.max(fromRow, activeLimit.minRow());
        int rowEnd = Math.min(toRow, activeLimit.maxRow() + 1);

        for (int row = rowStart; row < rowEnd; row++) {
            int rowBase = row * width;
            int colStart = activeLimit.minCol();
            int colEnd = activeLimit.maxCol() + 1;

            if (row > 0 && row < height - 1 && width > 2) {
                int up = rowBase - width;
                int down = rowBase + width;

                if (colStart == 0) {
                    dst[rowBase] = ruleAt(src, width, height, row, 0);
                    if (dst[rowBase]) {
                        minRow = Math.min(minRow, row);
                        maxRow = Math.max(maxRow, row);
                        minCol = Math.min(minCol, 0);
                        maxCol = Math.max(maxCol, 0);
                    }
                    colStart = 1;
                }

                int effectiveColEnd = Math.min(colEnd, width - 1);

                for (int col = colStart; col < effectiveColEnd; col++) {
                    int i = rowBase + col;
                    int neighbours =
                            (src[up + col - 1] ? 1 : 0)
                                    + (src[up + col] ? 1 : 0)
                                    + (src[up + col + 1] ? 1 : 0)
                                    + (src[i - 1] ? 1 : 0)
                                    + (src[i + 1] ? 1 : 0)
                                    + (src[down + col - 1] ? 1 : 0)
                                    + (src[down + col] ? 1 : 0)
                                    + (src[down + col + 1] ? 1 : 0);
                    dst[i] = src[i] ? (neighbours == 2 || neighbours == 3) : (neighbours == 3);
                    if (dst[i]) {
                        minRow = Math.min(minRow, row);
                        maxRow = Math.max(maxRow, row);
                        minCol = Math.min(minCol, col);
                        maxCol = Math.max(maxCol, col);
                    }
                }

                if (colEnd == width) {
                    int i = rowBase + width - 1;
                    dst[i] = ruleAt(src, width, height, row, width - 1);
                    if (dst[i]) {
                        minRow = Math.min(minRow, row);
                        maxRow = Math.max(maxRow, row);
                        minCol = Math.min(minCol, width - 1);
                        maxCol = Math.max(maxCol, width - 1);
                    }
                }
            } else {
                for (int col = colStart; col < colEnd; col++) {
                    int i = rowBase + col;
                    dst[i] = ruleAt(src, width, height, row, col);
                    if (dst[i]) {
                        minRow = Math.min(minRow, row);
                        maxRow = Math.max(maxRow, row);
                        minCol = Math.min(minCol, col);
                        maxCol = Math.max(maxCol, col);
                    }
                }
            }
        }

        // Carry the previous generation's ever-alive flags forward and mark the cells that
        // newly qualify, fused into one pass per row so the row is still in cache for the
        // second half.
        //
        // The OR sweeps the full width even though dst can only be true inside the active
        // region, so on a sparse board most of it is provably redundant. Narrowing it was
        // tried three ways -- bounding the columns, splitting copy and OR into separate
        // passes, and folding the marks into the rule loop above -- and every one of them
        // cost 2-5% on a dense board while only speeding up boards that were already taking
        // under 50us a generation. The redundancy is deliberate: it buys sequential access
        // over a contiguous row, which is worth more than the work it skips. See
        // docs/profiling-issues-fixing-progress.md.
        //
        // The population count is folded into this same pass: it already visits every cell
        // of the band, so counting dst here costs nothing extra and spares the status bar
        // an O(board) scan per frame.
        int population = 0;
        for (int row = fromRow; row < toRow; row++) {
            int rowBase = row * width;
            System.arraycopy(srcEver, rowBase, dstEver, rowBase, width);
            for (int col = 0; col < width; col++) {
                int i = rowBase + col;
                dstEver[i] |= dst[i];
                if (dst[i]) {
                    population++;
                }
            }
        }

        return new BandResult(new ActiveRegion(minRow, maxRow, minCol, maxCol), population);
    }

    /** Bounds-checked rule evaluation, used for cells on the border of the board. */
    private static boolean ruleAt(boolean[] src, int width, int height, int row, int col) {
        int neighbours = 0;
        int rowStart = Math.max(0, row - 1);
        int rowEnd = Math.min(height - 1, row + 1);
        int colStart = Math.max(0, col - 1);
        int colEnd = Math.min(width - 1, col + 1);

        for (int r = rowStart; r <= rowEnd; r++) {
            int base = r * width;
            for (int c = colStart; c <= colEnd; c++) {
                if ((r != row || c != col) && src[base + c]) {
                    neighbours++;
                }
            }
        }
        boolean alive = src[row * width + col];
        return alive ? (neighbours == 2 || neighbours == 3) : (neighbours == 3);
    }

    /**
     * What one band computed: the active region of its slice of the destination board,
     * and the number of live cells it wrote. The population is summed across bands by
     * {@link #next} and handed to {@link Generation#adopt} so the snapshot carries the
     * count without a second scan.
     */
    private record BandResult(ActiveRegion region, int population) {
    }
}