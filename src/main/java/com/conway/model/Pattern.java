package com.conway.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An immutable named arrangement of cells, independent of any board.
 *
 * <p>Patterns are data, not code: they are parsed from the widely used plaintext
 * {@code .cells} format rather than hard-coded as index assignments, so adding a
 * pattern means adding a resource file and does not require touching the board or
 * the container.
 */
public final class Pattern {

    private static final char DEAD = '.';
    private static final char COMMENT = '!';

    private final String name;
    private final int width;
    private final int height;
    private final boolean[] cells;

    private Pattern(String name, int width, int height, boolean[] cells) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.cells = cells;
    }

    /**
     * Parses the plaintext {@code .cells} format: lines beginning with {@code !} are
     * comments, {@code .} and space are dead cells, and any other character is alive.
     * Short lines are padded, so trailing dead cells may be omitted.
     *
     * @throws IllegalArgumentException if the input contains no cell rows
     */
    public static Pattern parse(String name, List<String> lines) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(lines, "lines");

        List<String> rows = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith(String.valueOf(COMMENT))) {
                rows.add(line);
            }
        }
        while (!rows.isEmpty() && rows.getLast().isBlank()) {
            rows.removeLast();
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Pattern '" + name + "' contains no cell rows");
        }

        int height = rows.size();
        int width = 0;
        for (String row : rows) {
            width = Math.max(width, row.length());
        }
        if (width == 0) {
            throw new IllegalArgumentException("Pattern '" + name + "' has zero width");
        }

        boolean[] cells = new boolean[width * height];
        for (int r = 0; r < height; r++) {
            String row = rows.get(r);
            for (int c = 0; c < row.length(); c++) {
                char ch = row.charAt(c);
                cells[r * width + c] = ch != DEAD && ch != ' ';
            }
        }
        return new Pattern(name, width, height, cells);
    }

    public String name() {
        return name;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean isAlive(int row, int col) {
        if (row < 0 || row >= height || col < 0 || col >= width) {
            throw new IndexOutOfBoundsException(
                    "Cell (" + row + ", " + col + ") is outside pattern " + height + "x" + width);
        }
        return cells[row * width + col];
    }

    /** Number of live cells in the pattern. */
    public int population() {
        int count = 0;
        for (boolean cell : cells) {
            if (cell) {
                count++;
            }
        }
        return count;
    }

    /** Returns this pattern rotated by 180 degrees. Applying it twice is the identity. */
    public Pattern rotate180() {
        boolean[] rotated = new boolean[cells.length];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                rotated[(height - 1 - r) * width + (width - 1 - c)] = cells[r * width + c];
            }
        }
        return new Pattern(name + " (rotated 180)", width, height, rotated);
    }

    @Override
    public String toString() {
        return "Pattern[" + name + ", " + height + "x" + width + ", population=" + population() + "]";
    }
}
