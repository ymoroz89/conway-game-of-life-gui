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
 *
 * <p>The per-cell loops take the band's arrays and dimensions as parameters or locals and
 * never read a field, so a loop body never reloads {@code this}.
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

    /**
     * Computes rows {@code [fromRow, toRow)} of the next generation.
     *
     * <p>Rows come in two shapes and the branch below separates them. In an interior row --
     * not the first or last, on a board at least three cells wide -- every cell except the
     * first and last column has all eight neighbors on the board, so that middle run needs
     * no bounds checks at all; it is the path that carries the run time. Every other cell --
     * the first and last column of an interior row, and every cell of the top and bottom
     * rows or of a board too narrow to have a middle run -- goes through {@link #ruleAt},
     * which checks each neighbor.
     *
     * <p>Rule evaluation is clipped to the active region: cells the region does not reach are
     * not written at all, since a fresh {@code dst} is already dead there. {@code live}
     * collects the bounding box of the cells this band leaves alive, which {@link LifeRules}
     * merges with the boxes of the other bands to form the next generation's active region.
     */
    BandResult computeBand(int fromRow, int toRow) {
        final boolean[] src = this.src;
        final boolean[] dst = this.dst;
        final int width = this.width;
        final int height = this.height;
        final ActiveRegion activeLimit = this.active;

        LiveBounds live = new LiveBounds();
        RowBounds rowBounds = new RowBounds();

        int firstRow = Math.max(fromRow, activeLimit.minRow());
        int lastRow = Math.min(toRow, activeLimit.maxRow() + 1);

        for (int row = firstRow; row < lastRow; row++) {
            int rowBase = row * width;
            int colStart = activeLimit.minCol();
            int colEnd = activeLimit.maxCol() + 1;
            rowBounds.reset();

            if (isInteriorRow(row, width, height)) {
                processInteriorRow(src, dst, width, height, row, rowBase, colStart, colEnd, rowBounds);
            } else {
                // A cell on the border of the board, or on a board too narrow to have
                // interior cells: each one is evaluated with its neighbors checked.
                processBorderRow(src, dst, width, height, row, rowBase, colStart, colEnd, rowBounds);
            }

            live.includeRow(row, rowBounds.minCol, rowBounds.maxCol);
        }

        int population = carryEverAliveAndCountPopulation(fromRow, toRow);
        return new BandResult(live.toRegion(), population);
    }

    /** Processes an interior row where the middle run needs no bounds checks. */
    private static void processInteriorRow(boolean[] src,
                                           boolean[] dst,
                                           int width,
                                           int height,
                                           int row,
                                           int rowBase,
                                           int colStart,
                                           int colEnd,
                                           RowBounds rowBounds) {
        int rowAboveBase = rowBase - width;
        int rowBelowBase = rowBase + width;

        handleLeftEdgeIfReached(src, dst, width, height, row, rowBase, colStart, rowBounds);
        processUncheckedMiddleRun(src, dst, rowBase, rowAboveBase, rowBelowBase, width, colStart, colEnd, rowBounds);
        handleRightEdgeIfReached(src, dst, width, height, row, rowBase, colEnd, rowBounds);
    }

    /** Processes a border or narrow row, using fully checked rule evaluation. */
    private static void processBorderRow(boolean[] src,
                                         boolean[] dst,
                                         int width,
                                         int height,
                                         int row,
                                         int rowBase,
                                         int colStart,
                                         int colEnd,
                                         RowBounds rowBounds) {
        for (int col = colStart; col < colEnd; col++) {
            int i = rowBase + col;
            dst[i] = ruleAt(src, width, height, row, col);
            rowBounds.includeIfAlive(dst[i], col);
        }
    }

    /**
     * Copies ever-alive flags for one row, folds in the freshly alive cells of this
     * generation, and returns the number of live cells in the row.
     */
    private int processEverRow(int row) {
        final boolean[] srcEver = this.srcEver;
        final boolean[] dst = this.dst;
        final boolean[] dstEver = this.dstEver;
        final int width = this.width;

        int rowBase = row * width;
        System.arraycopy(srcEver, rowBase, dstEver, rowBase, width);

        int pop = 0;
        for (int col = 0; col < width; col++) {
            int i = rowBase + col;
            boolean alive = dst[i];
            dstEver[i] |= alive;
            if (alive) pop++;
        }
        return pop;
    }

    /**
     * True when {@code row} has a middle run of cells with all eight neighbors on the board:
     * the row is not the first or last, and the board is at least three cells wide. The first
     * and last column of such a row still need checked evaluation. No row of a narrower board
     * qualifies, whatever its position.
     */
    private static boolean isInteriorRow(int row, int width, int height) {
        return row > 0 && row < height - 1 && width > 2;
    }

    /**
     * Carries the ever-alive flags of rows {@code [fromRow, toRow)} forward into
     * {@code dstEver}, marks the cells this generation turned alive, and returns the number
     * of live cells in the band.
     *
     * <p>The copy and the marking are fused into one pass per row so the row is still in
     * cache for the second half. The OR sweeps the full width even though {@code dst} can
     * only be true inside the active region, so on a sparse board most of it is provably
     * redundant. Narrowing it was tried three ways -- bounding the columns, splitting copy
     * and OR into separate passes, and folding the marks into the rule loops above -- and
     * every one of them cost 2-5% on a dense board while only speeding up boards that were
     * already taking under 50us a generation. The redundancy is deliberate: it buys
     * sequential access over a contiguous row, which is worth more than the work it skips.
     * See docs/profiling-issues-fixing-progress.md.
     *
     * <p>The population count is folded into this same pass: it already visits every cell of
     * the band, so counting {@code dst} here costs nothing extra and spares the status bar
     * an O(board) scan per frame.
     */
    private int carryEverAliveAndCountPopulation(int fromRow, int toRow) {
        int population = 0;
        for (int row = fromRow; row < toRow; row++) {
            population += processEverRow(row);
        }
        return population;
    }

    /**
     * Bounds-checked rule evaluation, for cells on the border of the board and for every cell
     * of a board too narrow to have a middle run.
     */
    private static boolean ruleAt(boolean[] src, int width, int height, int row, int col) {
        int neighbors = countNeighborsChecked(src, width, height, row, col);
        return survives(src[row * width + col], neighbors);
    }

    /** Counts the live neighbors of one cell, skipping any that fall off the board. */
    private static int countNeighborsChecked(boolean[] src, int width, int height, int row, int col) {
        int neighbors = 0;
        int rowStart = Math.max(0, row - 1);
        int rowEnd = Math.min(height - 1, row + 1);
        int colStart = Math.max(0, col - 1);
        int colEnd = Math.min(width - 1, col + 1);

        for (int r = rowStart; r <= rowEnd; r++) {
            int base = r * width;
            for (int c = colStart; c <= colEnd; c++) {
                if ((r != row || c != col) && src[base + c]) {
                    neighbors++;
                }
            }
        }
        return neighbors;
    }

    private static void handleLeftEdgeIfReached(boolean[] src,
                                                boolean[] dst,
                                                int width,
                                                int height,
                                                int row,
                                                int rowBase,
                                                int colStart,
                                                RowBounds rowBounds) {
        if (colStart == 0) {
            dst[rowBase] = ruleAt(src, width, height, row, 0);
            rowBounds.includeIfAlive(dst[rowBase], 0);
        }
    }

    private static void handleRightEdgeIfReached(boolean[] src,
                                                 boolean[] dst,
                                                 int width,
                                                 int height,
                                                 int row,
                                                 int rowBase,
                                                 int colEnd,
                                                 RowBounds rowBounds) {
        if (colEnd == width) {
            int col = width - 1;
            int i = rowBase + col;
            dst[i] = ruleAt(src, width, height, row, col);
            rowBounds.includeIfAlive(dst[i], col);
        }
    }

    private static void processUncheckedMiddleRun(boolean[] src,
                                                  boolean[] dst,
                                                  int rowBase,
                                                  int rowAboveBase,
                                                  int rowBelowBase,
                                                  int width,
                                                  int colStart,
                                                  int colEnd,
                                                  RowBounds rowBounds) {
        int uncheckedStart = Math.max(colStart, 1);
        int uncheckedEnd = Math.min(colEnd, width - 1);
        for (int col = uncheckedStart; col < uncheckedEnd; col++) {
            int cellIndex = rowBase + col;
            int neighborCount =
                    (src[rowAboveBase + col - 1] ? 1 : 0)
                            + (src[rowAboveBase + col] ? 1 : 0)
                            + (src[rowAboveBase + col + 1] ? 1 : 0)
                            + (src[cellIndex - 1] ? 1 : 0)
                            + (src[cellIndex + 1] ? 1 : 0)
                            + (src[rowBelowBase + col - 1] ? 1 : 0)
                            + (src[rowBelowBase + col] ? 1 : 0)
                            + (src[rowBelowBase + col + 1] ? 1 : 0);
            dst[cellIndex] = survives(src[cellIndex], neighborCount);
            rowBounds.includeIfAlive(dst[cellIndex], col);
        }
    }

    /**
     * The rule itself: a live cell with two or three live neighbors survives, and a dead
     * cell with exactly three comes alive.
     */
    private static boolean survives(boolean alive, int neighbors) {
        return alive ? (neighbors == 2 || neighbors == 3) : (neighbors == 3);
    }

    /**
     * The bounding box of the cells a band leaves alive, accumulated as the band runs.
     *
     * <p>Rows arrive in order, but nothing depends on that, and a row that carries no live
     * cells changes nothing.
     */
    private static final class LiveBounds {

        private int minRow = Integer.MAX_VALUE;
        private int maxRow = -1;
        private int minCol = Integer.MAX_VALUE;
        private int maxCol = -1;

        /**
         * Offers the live cells of one row, whose columns span {@code [rowMinCol, rowMaxCol]}.
         * An empty span means the row had no live cells and is ignored.
         */
        void includeRow(int row, int rowMinCol, int rowMaxCol) {
            if (rowMinCol > rowMaxCol) {
                return;
            }
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
            minCol = Math.min(minCol, rowMinCol);
            maxCol = Math.max(maxCol, rowMaxCol);
        }

        /** The bounding box, empty when no row offered a live cell. */
        ActiveRegion toRegion() {
            return new ActiveRegion(minRow, maxRow, minCol, maxCol);
        }
    }

    /**
     * The live columns of the row being evaluated. One instance per band, reset for each row,
     * so the per-row bookkeeping allocates nothing.
     */
    private static final class RowBounds {
        int minCol = Integer.MAX_VALUE;
        int maxCol = -1;

        void reset() {
            minCol = Integer.MAX_VALUE;
            maxCol = -1;
        }

        void includeIfAlive(boolean alive, int col) {
            if (!alive) return;
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
        }
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
