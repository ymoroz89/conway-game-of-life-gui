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
        Generation board = Generation.empty(width, height);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (random.nextDouble() < density) {
                    board = board.withCell(row, col, true);
                }
            }
        }
        return board;
    }
}
