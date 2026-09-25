package com.conway.engine.rules;

import com.conway.model.ActiveRegion;
import com.conway.model.Generation;

/**
 * Applies the rules of Life to one band of rows: the hot loop of the simulation.
 *
 * <p>One instance serves every band of a single generation. It reads only the previous
 * generation and writes only the rows of the band it is given, so concurrent calls for
 * disjoint bands need no synchronization between them. Visibility of those writes is the
 * caller's job; {@link LifeRules} joins each band with {@code Future.get()}.
 *
 * <p>Rule evaluation is clipped to the active region: a cell outside the padded bounding box
 * of the live cells cannot come alive, and the destination arrays are fresh, so those cells
 * are already dead.
 *
 * <p>Pure CPU: it never blocks, which is why the band workers are a {@code ForkJoinPool}
 * rather than virtual threads. Boundaries are dead: the grid does not wrap.
 */
final class BandKernel {

    private final boolean[] src;
    private final boolean[] srcEver;
    private final boolean[] dst;
    private final boolean[] dstEver;
    private final int width;
    private final int height;
    private final ActiveRegion active;

    /**
     * @param current the generation to read
     * @param dst     fresh alive flags for the next generation, written band by band
     * @param dstEver fresh ever-alive flags for the next generation, written band by band
     * @param active  the region rule evaluation is clipped to, already padded
     */
    BandKernel(Generation current, boolean[] dst, boolean[] dstEver, ActiveRegion active) {
        this.src = current.aliveCells();
        this.srcEver = current.everAliveCells();
        this.dst = dst;
        this.dstEver = dstEver;
        this.width = current.width();
        this.height = current.height();
        this.active = active;
    }

    /** Computes rows {@code [fromRow, toRow)} of the next generation. */
    BandResult computeBand(int fromRow, int toRow) {
        // Locals rather than fields, so the loops below read registers, not the heap.
        final boolean[] src = this.src;
        final boolean[] srcEver = this.srcEver;
        final boolean[] dst = this.dst;
        final boolean[] dstEver = this.dstEver;
        final int width = this.width;
        final int height = this.height;
        final ActiveRegion activeLimit = this.active;

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
     * What one band computed: the active region of its slice of the destination board, and
     * the number of live cells it wrote. {@link LifeRules} sums these across bands and hands
     * the totals to {@link Generation#adopt} so the snapshot carries them without a second
     * scan.
     */
    record BandResult(ActiveRegion region, int population) {
    }
}
