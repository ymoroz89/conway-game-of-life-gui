package com.conway.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PatternTest {

    @Test
    @DisplayName("Plaintext rows are parsed into cells")
    void parsesPlaintext() {
        Pattern glider = Pattern.parse("glider", List.of(".O.", "..O", "OOO"));

        assertEquals("glider", glider.name());
        assertEquals(3, glider.width());
        assertEquals(3, glider.height());
        assertEquals(5, glider.population());
        assertTrue(glider.isAlive(0, 1));
        assertFalse(glider.isAlive(0, 0));
        assertTrue(glider.isAlive(2, 0));
    }

    @Test
    @DisplayName("Comment lines are ignored")
    void ignoresComments() {
        Pattern pattern = Pattern.parse("commented",
                List.of("!Name: something", "!author", "OO", "OO"));

        assertEquals(2, pattern.width());
        assertEquals(2, pattern.height());
        assertEquals(4, pattern.population());
    }

    @Test
    @DisplayName("Short rows are padded with dead cells")
    void padsShortRows() {
        Pattern pattern = Pattern.parse("ragged", List.of("O", "OOOO", "OO"));

        assertEquals(4, pattern.width());
        assertEquals(3, pattern.height());
        assertEquals(7, pattern.population());
        assertFalse(pattern.isAlive(0, 3));
        assertTrue(pattern.isAlive(1, 3));
    }

    @Test
    @DisplayName("Spaces count as dead cells")
    void treatsSpacesAsDead() {
        Pattern pattern = Pattern.parse("spaced", List.of("O O", "   "));

        assertEquals(2, pattern.population());
        assertFalse(pattern.isAlive(0, 1));
    }

    @Test
    @DisplayName("Input without cell rows is rejected")
    void rejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class,
                () -> Pattern.parse("empty", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> Pattern.parse("comments-only", List.of("!just a comment")));
    }

    @Test
    @DisplayName("Rotating by 180 degrees reverses both axes")
    void rotate180() {
        Pattern original = Pattern.parse("L", List.of("O..", "O..", "OO."));
        Pattern rotated = original.rotate180();

        assertEquals(original.width(), rotated.width());
        assertEquals(original.height(), rotated.height());
        assertEquals(original.population(), rotated.population());

        assertEquals(List.of(".OO", "..O", "..O"), rows(rotated));
    }

    @Test
    @DisplayName("Rotating twice returns the original arrangement")
    void rotateIsInvolution() {
        Pattern original = Pattern.parse("glider", List.of(".O.", "..O", "OOO"));

        assertEquals(rows(original), rows(original.rotate180().rotate180()));
    }

    @Test
    @DisplayName("Out-of-range cell access is rejected")
    void rejectsOutOfRangeAccess() {
        Pattern pattern = Pattern.parse("block", List.of("OO", "OO"));

        assertThrows(IndexOutOfBoundsException.class, () -> pattern.isAlive(2, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> pattern.isAlive(0, -1));
    }

    private static List<String> rows(Pattern pattern) {
        List<String> rows = new ArrayList<>(pattern.height());
        for (int row = 0; row < pattern.height(); row++) {
            StringBuilder line = new StringBuilder(pattern.width());
            for (int col = 0; col < pattern.width(); col++) {
                line.append(pattern.isAlive(row, col) ? 'O' : '.');
            }
            rows.add(line.toString());
        }
        return rows;
    }
}
