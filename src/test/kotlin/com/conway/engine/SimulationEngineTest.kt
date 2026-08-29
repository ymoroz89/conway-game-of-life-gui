package com.conway.engine

import com.conway.model.Generation
import com.conway.model.Pattern
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SimulationEngineTest {

    companion object {
        private const val SIZE = 32

        /**
         * Tall enough for the engine to actually use eight bands: it allows one band per 50
         * active rows, so eight bands need at least 400.
         */
        private const val BANDED_SIZE = 420
        private val TIMEOUT = Duration.ofSeconds(10)
    }

    private lateinit var engine: SimulationEngine

    @BeforeEach
    fun setUp() {
        engine = SimulationEngine(SIZE, SIZE, 4)
    }

    @AfterEach
    fun tearDown() {
        engine.close()
    }

    @Test
    @DisplayName("A step advances exactly one generation")
    fun stepAdvancesOneGeneration() {
        assertEquals(0L, engine.currentGeneration().number)

        engine.step()
        assertTrue(engine.sync(5, TimeUnit.SECONDS))

        assertEquals(1L, engine.currentGeneration().number)
    }

    @Test
    @Timeout(30)
    @DisplayName("Queued step requests are never dropped")
    fun queuedStepsAreAllHonoured() {
        // The previous wait/notify design silently discarded a step request whenever
        // the controller thread was not already parked in wait(). Because commands are
        // queued rather than signalled, every request must be applied exactly once.
        val steps = 200
        repeat(steps) { engine.step() }
        assertTrue(engine.sync(20, TimeUnit.SECONDS))

        assertEquals(steps.toLong(), engine.currentGeneration().number)
    }

    @Test
    @Timeout(30)
    @DisplayName("Start advances continuously and pause stops it")
    fun startAndPause() {
        engine.place(blinker(), 4, 4)
        engine.start()

        await { engine.currentGeneration().number > 25L }
        assertEquals(SimulationEngine.State.RUNNING, engine.state)

        engine.pause()
        assertTrue(engine.sync(5, TimeUnit.SECONDS))
        val settled = engine.currentGeneration().number

        Thread.sleep(200L)
        assertEquals(settled, engine.currentGeneration().number,
            "The board advanced after the engine was paused")
        assertEquals(SimulationEngine.State.PAUSED, engine.state)
    }

    @Test
    @Timeout(30)
    @DisplayName("Throughput statistics are recorded for a run")
    fun statsAreRecorded() {
        engine.place(blinker(), 4, 4)
        engine.start()
        await { engine.stats().generationsInRun > 50L }
        engine.pause()
        assertTrue(engine.sync(5, TimeUnit.SECONDS))

        val stats = engine.stats()
        assertTrue(stats.elapsedNanos > 0L, "Elapsed time was not measured")
        assertTrue(stats.generationsPerSecond > 0.0,
            "Rate should be positive but was ${stats.generationsPerSecond}")
    }

    @Test
    @DisplayName("Toggling a cell flips it and is idempotent in pairs")
    fun toggleCell() {
        engine.toggleCell(3, 7)
        assertTrue(engine.sync(5, TimeUnit.SECONDS))
        assertTrue(engine.currentGeneration().isAlive(3, 7))
        assertTrue(engine.currentGeneration().wasEverAlive(3, 7))

        engine.toggleCell(3, 7)
        assertTrue(engine.sync(5, TimeUnit.SECONDS))
        assertFalse(engine.currentGeneration().isAlive(3, 7))
        assertFalse(engine.currentGeneration().wasEverAlive(3, 7),
            "Removing a cell should return it to unpopulated, not recently dead")
    }

    @Test
    @DisplayName("Out-of-bounds toggles are ignored rather than throwing")
    fun outOfBoundsToggleIsIgnored() {
        engine.toggleCell(-1, 0)
        engine.toggleCell(0, SIZE)
        engine.toggleCell(SIZE * 4, SIZE * 4)
        assertTrue(engine.sync(5, TimeUnit.SECONDS))

        assertEquals(0, engine.currentGeneration().population)
        assertNotEquals(SimulationEngine.State.FAILED, engine.state)
    }

    @Test
    @DisplayName("Clear empties the board and resets the generation counter")
    fun clearResetsEverything() {
        engine.place(blinker(), 4, 4)
        engine.step()
        engine.step()
        assertTrue(engine.sync(5, TimeUnit.SECONDS))
        assertTrue(engine.currentGeneration().population > 0)

        engine.clear()
        assertTrue(engine.sync(5, TimeUnit.SECONDS))

        val cleared = engine.currentGeneration()
        assertEquals(0, cleared.population)
        assertEquals(0L, cleared.number)
    }

    @Test
    @DisplayName("A placed pattern larger than the board is clipped, not rejected")
    fun oversizedPatternIsClipped() {
        val small = SimulationEngine(4, 4, 2)
        try {
            small.place(blinker(), 3, 3)
            assertTrue(small.sync(5, TimeUnit.SECONDS))
            assertNotEquals(SimulationEngine.State.FAILED, small.state)
            assertEquals(1, small.currentGeneration().population)
        } finally {
            small.close()
        }
    }

    @Test
    @DisplayName("Changing the partition count does not disturb the board")
    fun partitionCountIsAppliedSafely() {
        engine.place(blinker(), 4, 4)
        engine.setPartitions(4)
        engine.step()
        assertTrue(engine.sync(5, TimeUnit.SECONDS))
        val withFour = engine.currentGeneration()

        engine.clear()
        engine.place(blinker(), 4, 4)
        engine.setPartitions(1)
        engine.step()
        assertTrue(engine.sync(5, TimeUnit.SECONDS))

        assertEquals(withFour, engine.currentGeneration())
    }

    @Test
    @DisplayName("The partition count is clamped to the configured maximum")
    fun partitionCountIsClamped() {
        engine.setPartitions(9999)
        engine.step()
        assertTrue(engine.sync(5, TimeUnit.SECONDS))

        assertEquals(engine.maxPartitions, engine.stats().partitions)
    }

    @Test
    @Timeout(60)
    @DisplayName("Concurrent edits during a run neither corrupt the board nor fail")
    fun concurrentEditsAreSafe() {
        // Editing used to write to the shared matrix straight from the event dispatch
        // thread while the simulation thread wrote to the same array. Edits are now
        // queued to the single writer, so hammering them during a run must be benign.
        engine.start()
        await { engine.currentGeneration().number > 5L }

        val threads = 4
        val editsPerThread = 250
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failures = CopyOnWriteArrayList<Throwable>()

        for (t in 0 until threads) {
            val offset = t
            Thread({
                try {
                    ready.countDown()
                    go.await()
                    for (i in 0 until editsPerThread) {
                        engine.toggleCell((offset * 7 + i) % SIZE, (offset * 5 + i) % SIZE)
                        // Readers run concurrently too; a snapshot must always be consistent.
                        val snapshot = engine.currentGeneration()
                        if (snapshot.width != SIZE || snapshot.height != SIZE) {
                            failures.add(AssertionError("Observed a malformed snapshot"))
                        }
                    }
                } catch (e: Throwable) {
                    failures.add(e)
                } finally {
                    done.countDown()
                }
            }, "editor-$t").apply {
                isDaemon = true
                start()
            }
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS))
        go.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "Editor threads did not finish")

        engine.pause()
        assertTrue(engine.sync(10, TimeUnit.SECONDS))

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "Concurrent editing produced ${failures.size} failure(s); first was:", failures[0],
            )
        }
        assertNotEquals(SimulationEngine.State.FAILED, engine.state)
    }

    @Test
    @DisplayName("Snapshots are immutable, so a held reference never changes")
    fun heldSnapshotsDoNotChange() {
        engine.place(blinker(), 4, 4)
        assertTrue(engine.sync(5, TimeUnit.SECONDS))

        val held = engine.currentGeneration()
        val population = held.population
        val number = held.number

        engine.step()
        engine.step()
        assertTrue(engine.sync(5, TimeUnit.SECONDS))

        assertEquals(population, held.population)
        assertEquals(number, held.number)
        assertNotEquals(number, engine.currentGeneration().number)
    }

    @Test
    @Timeout(30)
    @DisplayName("Close stops the simulation thread and is idempotent")
    fun closeIsIdempotent() {
        engine.start()
        await { engine.currentGeneration().number > 5L }

        engine.close()
        engine.close()

        assertEquals(SimulationEngine.State.STOPPED, engine.state)
        assertFalse(threadIsAlive("conway-simulation"), "The simulation thread outlived close()")
    }

    @Test
    @DisplayName("Commands issued after close are ignored")
    fun commandsAfterCloseAreIgnored() {
        engine.close()
        val before = engine.currentGeneration()

        engine.step()
        engine.start()
        engine.toggleCell(1, 1)

        assertFalse(engine.sync(1, TimeUnit.SECONDS), "sync should not succeed after close")
        assertSame(before, engine.currentGeneration())
    }

    @Test
    @DisplayName("Partition counts are honoured with the virtual-thread executor")
    @Timeout(30)
    fun partitionCountsAreHonouredWithVirtualThreads() {
        // The band workers now run on a virtual-thread-per-task executor, which has no
        // pool to size: a virtual thread is created per band and retired when the band
        // finishes. The previous pool-sizing tests pinned ThreadPoolExecutor internals
        // (core size, keepalive, thread count) that no longer exist. What still matters
        // is that the requested partition count is actually used and produces the same
        // board, which PartitionEquivalenceTest pins down; here we just confirm the
        // engine runs correctly across a range of counts on a banded board.
        SimulationEngine(BANDED_SIZE, BANDED_SIZE, 16).use { large ->
            spanningBlocks(large, BANDED_SIZE)

            for (partitions in intArrayOf(1, 2, 8, 16)) {
                large.setPartitions(partitions)
                step(large, 20)
                assertEquals(partitions, large.stats().partitions,
                    "stats should report the requested partition count")
                assertNotEquals(SimulationEngine.State.FAILED, large.state)
            }
        }
    }

    @Test
    @DisplayName("An invalid partition ceiling is rejected before any thread is started")
    fun invalidMaxPartitionsIsRejectedBeforeAllocating() {
        // Kotlin runs initialisers in declaration order, so the check has to come before the
        // executor and the simulation thread are created. When it did not, the constructor
        // threw but left both running with no reference left to close them.
        for (invalid in intArrayOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                SimulationEngine(SIZE, SIZE, invalid)
            }
        }
    }

    @Test
    @DisplayName("The board is fully initialised before the simulation thread reads it")
    fun boardIsInitialisedBeforeTheThreadStarts() {
        // The simulation thread must not observe a default-valued board or partition count.
        // Constructing and immediately stepping is the tightest race available from here.
        SimulationEngine(SIZE, SIZE, 4).use { fresh ->
            fresh.step()
            assertTrue(fresh.sync(TIMEOUT.seconds, TimeUnit.SECONDS))
            assertEquals(1L, fresh.currentGeneration().number)
            assertEquals(SIZE, fresh.currentGeneration().width)
            assertNotEquals(SimulationEngine.State.FAILED, fresh.state)
        }
    }

    private fun blinker(): Pattern = Pattern.parse("blinker", listOf("OOO"))

    /**
     * Two still lifes at opposite ends, so the active region's bounding box spans the whole
     * board permanently.
     *
     * The engine only splits a generation across bands when the active rows can fill
     * them, so a small board or a small pattern is computed inline and never touches the
     * worker pool at all. Exercising the pool therefore needs a board tall enough that the
     * requested bands are actually used. Blocks rather than blinkers: a block is stable, so
     * the region never collapses.
     */
    private fun spanningBlocks(target: SimulationEngine, size: Int) {
        for (row in intArrayOf(1, size - 3)) {
            target.toggleCell(row, 1)
            target.toggleCell(row, 2)
            target.toggleCell(row + 1, 1)
            target.toggleCell(row + 1, 2)
        }
    }

    private fun step(target: SimulationEngine, generations: Int) {
        for (i in 0 until generations) {
            target.step()
        }
        target.sync(TIMEOUT.seconds, TimeUnit.SECONDS)
    }

    private fun threadIsAlive(name: String): Boolean =
        Thread.getAllStackTraces().keys.any { it.name == name && it.isAlive }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5L)
        }
        return fail("Condition was not met within $TIMEOUT")
    }
}
