package com.conway.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.conway.engine.support.SimulationStats;
import com.conway.model.Generation;
import com.conway.model.Pattern;

class SimulationEngineTest {

    private static final int SIZE = 32;

    /**
     * Tall enough for the engine to actually use eight bands: it allows one band per 50
     * active rows, so eight bands need at least 400.
     */
    private static final int BANDED_SIZE = 420;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private SimulationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SimulationEngine(SIZE, SIZE, 4);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    @DisplayName("A step advances exactly one generation")
    void stepAdvancesOneGeneration() throws Exception {
        assertEquals(0L, engine.currentGeneration().number());

        engine.step();
        assertTrue(engine.sync(5, TimeUnit.SECONDS));

        assertEquals(1L, engine.currentGeneration().number());
    }

    @Test
    @Timeout(30)
    @DisplayName("Queued step requests are never dropped")
    void queuedStepsAreAllHonored() throws Exception {
        // The previous wait/notify design silently discarded a step request whenever
        // the controller thread was not already parked in wait(). Because commands are
        // queued rather than signaled, every request must be applied exactly once.
        int steps = 200;
        for (int i = 0; i < steps; i++) {
            engine.step();
        }
        assertTrue(engine.sync(20, TimeUnit.SECONDS));

        assertEquals(steps, engine.currentGeneration().number());
    }

    @Test
    @Timeout(30)
    @DisplayName("Start advances continuously and pause stops it")
    void startAndPause() throws Exception {
        engine.place(blinker(), 4, 4);
        engine.start();

        await(() -> engine.currentGeneration().number() > 25L);
        assertEquals(SimulationEngine.State.RUNNING, engine.state());

        engine.pause();
        assertTrue(engine.sync(5, TimeUnit.SECONDS));
        long settled = engine.currentGeneration().number();

        Thread.sleep(200L);
        assertEquals(settled, engine.currentGeneration().number(),
                "The board advanced after the engine was paused");
        assertEquals(SimulationEngine.State.PAUSED, engine.state());
    }

    @Test
    @Timeout(30)
    @DisplayName("Throughput statistics are recorded for a run")
    void statsAreRecorded() throws Exception {
        engine.place(blinker(), 4, 4);
        engine.start();
        await(() -> engine.stats().generationsInRun() > 50L);
        engine.pause();
        assertTrue(engine.sync(5, TimeUnit.SECONDS));

        SimulationStats stats = engine.stats();
        assertTrue(stats.elapsedNanos() > 0L, "Elapsed time was not measured");
        assertTrue(stats.generationsPerSecond() > 0.0d,
                "Rate should be positive but was " + stats.generationsPerSecond());
    }

    @Test
    @DisplayName("Toggling a cell flips it and is idempotent in pairs")
    void toggleCell() throws Exception {
        engine.toggleCell(3, 7);
        assertTrue(engine.sync(5, TimeUnit.SECONDS));
        assertTrue(engine.currentGeneration().isAlive(3, 7));
        assertTrue(engine.currentGeneration().wasEverAlive(3, 7));

        engine.toggleCell(3, 7);
        assertTrue(engine.sync(5, TimeUnit.SECONDS));
        assertFalse(engine.currentGeneration().isAlive(3, 7));
        assertFalse(engine.currentGeneration().wasEverAlive(3, 7),
                "Removing a cell should return it to unpopulated, not recently dead");
    }

    @Test
    @DisplayName("Out-of-bounds toggles are ignored rather than throwing")
    void outOfBoundsToggleIsIgnored() throws Exception {
        engine.toggleCell(-1, 0);
        engine.toggleCell(0, SIZE);
        engine.toggleCell(SIZE * 4, SIZE * 4);
        assertTrue(engine.sync(5, TimeUnit.SECONDS));

        assertEquals(0, engine.currentGeneration().population());
        assertNotEquals(SimulationEngine.State.FAILED, engine.state());
    }

