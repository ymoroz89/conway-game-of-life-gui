package com.conway.engine;

import static com.conway.testsupport.Boards.board;
import static com.conway.testsupport.Boards.render;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.conway.model.Generation;

class LifeRulesTest {

    private static ExecutorService executor;
    private static LifeRules rules;

    @BeforeAll
    static void setUp() {
        executor = Executors.newFixedThreadPool(4);
        rules = new LifeRules(executor);
    }

    @AfterAll
    static void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("A block is a still life")
    void blockIsStable() throws Exception {
        Generation start = board(
                "....",
                ".OO.",
                ".OO.",
                "....");

        assertEquals(render(start), render(rules.next(start, 1)));
    }

    @Test
    @DisplayName("A blinker oscillates with period two")
    void blinkerOscillates() throws Exception {
        Generation vertical = board(
                ".....",
                "..O..",
                "..O..",
                "..O..",
                ".....");
        List<String> horizontal = List.of(
                ".....",
                ".....",
                ".OOO.",
                ".....",
                ".....");

        Generation afterOne = rules.next(vertical, 1);
        assertEquals(horizontal, render(afterOne));
        assertEquals(render(vertical), render(rules.next(afterOne, 1)));
    }

    @Test
    @DisplayName("A glider translates by one cell diagonally every four generations")
    void gliderTranslates() throws Exception {
        Generation start = board(
                ".O........",
                "..O.......",
                "OOO.......",
                "..........",
                "..........",
                "..........",
                "..........",
                "..........",
                "..........",
                "..........");

        Generation result = start;
        for (int i = 0; i < 4; i++) {
            result = rules.next(result, 1);
        }

        assertEquals(List.of(
                "..........",
                "..O.......",
                "...O......",
                ".OOO......",
                "..........",
                "..........",
                "..........",
                "..........",
                "..........",
                ".........."), render(result));
    }

    @Test
    @DisplayName("An isolated cell dies of underpopulation")
    void lonelyCellDies() throws Exception {
        Generation start = board(
                "...",
                ".O.",
                "...");

        assertEquals(0, rules.next(start, 1).population());
    }

    @Test
    @DisplayName("Overcrowded cells die")
    void overcrowdedCellDies() throws Exception {
        Generation start = board(
                "OOO",
                "OOO",
                "OOO");

        // Only the four corners survive: each has exactly three neighbours.
        assertEquals(List.of(
                "O.O",
                "...",
                "O.O"), render(rules.next(start, 1)));
    }

    @Test
    @DisplayName("The grid does not wrap: neighbours off the edge count as dead")
    void bordersDoNotWrap() throws Exception {
        // Were the board toroidal, these three corner cells would each see enough
        // neighbours to survive and to breed a fourth cell.
        Generation start = board(
                "O.O",
                "...",
                "O.O");

        assertEquals(0, rules.next(start, 1).population());
    }

    @Test
    @DisplayName("A block straddling the corner survives, confirming border cells are evaluated")
    void cornerBlockSurvives() throws Exception {
        Generation start = board(
                "OO..",
                "OO..",
                "....",
                "....");

        assertEquals(render(start), render(rules.next(start, 1)));
    }

    @Test
    @DisplayName("The generation counter increments")
    void generationNumberIncrements() throws Exception {
        Generation start = board("...", ".O.", "...");

        assertEquals(0L, start.number());
        assertEquals(1L, rules.next(start, 1).number());
        assertEquals(2L, rules.next(rules.next(start, 1), 1).number());
    }

    @Test
    @DisplayName("Cells that have died remain marked as previously alive")
    void everAliveIsRetainedAfterDeath() throws Exception {
        Generation start = board(
                "...",
                ".O.",
                "...");

        Generation next = rules.next(start, 1);
        assertFalse(next.isAlive(1, 1));
        assertTrue(next.wasEverAlive(1, 1));
        assertFalse(next.wasEverAlive(0, 0));
    }
    @Test
    void optimizedMatchesReference() throws InterruptedException {
        int width = 50;
        int height = 50;
        boolean[] initial = new boolean[width * height];
        // Blinker
        initial[10 * width + 10] = true;
        initial[11 * width + 10] = true;
        initial[12 * width + 10] = true;

        Generation gen0 = Generation.adopt(width, height, initial, initial.clone(), 0);
        
        Generation gen1 = rules.next(gen0, 1);
        
        // Expected blinker horizontal
        assertTrue(gen1.isAlive(11, 9));
        assertTrue(gen1.isAlive(11, 10));
        assertTrue(gen1.isAlive(11, 11));
        assertFalse(gen1.isAlive(10, 10));
        assertFalse(gen1.isAlive(12, 10));
        
        // Check wasEverAlive
        assertTrue(gen1.wasEverAlive(10, 10));
        assertTrue(gen1.wasEverAlive(11, 9));
        assertTrue(gen1.wasEverAlive(11, 10));
        assertTrue(gen1.wasEverAlive(11, 11));
        assertTrue(gen1.wasEverAlive(12, 10));
    }
}
