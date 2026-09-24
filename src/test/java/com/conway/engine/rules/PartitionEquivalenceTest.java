package com.conway.engine.rules;

import static com.conway.testsupport.Boards.random;
import static com.conway.testsupport.Boards.render;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.conway.model.Generation;

/**
 * The central safety net for the parallel rules engine.
 *
 * <p>Partitioning is purely a work-distribution concern: the board that comes out
 * must not depend on how many bands the work was split into. Asserting that property
 * directly catches off-by-one errors at band boundaries, rows that are skipped or
 * computed twice, and any accidental sharing of mutable state between workers —
 * the exact class of defect that partition arithmetic has historically produced here.
 */
class PartitionEquivalenceTest {

    private static final int BOARD_SIZE = 37;
    private static final int GENERATIONS = 12;
    private static final long SEED = 20_260_728L;

    private static ExecutorService executor;
    private static LifeRules rules;

    @BeforeAll
    static void setUp() {
        // ExecutorService has been AutoCloseable since Java 19: close() shuts the pool
        // down and waits for it to terminate, replacing the manual shutdown block.
        executor = Executors.newFixedThreadPool(8);
        rules = new LifeRules(executor);
    }

    @AfterAll
    static void tearDown() {
        executor.close();
    }

    @ParameterizedTest(name = "{0} partitions produce the single-threaded result")
    @ValueSource(ints = {2, 3, 4, 5, 7, 8, 16, 36, 37})
    void partitioningDoesNotChangeTheResult(int partitions) throws Exception {
        List<Generation> expected = singleThreadedRun();

        Generation actual = seed();
        for (int generation = 0; generation < GENERATIONS; generation++) {
            actual = rules.next(actual, partitions);
            assertEquals(render(expected.get(generation)), render(actual),
                    "Diverged from the single-threaded result at generation " + (generation + 1)
                            + " with " + partitions + " partitions");
        }
    }

    @Test
    @DisplayName("Requesting more partitions than there are rows is clamped, not broken")
    void partitionsAreClampedToRowCount() throws Exception {
        List<Generation> expected = singleThreadedRun();

        Generation actual = seed();
        for (int generation = 0; generation < GENERATIONS; generation++) {
            actual = rules.next(actual, BOARD_SIZE * 10);
            assertEquals(render(expected.get(generation)), render(actual),
                    "Oversized partition count diverged at generation " + (generation + 1));
        }
    }

    @Test
    @DisplayName("Zero and negative partition counts fall back to a single band")
    void nonPositivePartitionCountsAreClamped() throws Exception {
        Generation start = seed();
        Generation baseline = rules.next(start, 1);

        assertEquals(render(baseline), render(rules.next(start, 0)));
        assertEquals(render(baseline), render(rules.next(start, -4)));
    }

    @Test
    @DisplayName("Every row is computed exactly once, including on a non-square board")
    void nonSquareBoardsPartitionCorrectly() throws Exception {
        Generation start = random(23, 41, new Random(SEED), 0.35d);

        Generation expected = start;
        Generation actual = start;
        for (int generation = 0; generation < GENERATIONS; generation++) {
            expected = rules.next(expected, 1);
            actual = rules.next(actual, 6);
            assertEquals(render(expected), render(actual),
                    "Non-square board diverged at generation " + (generation + 1));
        }
    }

    private static Generation seed() {
        return random(BOARD_SIZE, BOARD_SIZE, new Random(SEED), 0.35d);
    }

    private static List<Generation> singleThreadedRun() throws InterruptedException {
        List<Generation> history = new ArrayList<>(GENERATIONS);
        Generation board = seed();
        for (int generation = 0; generation < GENERATIONS; generation++) {
            board = rules.next(board, 1);
            history.add(board);
        }
        return history;
    }
}
