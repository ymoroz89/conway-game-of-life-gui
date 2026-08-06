package com.conway.model;

import static com.conway.testsupport.Boards.board;
import static com.conway.testsupport.Boards.render;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GenerationTest {

    @Test
    @DisplayName("An empty board has no live cells and is generation zero")
    void emptyBoard() {
        Generation board = Generation.empty(5, 3);

        assertEquals(5, board.width());
        assertEquals(3, board.height());
        assertEquals(0, board.population());
        assertEquals(0L, board.number());
        assertFalse(board.isAlive(0, 0));
        assertFalse(board.wasEverAlive(2, 4));
    }

    @Test
    @DisplayName("Non-positive dimensions are rejected")
    void invalidDimensionsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Generation.empty(0, 5));
        assertThrows(IllegalArgumentException.class, () -> Generation.empty(5, 0));
        assertThrows(IllegalArgumentException.class, () -> Generation.empty(-1, 5));
    }

    @Test
    @DisplayName("Coordinates outside the board are rejected rather than silently wrapping")
    void outOfBoundsAccessIsRejected() {
        Generation board = Generation.empty(4, 3);

        assertThrows(IndexOutOfBoundsException.class, () -> board.isAlive(3, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> board.isAlive(0, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> board.isAlive(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> board.withCell(0, -1, true));
    }

    @Test
    @DisplayName("withCell returns a new board and leaves the original untouched")
    void withCellDoesNotMutate() {
        Generation original = Generation.empty(4, 4);
        Generation modified = original.withCell(1, 2, true);

        assertEquals(0, original.population());
        assertFalse(original.isAlive(1, 2));
        assertEquals(1, modified.population());
        assertTrue(modified.isAlive(1, 2));
        assertTrue(modified.wasEverAlive(1, 2));
    }

    @Test
    @DisplayName("Clearing a cell also clears its ever-alive marker")
    void removingACellReturnsItToUnpopulated() {
        Generation board = Generation.empty(3, 3).withCell(1, 1, true).withCell(1, 1, false);

        assertFalse(board.isAlive(1, 1));
        assertFalse(board.wasEverAlive(1, 1));
    }

    @Test
    @DisplayName("A pattern is stamped at the requested offset")
    void patternIsPlacedAtOffset() {
        Pattern glider = Pattern.parse("glider", List.of(".O.", "..O", "OOO"));
        Generation board = Generation.empty(6, 6).withPattern(glider, 1, 2);

        assertEquals(List.of(
                "......",
                "...O..",
                "....O.",
                "..OOO.",
                "......",
                "......"), render(board));
    }

    @Test
    @DisplayName("A pattern that overhangs the board is clipped, not rejected")
    void patternIsClipped() {
        Pattern block = Pattern.parse("block", List.of("OO", "OO"));

        Generation bottomRight = Generation.empty(3, 3).withPattern(block, 2, 2);
        assertEquals(1, bottomRight.population());
        assertTrue(bottomRight.isAlive(2, 2));

        Generation topLeft = Generation.empty(3, 3).withPattern(block, -1, -1);
        assertEquals(1, topLeft.population());
        assertTrue(topLeft.isAlive(0, 0));

        Generation entirelyOutside = Generation.empty(3, 3).withPattern(block, 50, 50);
        assertEquals(0, entirelyOutside.population());
    }

    @Test
    @DisplayName("adopt rejects arrays that do not match the declared dimensions")
    void adoptValidatesArrayLength() {
        assertThrows(IllegalArgumentException.class,
                () -> Generation.adopt(3, 3, new boolean[8], new boolean[9], 0L));
        assertThrows(IllegalArgumentException.class,
                () -> Generation.adopt(3, 3, new boolean[9], new boolean[8], 0L));
    }

    @Test
    @DisplayName("Boards with identical contents and generation numbers are equal")
    void equality() {
        Generation first = board("OO.", ".O.", "...");
        Generation second = board("OO.", ".O.", "...");
        Generation different = board("OO.", ".OO", "...");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, different);
    }
}