    @Test
    @DisplayName("Clear empties the board and resets the generation counter")
    void clearResetsEverything() throws Exception {
        engine.place(blinker(), 4, 4);
        engine.step();
        engine.step();
        assertTrue(engine.sync(5, TimeUnit.SECONDS));
        assertTrue(engine.currentGeneration().population() > 0);

        engine.clear();
        assertTrue(engine.sync(5, TimeUnit.SECONDS));

        Generation cleared = engine.currentGeneration();
        assertEquals(0, cleared.population());
        assertEquals(0L, cleared.number());
    }

    @Test
    @DisplayName("A placed pattern larger than the board is clipped, not rejected")
    void oversizedPatternIsClipped() throws Exception {
        SimulationEngine small = new SimulationEngine(4, 4, 2);
        try {
            small.place(blinker(), 3, 3);
            assertTrue(small.sync(5, TimeUnit.SECONDS));
            assertNotEquals(SimulationEngine.State.FAILED, small.state());
            assertEquals(1, small.currentGeneration().population());
        } finally {
            small.close();
        }
    }

    @Test
    @DisplayName("Changing the partition count does not disturb the board")
    void partitionCountIsAppliedSafely() throws Exception {
        engine.place(blinker(), 4, 4);
        engine.setPartitions(4);
        engine.step();
        assertTrue(engine.sync(5, TimeUnit.SECONDS));
        Generation withFour = engine.currentGeneration();

        engine.clear();
        engine.place(blinker(), 4, 4);
        engine.setPartitions(1);
        engine.step();
        assertTrue(engine.sync(5, TimeUnit.SECONDS));

        assertEquals(withFour, engine.currentGeneration());
    }

    @Test
    @DisplayName("The partition count is clamped to the configured maximum")
    void partitionCountIsClamped() throws Exception {
        engine.setPartitions(9999);
        engine.step();
        assertTrue(engine.sync(5, TimeUnit.SECONDS));

        assertEquals(engine.maxPartitions(), engine.stats().partitions());
    }

    @Test
    @Timeout(60)
    @DisplayName("Concurrent edits during a run neither corrupt the board nor fail")
    void concurrentEditsAreSafe() throws Exception {
        // Editing used to write to the shared matrix straight from the event dispatch
        // thread while the simulation thread wrote to the same array. Edits are now
        // queued to the single writer, so hammering them during a run must be benign.
        engine.start();
        await(() -> engine.currentGeneration().number() > 5L);

        int threads = 4;
        int editsPerThread = 250;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int offset = t;
            Thread editor = new Thread(() -> {
                try {
                    ready.countDown();
                    go.await();
                    for (int i = 0; i < editsPerThread; i++) {
                        engine.toggleCell((offset * 7 + i) % SIZE, (offset * 5 + i) % SIZE);
                        // Readers run concurrently too; a snapshot must always be consistent.
                        Generation snapshot = engine.currentGeneration();
                        if (snapshot.width() != SIZE || snapshot.height() != SIZE) {
                            failures.add(new AssertionError("Observed a malformed snapshot"));
                        }
                    }
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            }, "editor-" + t);
            editor.setDaemon(true);
            editor.start();
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "Editor threads did not finish");

        engine.pause();
        assertTrue(engine.sync(10, TimeUnit.SECONDS));

        if (!failures.isEmpty()) {
            throw new AssertionError("Concurrent editing produced " + failures.size()
                    + " failure(s); first was:", failures.get(0));
        }
        assertNotEquals(SimulationEngine.State.FAILED, engine.state());
    }

    @Test
    @DisplayName("Snapshots are immutable, so a held reference never changes")
    void heldSnapshotsDoNotChange() throws Exception {
        engine.place(blinker(), 4, 4);
        assertTrue(engine.sync(5, TimeUnit.SECONDS));

        Generation held = engine.currentGeneration();
        int population = held.population();
        long number = held.number();

        engine.step();
        engine.step();
        assertTrue(engine.sync(5, TimeUnit.SECONDS));

        assertEquals(population, held.population());
        assertEquals(number, held.number());
        assertNotEquals(number, engine.currentGeneration().number());
    }

