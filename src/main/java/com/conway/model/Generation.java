package com.conway.model;

import java.util.Arrays;

/**
 * An immutable snapshot of the board at a single point in the simulation.
 *
 * <p>This type is the mechanism that removes the data race between the simulation
 * thread and the Swing event dispatch thread. All fields are {@code final} and the
 * backing arrays are never mutated after construction, so publishing an instance
 * through a {@code volatile} field guarantees every reader observes a fully
 * initialized, internally consistent generation. Readers never see a half-applied
 * generation, and no locking is needed on the render path.
 *
 * <p>Cells carry two independent flags:
 * <ul>
 *   <li>{@code alive} &mdash; participates in the Game of Life rules.</li>
 *   <li>{@code everAlive} &mdash; purely presentational; it records whether the cell
 *       has ever been alive so the renderer can distinguish "died here" from
 *       "never populated". It is monotonic within a run and is reset only by a clear.</li>
 * </ul>
 *
 * <p>Both are stored as flat {@code boolean[]} indexed {@code row * width + col}
 * rather than as {@code Boolean[][]}, which removes ~40k object headers per board
 * and gives the rules engine sequential memory access.
 *
 * <p>The population is maintained as a field rather than recomputed on demand. The
 * status bar reads it at the repaint rate (60 fps), and a scan of the whole board
 * per frame is redundant work the simulation thread can absorb once per generation
 * instead. The rules engine folds the count into a pass it already makes, so the
 * hot path pays nothing extra.
 */
public final class Generation {

    private final int width;
    private final int height;
    private final boolean[] alive;
    private final boolean[] everAlive;
    private final long number;
    private final ActiveRegion activeRegion;
    private final int population;

    private Generation(int width, int height, boolean[] alive, boolean[] everAlive,
                       long number, ActiveRegion activeRegion, int population) {
        this.width = width;
        this.height = height;
        this.alive = alive;
        this.everAlive = everAlive;
        this.number = number;
        this.activeRegion = activeRegion;
        this.population = population;
    }

