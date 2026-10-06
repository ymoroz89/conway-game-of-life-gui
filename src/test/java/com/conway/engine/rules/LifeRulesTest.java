package com.conway.engine.rules;

import static com.conway.testsupport.Boards.board;
import static com.conway.testsupport.Boards.random;
import static com.conway.testsupport.Boards.render;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.conway.model.Generation;

class LifeRulesTest {

    private static LifeRules rules;

    @BeforeAll
    static void setUp() {
        // The no-arg constructor is what SimulationEngine ships: bands are forked into a
        // StructuredTaskScope per generation. There is no pool, so nothing to tear down.
        rules = new LifeRules();
    }

    @Test
    @DisplayName("Ever-alive flags survive the board dying out")
    void everAliveSurvivesExtinction() throws Exception {
        // A lone cell dies at once, leaving an empty board and an empty active region. The
        // flags are documented as monotonic within a run and are what the renderer draws as
        // "died here", so an empty board must not erase them. The early return for an empty
        // region used to hand back a freshly zeroed array, wiping every trail the moment the
        // population reached zero.
        Generation board = Generation.empty(8, 8).withCell(3, 3, true);

        for (int i = 0; i < 3; i++) {
            board = rules.next(board, 1);
        }

        assertEquals(0, board.population(), "the cell should have died of underpopulation");
        assertTrue(board.wasEverAlive(3, 3), "its trail must outlive it");
    }

    @Test
    @DisplayName("Ever-alive flags accumulate across generations")
    void everAliveAccumulates() throws Exception {
        // A glider moves, so cells it has left must stay marked while new ones are added.
        Generation board = board(
                ".O........",
                "..O.......",
                "OOO.......",
                "..........",
                "..........",
                "..........");

        Generation later = board;
        for (int i = 0; i < 4; i++) {
            later = rules.next(later, 2);
        }

        assertTrue(later.wasEverAlive(2, 0), "a cell the glider has vacated stays marked");
        assertTrue(later.population() > 0, "the glider is still alive");
        for (int row = 0; row < later.height(); row++) {
            for (int col = 0; col < later.width(); col++) {
                if (board.wasEverAlive(row, col)) {
                    assertTrue(later.wasEverAlive(row, col),
                            "flag lost at (" + row + ", " + col + ")");
                }
            }
        }
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

        Generation result = board(
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

        // Only the four corners survive: each has exactly three neighbors.
        assertEquals(List.of(
                "O.O",
                "...",
                "O.O"), render(rules.next(start, 1)));
    }

    @Test
    @DisplayName("The grid does not wrap: neighbors off the edge count as dead")
    void bordersDoNotWrap() throws Exception {
        // Were the board toroidal, these three corner cells would each see enough
        // neighbors to survive and to breed a fourth cell.
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
    @DisplayName("The population count matches the rendered board across generations and partition counts")
    void populationMatchesRenderedBoard() throws Exception {
        // The population is folded into the ever-alive pass and summed across bands, so it
        // must agree with a direct count of the rendered board at every partition count.
        Generation start = board(
                ".O........",
                "..O.......",
                "OOO.......",
                "..........",
                "..........",
                "..........");

        for (int partitions : new int[] {1, 2, 4}) {
            Generation current = start;
            for (int i = 0; i < 6; i++) {
                current = rules.next(current, partitions);
                int expected = 0;
                for (String row : render(current)) {
                    for (int c = 0; c < row.length(); c++) {
                        if (row.charAt(c) == 'O') {
                            expected++;
                        }
                    }
                }
                assertEquals(expected, current.population(),
                        "population with " + partitions + " partition(s) at generation " + i);
            }
        }
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

    @Test
    @DisplayName("A failing forked band is reported as SimulationException with the band's own cause")
    void failingForkedBandSurfacesWrapped() throws Exception {
        // The contract this pins is the one the engine relies on: however a generation fails,
        // it escapes as a SimulationException whose cause is the band's own exception, so the
        // engine's single failure path produces the same diagnostic no matter which band broke.
        // Here the failure happens on a forked band, inside the scope, and has to survive the
        // Joiner's exception mapping unchanged.
        LifeRules failingRules = rulesFailingOn(fromRow -> fromRow != 0);

        SimulationException failure = assertThrows(SimulationException.class,
                () -> failingRules.next(denseBoard(), 4));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(INJECTED_FAILURE_MESSAGE, failure.getCause().getMessage());
    }

    @Test
    @DisplayName("A failing inline band is reported the same way, and takes precedence")
    void failingInlineBandSurfacesWrapped() throws Exception {
        // Band zero runs on the owner thread, outside the scope's cancellation reach, so its
        // failure travels a different path: recorded, the scope joined anyway, then thrown.
        // What the engine sees must be indistinguishable from the forked case above.
        //
        // Joining matters here. Leaving the scope without joining is a structure violation, and
        // close() then reports "Owner did not join after forking" instead of the real failure.
        LifeRules failingRules = rulesFailingOn(fromRow -> true);

        SimulationException failure = assertThrows(SimulationException.class,
                () -> failingRules.next(denseBoard(), 4));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(INJECTED_FAILURE_MESSAGE, failure.getCause().getMessage());
        // The forked bands failed too; their failure rides along as suppressed rather than
        // replacing the inline cause or vanishing. A structure violation would show up here.
        assertEquals(1, failure.getSuppressed().length,
                "the forked bands' failure should be suppressed onto the inline one");
        assertInstanceOf(SimulationException.class, failure.getSuppressed()[0]);
    }

    /**
     * Rules whose bands throw when {@code failingBandStart} accepts their first row.
     *
     * <p>The first row identifies the band: zero is the one computed inline on the calling
     * thread, anything else was forked into the scope. Substituting the band computation is the
     * seam {@code LifeRules} exposes for exactly this, and unlike the executor it replaced it
     * leaves the real dispatch path in place — these tests drive the same code the application
     * runs.
     */
    private static LifeRules rulesFailingOn(IntPredicate failingBandStart) {
        return new LifeRules(Thread.ofVirtual().factory(), (kernel, fromRow, toRow) -> {
            if (failingBandStart.test(fromRow)) {
                throw new IllegalStateException(INJECTED_FAILURE_MESSAGE);
            }
            return kernel.computeBand(fromRow, toRow);
        });
    }

    /**
     * Dense and tall enough that the requested partition count is honored: 120 active rows
     * allow two bands of 50+ rows, so exactly one band is forked and one runs inline.
     */
    private static Generation denseBoard() {
        return random(120, 120, new Random(42L), 0.35d);
    }

    private static final String INJECTED_FAILURE_MESSAGE = "injected band failure";
}
