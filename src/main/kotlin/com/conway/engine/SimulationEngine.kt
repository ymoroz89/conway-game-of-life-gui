package com.conway.engine

import com.conway.model.Generation
import com.conway.model.Pattern
import org.slf4j.LoggerFactory
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the board and runs the simulation.
 *
 * The engine is a single-writer actor. Exactly one thread -- the simulation thread
 * created in the constructor -- ever mutates simulation state. Every caller-facing
 * method does nothing but enqueue a [Command]; nothing outside this class touches the
 * board.
 *
 * The board and its statistics are handed to readers as one immutable [EngineSnapshot]
 * published through a single `@Volatile` field. A reader therefore sees either
 * generation N or generation N+1 in full, always with the throughput numbers that
 * belong to it, and needs no lock. This is what allows the UI thread to render while
 * the simulation runs.
 *
 * Pausing blocks on [BlockingQueue.take] rather than `Object.wait()`, so a step
 * request enqueued at any moment is always honoured: there is no window in which a
 * notification can be lost, and no spurious wakeup can advance the board. Requests are
 * queued, never dropped.
 *
 * Both the simulation thread and the band workers are virtual threads. The simulation
 * thread spends its life blocked in `take()`/`Future.get()` and parks between
 * generations, so it unmounts from any carrier while blocked and costs nothing while
 * idle. Virtual threads are daemon by default, so the JVM can still exit if a
 * shutdown path is missed.
 */
