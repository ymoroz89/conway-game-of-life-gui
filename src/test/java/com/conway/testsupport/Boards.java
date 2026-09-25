package com.conway.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.conway.model.Generation;
import com.conway.model.Pattern;

/** Helpers for expressing boards as readable literals in tests. */
public final class Boards {

    private Boards() {
    }

    /**
     * Builds a board from plaintext rows, where {@code O} is alive and {@code .} is dead.
     * All rows must be the same length.
     */
    public static Generation board(String... rows) {
        Pattern pattern = Pattern.parse("test-board", List.of(rows));
        return Generation.empty(pattern.width(), pattern.height()).withPattern(pattern, 0, 0);
    }

    /** Renders the alive cells of a board back to the same plaintext form. */
    public static List<String> render(Generation generation) {
        List<String> rows = new ArrayList<>(generation.height());
        for (int row = 0; row < generation.height(); row++) {
            StringBuilder line = new StringBuilder(generation.width());
            for (int col = 0; col < generation.width(); col++) {
                line.append(generation.isAlive(row, col) ? 'O' : '.');
            }
            rows.add(line.toString());
        }
        return rows;
    }

    /** Builds a reproducible random board with the given live-cell probability. */
    public static Generation random(int width, int height, Random random, double density) {
        // Filled directly, in the same row-major order, rather than through withCell: that
        // copies the whole board per live cell, which is quadratic and too slow for boards
        // tall enough to be split into bands.
        boolean[] alive = new boolean[Math.multiplyExact(width, height)];
        for (int i = 0; i < alive.length; i++) {
            alive[i] = random.nextDouble() < density;
        }
        return Generation.adopt(width, height, alive, alive.clone(), 0L);
    }
}
