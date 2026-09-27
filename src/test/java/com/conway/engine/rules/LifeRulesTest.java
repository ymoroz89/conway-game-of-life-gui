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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
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
    static void tearDown() {
        // ExecutorService has been AutoCloseable since Java 19: close() shuts the pool
        // down and waits for it to terminate, replacing the manual shutdown block.
        executor.close();
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
    @DisplayName("A failing dispatched band is reported as SimulationException with the worker's own cause")
    void failingDispatchedBandSurfacesWrapped() throws Exception {
        // Band zero runs on the calling thread and cannot be made to fail from outside,
        // so the injection hits every dispatched band. The contract this pins is the one
        // the engine relies on: however a generation fails, it escapes as a
        // SimulationException whose cause is the worker's own exception, so the engine's
        // single failure path produces the same diagnostic no matter which band broke.
        try (ExecutorService failing = failingBandExecutor()) {
            LifeRules failingRules = new LifeRules(failing);
            // Dense and tall enough that the requested count is honored: 120 active rows
            // allow two bands of 50+ rows, so exactly one task is dispatched and one runs
            // inline, and the dispatched one fails.
            Generation start = random(120, 120, new Random(42L), 0.35d);

            SimulationException failure = assertThrows(SimulationException.class,
                    () -> failingRules.next(start, 4));

            assertEquals(INJECTED_FAILURE_MESSAGE, failure.getCause().getMessage());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
        }
    }

    /**
     * An executor whose workers always fail, so any dispatched band throws on arrival.
     *
     * <p>LifeRules.next() dispatches with {@code executor.submit(Callable)}; replacing the
     * task with one that throws simulates a worker crashing inside {@code computeBand} while
     * keeping the failure on the worker thread, which is what exercises the
     * {@code ExecutionException} wrapping path.
     */
    private static ExecutorService failingBandExecutor() {
        return new ThreadPoolExecutor(
                0, 4, 1L, TimeUnit.MINUTES, new SynchronousQueue<Runnable>()) {
            @Override
            public <T> Future<T> submit(Callable<T> task) {
                // Discard the real task and substitute one that throws. The anonymous call
                // avoids a raw-super call and keeps the lambda as Callable<T>.
                return super.submit(new Callable<T>() {
                    @Override
                    public T call() {
                        throw new IllegalStateException(INJECTED_FAILURE_MESSAGE);
                    }
                });
            }
        };
    }

    private static final String INJECTED_FAILURE_MESSAGE = "injected band failure";
}