class SimulationEngine(
    private val width: Int,
    private val height: Int,
    val maxPartitions: Int,
) : AutoCloseable {

    private val commands: BlockingQueue<Command> = LinkedBlockingQueue()
    private val listeners = CopyOnWriteArrayList<EngineListener>()
    private val closed = AtomicBoolean()
    private val workers: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val rules: LifeRules = LifeRules(workers)
    private val simulationThread: Thread =
        Thread.ofVirtual().name("conway-simulation").start { runLoop() }

    /** The single publication point; written only by the simulation thread (and once by
     *  the constructor), read by any thread. */
    @Volatile
    private var published: EngineSnapshot

    /**
     * Lifecycle latch. A separate volatile rather than part of [published]: it is written by
     * [close] on the caller's thread too, and bundling it would let a concurrent close
     * overwrite a newer generation.
     */
    @Volatile
    var state: State = State.PAUSED
        private set

    // ---- Simulation-thread-confined state. Never read or written from anywhere else. ----
    private var board: Generation
    private var running = false
    private var shutdownRequested = false
    private var partitions = INITIAL_PARTITIONS
    private var generationsInRun = 0L
    private var runStartNanos = 0L
    private var elapsedNanos = 0L

    init {
        require(maxPartitions >= 1) { "maxPartitions must be at least 1 but was $maxPartitions" }
        this.published = EngineSnapshot.initial(width, height)
        this.board = published.generation
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(SimulationEngine::class.java)

        private const val SHUTDOWN_TIMEOUT_MS = 5_000L

        /** Partition count before anything configures one. A single band runs inline. */
        private const val INITIAL_PARTITIONS = 1
    }

    /** Lifecycle states reported to listeners. */
    enum class State {
        /** Idle; the board only changes in response to explicit commands. */
        PAUSED,

        /** Advancing generations continuously. */
        RUNNING,

        /** Shut down; no further commands are processed. */
        STOPPED,

        /** A generation could not be computed. The engine is paused and the error was reported. */
        FAILED,
    }

    // ------------------------------------------------------------------
    // Commands. Each simply enqueues; none blocks on the simulation thread.
    // ------------------------------------------------------------------

    /** Begins advancing generations continuously. */
    fun start() = submit(Command.Start)

    /** Stops advancing generations. The board is retained. */
    fun pause() = submit(Command.Pause)

    /** Advances exactly one generation. Requests are queued and never dropped. */
    fun step() = submit(Command.Step)

    /** Empties the board and resets the generation counter. */
    fun clear() = submit(Command.Clear)

    /** Inverts the state of a single cell. Coordinates outside the board are ignored. */
    fun toggleCell(row: Int, col: Int) = submit(Command.ToggleCell(row, col))

    /** Stamps a [pattern] onto the board, clipping anything that falls outside it. */
    fun place(pattern: Pattern, row: Int, col: Int) =
        submit(Command.PlacePattern(requireNotNull(pattern) { "pattern" }, row, col))

    /**
     * Sets how many row bands each generation is split into. The change is applied by the
     * simulation thread between generations, so it can never tear against work in flight.
     *
     * @param count clamped to `[1, maxPartitions]`
     */
    fun setPartitions(count: Int) {
        submit(Command.SetPartitions(count.coerceIn(1, maxPartitions)))
    }
// ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** The most recently published board together with its statistics, as one immutable
     *  bundle. Every call may return a newer instance; the result is safe to hold. */
    fun snapshot(): EngineSnapshot = published

    /** The most recently published board; immutable and safe to hold. */
    fun currentGeneration(): Generation = published.generation

    /** Throughput measurement for the current run. */
    fun stats(): SimulationStats = published.stats

    fun addListener(listener: EngineListener) {
        listeners.add(requireNotNull(listener) { "listener" })
    }

    /**
     * Blocks until every command enqueued before this call has been processed.
     *
     * @return `false` if the timeout expired or the engine shut down first
     */
    @Throws(InterruptedException::class)
    fun sync(timeout: Long, unit: TimeUnit): Boolean {
        if (closed.get()) return false
        val latch = CountDownLatch(1)
        submit(Command.Barrier(latch))
        return latch.await(timeout, unit)
    }

    /**
     * Stops the simulation thread and releases the worker executor. Idempotent. Blocks
     * until the thread has stopped or a timeout expires.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        LOG.info("Shutting down simulation engine")
        commands.add(Command.Shutdown)
        try {
            simulationThread.join(SHUTDOWN_TIMEOUT_MS)
            if (simulationThread.isAlive) {
                LOG.warn("Simulation thread did not stop within {} ms; interrupting", SHUTDOWN_TIMEOUT_MS)
                simulationThread.interrupt()
                simulationThread.join(SHUTDOWN_TIMEOUT_MS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // Virtual threads are daemon, so this is hygiene rather than a requirement for the
        // JVM to exit. close() waits for in-flight bands to finish.
        workers.close()
        setState(State.STOPPED)
        LOG.info("Simulation engine stopped")
    }

    private fun submit(command: Command) {
        if (closed.get()) {
            LOG.debug("Ignoring {} after shutdown", command)
            return
        }
        commands.add(command)
    }

    private fun runLoop() {
        LOG.info("Simulation thread started")
        try {
            while (!shutdownRequested) {
                // Running: drain pending commands without blocking, then advance.
                // Paused: block until something arrives, so an idle engine burns no CPU.
                val command = if (running) commands.poll() else commands.take()
                if (command != null) {
                    handle(command)
                } else {
                    advance(timed = true)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LOG.info("Simulation thread interrupted; stopping")
        } finally {
            releasePendingBarriers()
            LOG.info("Simulation thread finished")
        }
    }
private fun handle(command: Command) {
        when (command) {
            is Command.Start -> {
                if (!running) {
                    running = true
                    generationsInRun = 0L
                    elapsedNanos = 0L
                    runStartNanos = System.nanoTime()
                    LOG.info("Run started with {} partition(s)", partitions)
                    setState(State.RUNNING)
                }
            }
            is Command.Pause -> {
                if (running) {
                    running = false
                    logRunSummary()
                }
                setState(State.PAUSED)
            }
            is Command.Step -> advance(timed = false)
            is Command.Clear -> {
                running = false
                generationsInRun = 0L
                elapsedNanos = 0L
                board = Generation.empty(board.width, board.height)
                publish(board, SimulationStats(0L, 0L, 0L, partitions))
                LOG.info("Board cleared")
                setState(State.PAUSED)
            }
            is Command.ToggleCell -> applyToggle(command)
            is Command.PlacePattern -> {
                board = board.withPattern(command.pattern, command.row, command.col)
                publish(board, published.stats)
                LOG.debug("Placed {} at ({}, {})", command.pattern.name, command.row, command.col)
            }
            is Command.SetPartitions -> {
                if (partitions != command.count) {
                    partitions = command.count
                    LOG.info("Partition count changed to {}", partitions)
                    publish(board, SimulationStats(board.number, generationsInRun, elapsedNanos, partitions))
                }
            }
            is Command.Barrier -> command.latch.countDown()
            is Command.Shutdown -> {
                if (running) {
                    running = false
                    logRunSummary()
                }
                shutdownRequested = true
            }
        }
    }

    private fun applyToggle(toggle: Command.ToggleCell) {
        val current = board
        if (toggle.row < 0 || toggle.row >= current.height ||
            toggle.col < 0 || toggle.col >= current.width
        ) {
            return
        }
        val live = !current.isAlive(toggle.row, toggle.col)
        board = current.withCell(toggle.row, toggle.col, live)
        // The throughput counters are unaffected by an edit; republish the unchanged stats
        // with the new board so the navigation clock never rewinds.
        publish(board, published.stats)
        LOG.debug("{} cell at (row {}, col {})", if (live) "Added" else "Removed", toggle.row, toggle.col)
    }

    /**
     * Computes and publishes the next generation.
     *
     * @param timed whether this advance counts toward the throughput measurement; manual
     *              steps are excluded so human think time does not skew the rate
     */
    private fun advance(timed: Boolean) {
        try {
            val next = rules.next(board, partitions)
            if (timed) {
                generationsInRun++
                elapsedNanos = System.nanoTime() - runStartNanos
            }
            board = next
            publish(next, SimulationStats(next.number, generationsInRun, elapsedNanos, partitions))
        } catch (e: InterruptedException) {
            throw e
        } catch (e: RuntimeException) {
            running = false
            LOG.error("Generation {} failed; simulation paused", board.number + 1, e)
            setState(State.FAILED)
            for (listener in listeners) {
                listener.onError(e)
            }
        }
    }

    /** Single write point for the volatile snapshot: generation and stats change together. */
    private fun publish(generation: Generation, stats: SimulationStats) {
        published = EngineSnapshot(generation, stats)
    }

    private fun logRunSummary() {
        val snapshot = this.published.stats
        LOG.info(
            "Run finished: {} generations in {} s using {} partition(s), {} generations/s",
            snapshot.generationsInRun,
            String.format("%.3f", snapshot.elapsedSeconds),
            snapshot.partitions,
            String.format("%.1f", snapshot.generationsPerSecond),
        )
    }

    private fun setState(newState: State) {
        if (state == newState) return
        state = newState
        for (listener in listeners) {
            listener.onStateChanged(newState)
        }
    }

    /** Prevents callers blocked in [sync] from waiting forever after shutdown. */
    private fun releasePendingBarriers() {
        val pending = ArrayList<Command>()
        commands.drainTo(pending)
        for (command in pending) {
            if (command is Command.Barrier) {
                command.latch.countDown()
            }
        }
    }

    /**
     * The complete set of operations the simulation thread understands.
     *
     * Modelling these as distinct types replaces the previous
     * `runController(boolean, Boolean)` signature, in which four different behaviours
     * were encoded in a flag plus a three-valued `Boolean`.
     */
    sealed interface Command {
        data object Start : Command
        data object Pause : Command
        data object Step : Command
        data object Clear : Command
        data object Shutdown : Command
        data class ToggleCell(val row: Int, val col: Int) : Command
        data class PlacePattern(val pattern: Pattern, val row: Int, val col: Int) : Command
        data class SetPartitions(val count: Int) : Command
        data class Barrier(val latch: CountDownLatch) : Command
    }
}