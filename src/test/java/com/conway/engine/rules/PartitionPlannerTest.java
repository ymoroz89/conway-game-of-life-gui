package com.conway.engine.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PartitionPlannerTest {

    /** The cap on the machine the constant was calibrated against: 10 cores, 2x oversubscribed. */
    private static final int CAP = 20;

    @ParameterizedTest(name = "a {0}-row board is best split {1} ways")
    @CsvSource({
        "201, 4",
        "401, 8",
        "801, 16",
        "1601, 20",
    })
    @DisplayName("The recommendation reproduces the partition count each board measured fastest at")
    void matchesMeasuredOptimum(int rows, int expected) {
        // These are the measured optima from GenerationBenchmark, not restatements of the
        // formula. If the heuristic is retuned it must still land on the fastest count, so
        // this test is what stops the constant drifting away from the evidence for it.
        assertEquals(expected, PartitionPlanner.recommend(rows, CAP));
    }

    @ParameterizedTest(name = "a {0}-row board stays single-threaded")
    @ValueSource(ints = {1, 2, 10, 49})
    @DisplayName("A board too small to give any band enough work is not split at all")
    void tinyBoardsRunOnOneThread(int rows) {
        assertEquals(1, PartitionPlanner.recommend(rows, CAP),
                "splitting this board costs more in dispatch than the work is worth");
    }

    @Test
    @DisplayName("The recommendation never exceeds the cap, however large the board")
    void neverExceedsTheCap() {
        assertEquals(4, PartitionPlanner.recommend(10_000, 4));
        assertEquals(1, PartitionPlanner.recommend(10_000, 1),
                "a single-partition cap must still yield a usable value");
    }

    @Test
    @DisplayName("The rows-per-band threshold is honored when supplied explicitly")
    void honorsExplicitThreshold() {
        assertEquals(20, PartitionPlanner.recommend(201, CAP, 10));
        assertEquals(2, PartitionPlanner.recommend(201, CAP, 100));
        assertEquals(1, PartitionPlanner.recommend(201, CAP, 500));
    }

    @Test
    @DisplayName("The result is always a usable partition count")
    void resultIsAlwaysInRange() {
        for (int rows = 1; rows <= 2000; rows++) {
            int partitions = PartitionPlanner.recommend(rows, CAP);
            assertTrue(partitions >= 1 && partitions <= CAP,
                    "out of range for " + rows + " rows: " + partitions);
            assertTrue(partitions <= rows, "more bands than rows for " + rows + " rows");
        }
    }

    @Test
    @DisplayName("More rows never recommends fewer partitions")
    void isMonotonicInBoardSize() {
        int previous = 0;
        for (int rows = 1; rows <= 2000; rows++) {
            int partitions = PartitionPlanner.recommend(rows, CAP);
            assertTrue(partitions >= previous,
                    "recommendation fell from " + previous + " to " + partitions + " at " + rows);
            previous = partitions;
        }
    }

    @Test
    @DisplayName("Non-positive arguments are rejected rather than silently clamped")
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> PartitionPlanner.recommend(0, CAP));
        assertThrows(IllegalArgumentException.class, () -> PartitionPlanner.recommend(-1, CAP));
        assertThrows(IllegalArgumentException.class, () -> PartitionPlanner.recommend(201, 0));
        assertThrows(IllegalArgumentException.class, () -> PartitionPlanner.recommend(201, CAP, 0));
        assertThrows(IllegalArgumentException.class, () -> PartitionPlanner.hardwareCap(0));
    }

    @Test
    @DisplayName("The hardware cap is positive, respects the board and allows oversubscription")
    void hardwareCapIsSane() {
        int cap = PartitionPlanner.hardwareCap(201);
        assertTrue(cap >= 2, "cap should permit at least two bands: " + cap);
        assertTrue(cap <= 201, "cap should never exceed the row count: " + cap);
        assertEquals(4, PartitionPlanner.hardwareCap(4), "a tiny board caps at its own row count");
        assertEquals(1, PartitionPlanner.hardwareCap(1), "a one-row board caps at one, below the floor of two");
    }
}
