package com.conway.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

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

        int bands = Math.max(1, Math.min(partitions, height));
        if (bands == 1) {
            computeBand(src, srcEver, dst, dstEver, width, height, 0, height);
        } else {
            List<Callable<Void>> tasks = new ArrayList<>(bands);
            int baseSize = height / bands;
            int remainder = height % bands;
            int start = 0;
            for (int i = 0; i < bands; i++) {
                int size = baseSize + (i < remainder ? 1 : 0);
                final int from = start;
                final int to = start + size;
                start = to;
                tasks.add(() -> {
                    computeBand(src, srcEver, dst, dstEver, width, height, from, to);
                    return null;
                });
            }
            for (Future<Void> future : executor.invokeAll(tasks)) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    // A failed band means the generation is incomplete. Surfacing it is
                    // the only correct option: continuing would silently propagate a
                    // wrong board through every subsequent generation.
                    throw new SimulationException("Generation computation failed", e.getCause());
                }
            }
        }
        return Generation.adopt(width, height, dst, dstEver, current.number() + 1);
    }

    /**
     * Applies the rules to rows {@code [fromRow, toRow)}.
     *
     * <p>Interior rows use an unrolled neighbour sum with no per-neighbour bounds
     * checks; only the first and last row and column take the guarded path. For a
     * 201x201 board that removes roughly 320k branches per generation.
     */
    private static void computeBand(boolean[] src, boolean[] srcEver,
                                    boolean[] dst, boolean[] dstEver,
                                    int width, int height, int fromRow, int toRow) {
        for (int row = fromRow; row < toRow; row++) {
            int rowBase = row * width;

            if (row > 0 && row < height - 1 && width > 2) {
                int up = rowBase - width;
                int down = rowBase + width;

                dst[rowBase] = ruleAt(src, width, height, row, 0);
                for (int col = 1; col < width - 1; col++) {
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
                }
                dst[rowBase + width - 1] = ruleAt(src, width, height, row, width - 1);
            } else {
                for (int col = 0; col < width; col++) {
                    dst[rowBase + col] = ruleAt(src, width, height, row, col);
                }
            }

            for (int col = 0; col < width; col++) {
                int i = rowBase + col;
                dstEver[i] = srcEver[i] || dst[i];
            }
        }
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