    /**
     * Creates an empty generation numbered zero.
     *
     * @param width  board width in cells, must be positive
     * @param height board height in cells, must be positive
     */
    public static Generation empty(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Board dimensions must be positive but were " + width + "x" + height);
        }
        int cells = Math.multiplyExact(width, height);
        return new Generation(width, height, new boolean[cells], new boolean[cells],
                0L, ActiveRegion.EMPTY, 0);
    }

    /**
     * Wraps caller-supplied arrays and computes the active region and population.
     */
    public static Generation adopt(int width, int height, boolean[] alive, boolean[] everAlive, long number) {
        int expected = Math.multiplyExact(width, height);
        if (alive.length != expected || everAlive.length != expected) {
            throw new IllegalArgumentException("Array length does not match " + width + "x" + height);
        }
        return new Generation(width, height, alive, everAlive, number,
                computeActiveRegion(width, height, alive), countAlive(alive));
    }

    /**
     * Wraps caller-supplied arrays with a pre-computed active region.
     *
     * <p>The caller transfers ownership: after this call it must never read from or
     * write to {@code alive} or {@code everAlive} again.
     *
     * @throws IllegalArgumentException if the array lengths do not match the dimensions
     */
    public static Generation adopt(int width, int height, boolean[] alive, boolean[] everAlive,
                                   long number, ActiveRegion activeRegion) {
        int expected = Math.multiplyExact(width, height);
        if (alive.length != expected || everAlive.length != expected) {
            throw new IllegalArgumentException("Array length does not match " + width + "x" + height);
        }
        return new Generation(width, height, alive, everAlive, number, activeRegion, countAlive(alive));
    }

    /**
     * Wraps caller-supplied arrays with a pre-computed active region and population.
     *
     * <p>The caller transfers ownership: after this call it must never read from or
     * write to {@code alive} or {@code everAlive} again.
     *
     * <p>This is the hot path. The rules engine already sweeps the destination arrays
     * while computing a generation, so it counts the population in that pass and
     * supplies the result here rather than forcing a second scan.
     *
     * @throws IllegalArgumentException if the array lengths do not match the dimensions
     * @throws IllegalArgumentException if {@code population} is negative
     */
    public static Generation adopt(int width, int height, boolean[] alive, boolean[] everAlive,
                                   long number, ActiveRegion activeRegion, int population) {
        int expected = Math.multiplyExact(width, height);
        if (alive.length != expected || everAlive.length != expected) {
            throw new IllegalArgumentException("Array length does not match " + width + "x" + height);
        }
        if (population < 0) {
            throw new IllegalArgumentException("population must not be negative but was " + population);
        }
        return new Generation(width, height, alive, everAlive, number, activeRegion, population);
    }

    private static ActiveRegion computeActiveRegion(int width, int height, boolean[] alive) {
        int minRow = Integer.MAX_VALUE, maxRow = -1, minCol = Integer.MAX_VALUE, maxCol = -1;
        for (int r = 0; r < height; r++) {
            int rowBase = r * width;
            for (int c = 0; c < width; c++) {
                if (alive[rowBase + c]) {
                    minRow = Math.min(minRow, r);
                    maxRow = Math.max(maxRow, r);
                    minCol = Math.min(minCol, c);
                    maxCol = Math.max(maxCol, c);
                }
            }
        }
        return new ActiveRegion(minRow, maxRow, minCol, maxCol);
    }

    private static int countAlive(boolean[] alive) {
        int count = 0;
        for (boolean cell : alive) {
            if (cell) {
                count++;
            }
        }
        return count;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Number of generations computed since the board was last cleared. */
    public long number() {
        return number;
    }

    public boolean isAlive(int row, int col) {
        checkBounds(row, col);
        return alive[row * width + col];
    }

    public boolean wasEverAlive(int row, int col) {
        checkBounds(row, col);
        return everAlive[row * width + col];
    }

    /** Number of currently alive cells. Maintained incrementally; O(1). */
    public int population() {
        return population;
    }

    /**
     * Direct view of the alive flags, indexed {@code row * width + col}.
     *
     * <p>Exposed unwrapped so the rules engine and the renderer can iterate the board
     * without a defensive copy on a hot path. Callers must treat the result as
     * strictly read-only; mutating it breaks the immutability contract that makes
     * cross-thread publication safe.
     */
    public boolean[] aliveCells() {
        return alive;
    }

    /** Read-only view of the ever-alive flags. Same contract as {@link #aliveCells()}. */
    public boolean[] everAliveCells() {
        return everAlive;
    }

    /** The bounding box of all alive cells. */
    public ActiveRegion activeRegion() {
        return activeRegion;
    }

    /**
     * Returns a copy of this generation with a single cell forced to the given state.
     *
     * <p>Used for interactive editing. Turning a cell on marks it ever-alive; turning
     * it off clears that flag too, so a cell the user removes reverts to unpopulated
     * rather than rendering as recently dead.
     *
     * @throws IndexOutOfBoundsException if the coordinates are off the board
     */
    public Generation withCell(int row, int col, boolean live) {
        checkBounds(row, col);
        boolean[] newAlive = alive.clone();
        boolean[] newEver = everAlive.clone();
        int index = row * width + col;
        newAlive[index] = live;
        newEver[index] = live;
        // Not the hot path -- recompute both active region and population from scratch.
        return adopt(width, height, newAlive, newEver, number);
    }

    /**
     * Returns a copy of this generation with {@code pattern} stamped in with its
     * top-left corner at {@code (row, col)}.
     *
     * <p>Cells that fall outside the board are clipped rather than throwing, so a
     * pattern larger than the grid degrades gracefully instead of failing at startup.
     */
    public Generation withPattern(Pattern pattern, int row, int col) {
        boolean[] newAlive = alive.clone();
        boolean[] newEver = everAlive.clone();
        for (int r = 0; r < pattern.height(); r++) {
            int boardRow = row + r;
            if (boardRow < 0 || boardRow >= height) {
                continue;
            }
            for (int c = 0; c < pattern.width(); c++) {
                int boardCol = col + c;
                if (boardCol < 0 || boardCol >= width || !pattern.isAlive(r, c)) {
                    continue;
                }
                int index = boardRow * width + boardCol;
                newAlive[index] = true;
                newEver[index] = true;
            }
        }
        // Not the hot path -- recompute both active region and population from scratch.
        return adopt(width, height, newAlive, newEver, number);
    }

    private void checkBounds(int row, int col) {
        if (row < 0 || row >= height || col < 0 || col >= width) {
            throw new IndexOutOfBoundsException(
                    "Cell (" + row + ", " + col + ") is outside " + height + "x" + width + " board");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Generation other)) {
            return false;
        }
        return width == other.width
                && height == other.height
                && number == other.number
                && population == other.population
                && activeRegion.equals(other.activeRegion)
                && Arrays.equals(alive, other.alive)
                && Arrays.equals(everAlive, other.everAlive);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(width);
        result = 31 * result + Integer.hashCode(height);
        result = 31 * result + Long.hashCode(number);
        result = 31 * result + Integer.hashCode(population);
        result = 31 * result + Arrays.hashCode(alive);
        result = 31 * result + Arrays.hashCode(everAlive);
        return result;
    }

    @Override
    public String toString() {
        return "Generation[" + number + ", " + width + "x" + height + ", population=" + population + "]";
    }
}