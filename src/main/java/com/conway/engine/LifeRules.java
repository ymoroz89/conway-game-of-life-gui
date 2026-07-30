package com.conway.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
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
 * between workers; {@link ExecutorService#invokeAll} then supplies the
 * happens-before edge that makes every write visible to the thread that assembles
 * the result.
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
            return Generation.adopt(width, height, dst, dstEver, current.number() + 1, ActiveRegion.EMPTY);
        }

        int bands = Math.max(1, Math.min(partitions, height));
        ActiveRegion[] bandRegions = new ActiveRegion[bands];

        if (bands == 1) {
            bandRegions[0] = computeBand(src, srcEver, dst, dstEver, width, height, 0, height, active);
        } else {
            List<Callable<ActiveRegion>> tasks = new ArrayList<>(bands);
            int baseSize = height / bands;
            int remainder = height % bands;
            int start = 0;
            for (int i = 0; i < bands; i++) {
                int size = baseSize + (i < remainder ? 1 : 0);
                final int from = start;
                final int to = start + size;
                start = to;
                tasks.add(() -> computeBand(src, srcEver, dst, dstEver, width, height, from, to, active));
            }
            int i = 0;
            for (Future<ActiveRegion> future : executor.invokeAll(tasks)) {
                try {
                    bandRegions[i++] = future.get();
                } catch (ExecutionException e) {
                    throw new SimulationException("Generation computation failed", e.getCause());
                }
            }
        }

        ActiveRegion nextActive = ActiveRegion.EMPTY;
        for (ActiveRegion r : bandRegions) {
            nextActive = nextActive.merge(r);
        }

        return Generation.adopt(width, height, dst, dstEver, current.number() + 1, nextActive);
    }

    private static ActiveRegion computeBand(boolean[] src, boolean[] srcEver,
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

        for (int row = fromRow; row < toRow; row++) {
            int rowBase = row * width;
            System.arraycopy(srcEver, rowBase, dstEver, rowBase, width);
            for (int col = 0; col < width; col++) {
                int i = rowBase + col;
                dstEver[i] |= dst[i];
            }
        }

        return new ActiveRegion(minRow, maxRow, minCol, maxCol);
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
}
