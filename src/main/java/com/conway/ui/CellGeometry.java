package com.conway.ui;

/**
 * Maps pixel coordinates within a viewport onto cell indices.
 *
 * <p>Extracted from the board component so the arithmetic can be unit tested without
 * a display, and so the row and column cases share one implementation instead of two
 * near-identical copies.
 *
 * <p>Out-of-range input returns {@code -1}. The previous implementation returned the
 * row or column <em>count</em> for points past the last cell, which is not a valid
 * index and produced an {@code ArrayIndexOutOfBoundsException} in any caller that did
 * not separately re-check the bounds.
 */
public final class CellGeometry {

    /** Returned when a coordinate does not fall on a cell. */
    public static final int NO_CELL = -1;

    private final int rows;
    private final int columns;

    public CellGeometry(int rows, int columns) {
        if (rows <= 0 || columns <= 0) {
            throw new IllegalArgumentException(
                    "Grid must have positive extents but was " + rows + "x" + columns);
        }
        this.rows = rows;
        this.columns = columns;
    }

    /**
     * @return the row containing {@code y}, or {@link #NO_CELL} if it lies outside the board
     */
    public int rowAt(int y, int viewportHeight) {
        return indexAt(y, viewportHeight, rows);
    }

    /**
     * @return the column containing {@code x}, or {@link #NO_CELL} if it lies outside the board
     */
    public int columnAt(int x, int viewportWidth) {
        return indexAt(x, viewportWidth, columns);
    }

    private static int indexAt(int position, int extent, int count) {
        if (extent <= 0 || position < 0 || position >= extent) {
            return NO_CELL;
        }
        int index = (int) (position * (long) count / extent);
        return index < 0 || index >= count ? NO_CELL : index;
    }
}
