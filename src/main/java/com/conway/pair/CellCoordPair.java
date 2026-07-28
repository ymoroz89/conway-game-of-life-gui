package com.conway.pair;

public class CellCoordPair<L, R> {

    private final L left;
    private final R right;
    private final boolean isAlive;

    public CellCoordPair(L left, R right, boolean isAlive) {
        this.left = left;
        this.right = right;
        this.isAlive = isAlive;
    }

    public L getLeft() {
        return left;
    }

    public R getRight() {
        return right;
    }

    public boolean isAlive() {
        return isAlive;
    }
}