    @Test
    @Timeout(30)
    @DisplayName("Close stops the simulation thread and is idempotent")
    void closeIsIdempotent() throws Exception {
        // Thread.getAllStackTraces() never lists virtual threads, so the simulation thread
        // cannot be looked up by name. A state callback runs on the thread that caused the
        // change, and RUNNING is always raised by the simulation thread itself.
        AtomicReference<Thread> simulationThread = new AtomicReference<>();
        engine.addListener(new EngineListener() {
            @Override
            public void onStateChanged(SimulationEngine.State state) {
                if (state == SimulationEngine.State.RUNNING) {
                    simulationThread.set(Thread.currentThread());
                }
            }
        });
        engine.start();
        await(() -> engine.currentGeneration().number() > 5L);

        Thread thread = simulationThread.get();
        assertNotNull(thread, "RUNNING was never reported");
        assertEquals("conway-simulation", thread.getName());
        assertTrue(thread.isVirtual(), "The simulation thread should be a virtual thread");

        engine.close();
        engine.close();

        assertEquals(SimulationEngine.State.STOPPED, engine.state());
        assertFalse(thread.isAlive(), "The simulation thread outlived close()");
    }

    @Test
    @DisplayName("Commands issued after close are ignored")
    void commandsAfterCloseAreIgnored() throws Exception {
        engine.close();
        Generation before = engine.currentGeneration();

        engine.step();
        engine.start();
        engine.toggleCell(1, 1);

        assertFalse(engine.sync(1, TimeUnit.SECONDS), "sync should not succeed after close");
        assertSame(before, engine.currentGeneration());
    }

    private static Pattern blinker() {
        return Pattern.parse("blinker", List.of("OOO"));
    }

    @Test
    @DisplayName("Partition counts are honored and reported by the engine")
    @Timeout(30)
    void partitionCountsAreHonored() throws Exception {
        // The band workers run on a dedicated ForkJoinPool whose parallelism is fixed at
        // construction, so there is no pool-sizing apparatus to test and nothing to resize
        // when the partition control moves. What still matters is that the requested
        // partition count is actually used and produces the same board, which
        // PartitionEquivalenceTest pins down; here we just confirm the engine runs
        // correctly across a range of counts on a banded board.
        try (SimulationEngine large = new SimulationEngine(BANDED_SIZE, BANDED_SIZE, 16)) {
            spanningBlocks(large, BANDED_SIZE);

            for (int partitions : new int[] {1, 2, 8, 16}) {
                large.setPartitions(partitions);
                step(large, 20);
                assertEquals(partitions, large.stats().partitions(),
                        "stats should report the requested partition count");
                assertNotEquals(SimulationEngine.State.FAILED, large.state());
            }
        }
    }

    /**
     * Two still lifes at opposite ends, so the active region's bounding box spans the whole
     * board permanently.
     *
     * <p>The engine only splits a generation across bands when the active rows can fill
     * them, so a small board or a small pattern is computed inline and never touches the
     * worker pool at all. Exercising the pool therefore needs a board tall enough that the
     * requested bands are actually used. Blocks rather than blinkers: a block is stable, so
     * the region never collapses.
     */
    private static void spanningBlocks(SimulationEngine target, int size) {
        for (int row : new int[] {1, size - 3}) {
            target.toggleCell(row, 1);
            target.toggleCell(row, 2);
            target.toggleCell(row + 1, 1);
            target.toggleCell(row + 1, 2);
        }
    }

    private static void step(SimulationEngine target, int generations) throws InterruptedException {
        for (int i = 0; i < generations; i++) {
            target.step();
        }
        target.sync(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5L);
        }
        fail("Condition was not met within " + TIMEOUT);
    }
}
