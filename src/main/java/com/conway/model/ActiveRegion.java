package com.conway.model;

/**
 * An immutable integer rectangle, used to track active regions on the board.
 */
public record ActiveRegion(int minRow, int maxRow, int minCol, int maxCol) {

    public static final ActiveRegion EMPTY = new ActiveRegion(Integer.MAX_VALUE, -1, Integer.MAX_VALUE, -1);

    public boolean isEmpty() {
        return minRow > maxRow || minCol > maxCol;
    }

    /** Returns a region that covers both this and another region. */
    public ActiveRegion merge(ActiveRegion other) {
        if (other.isEmpty()) return this;
        if (this.isEmpty()) return other;
        return new ActiveRegion(
                Math.min(minRow, other.minRow),
                Math.max(maxRow, other.maxRow),
                Math.min(minCol, other.minCol),
                Math.max(maxCol, other.maxCol)
        );
    }

    /** 
     * Returns a region expanded by one cell in all directions, clamped to the board.
     * Life rules mean a cell can be influenced by neighbors one step away.
     */
    public ActiveRegion pad(int width, int height) {
        if (isEmpty()) return this;
        return new ActiveRegion(
                Math.max(0, minRow - 1),
                Math.min(height - 1, maxRow + 1),
                Math.max(0, minCol - 1),
                Math.min(width - 1, maxCol + 1)
        );
    }
}
