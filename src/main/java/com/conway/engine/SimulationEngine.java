package com.conway.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conway.engine.rules.LifeRules;
import com.conway.engine.support.EngineSnapshot;
import com.conway.engine.support.SimulationStats;
import com.conway.model.Generation;
import com.conway.model.Pattern;

/**
 * Owns the board and runs the simulation.
 *
 * <h2>Threading model</h2>
 * The engine is a single-writer actor. Exactly one thread &mdash; the simulation
 * thread created in the constructor &mdash; ever mutates simulation state. Every
 * mutating method does nothing beyond validating or clamping its arguments and enqueueing
 * a {@link Command}; the reads return published {@code volatile} fields or values fixed at
 * construction; only {@link #sync} and {@link #close} block. Nothing outside this class
 * touches the board.
 *
 * <p>The board and its statistics are handed to readers as one immutable
 * {@link EngineSnapshot} published through a single {@code volatile} field. A reader
 * therefore sees either generation N or generation N+1 in full, always with the
 * throughput numbers that belong to it, and needs no lock. This is what allows the
 * Swing event dispatch thread to render while the simulation runs.
 *
 * <p>Pausing blocks on {@link BlockingQueue#take()} rather than
 * {@code Object.wait()}, so a step request enqueued at any moment is always honored:
 * there is no window in which a notification can be lost, and no spurious wakeup can
 * advance the board. Requests are queued, never dropped.
 *
 * <p>All public methods are safe to call from any thread.
 *
 * <h2>Collaborators</h2>
 * Three responsibilities that are not "own the board" live in their own types:
 * {@link Command} (the vocabulary callers submit), {@link RunClock} (throughput
 * accounting for the current run) and {@link EngineListeners} (fan-out of lifecycle
 * events). What stays here is deliberately not split further: the board, the command loop
 * that mutates it, and the thread that owns both. Separating those would spread the
 * single-writer invariant across objects, and the value of that invariant is that it can
 * be checked by reading one class. Within the class, {@code handle} only routes; each
 * command has its own small handler.
 *
 * <h2>Threads</h2>
 * The simulation thread is a <em>virtual</em> thread: it spends its life blocked in
 * {@code take()}/{@code scope.join()}, so it unmounts from any carrier while blocked and
 * costs nothing while idle. Virtual threads are daemon by
 * default, so the JVM can still exit if a shutdown path is missed.
 *
 * <p>The band workers are <em>virtual threads</em>, forked per band into a
 * {@link java.util.concurrent.StructuredTaskScope} that {@link com.conway.engine.rules.LifeRules}
 * opens and closes within one generation. That they are virtual follows from the paragraph
 * above: the thread that forks the bands and then waits on them is virtual, so the bands run
 * on the same scheduler it does. The work is pure CPU, which is not what virtual threads are
 * usually for, but on the default board the cost that dominates is the hand-off, not the
 * band: a band is about 10,000 cells, and three are forked per generation, tens of thousands
 * of times a second. On one scheduler the hand-off stays cheap:
 *
 * <ul>
 *   <li><strong>The waiter's carrier is reused.</strong> When the simulation thread blocks
 *       in {@code scope.join()} it unmounts, and its carrier is free in the same pool as
 *       the bands, so it can run one instead of idling.
 *   <li><strong>The wake-up is local.</strong> A band that finishes unparks the simulation
 *       thread from its own carrier: {@code VirtualThread.unpark} switches to the carrier
 *       before resubmitting, so the resubmission lands on that carrier's local deque rather
 *       than in an external submission queue.
 *   <li><strong>Work stealing is kept.</strong> The virtual-thread scheduler is itself a
 *       work-stealing {@code ForkJoinPool}, so uneven bands &mdash; {@code computeBand} clips
 *       rule evaluation to the active region &mdash; and asymmetric cores are still absorbed.
 * </ul>
 *
 * <p>A dedicated {@code ForkJoinPool} was tried and measured <strong>23% slower in the
 * application</strong>: 17,059 against 22,283 generations/s (medians of five interleaved
 * runs each, 2,000,000 generations in the GUI on JDK 21, no overlap between the arms). It
 * puts the bands on a second scheduler, so every generation crosses between two pools in
 * both directions. {@code GenerationBenchmark} reports the opposite ordering, and that is
 * a trap: it calls {@code next()} from {@code main}, a platform thread, and with a platform
 * caller neither executor shares a scheduler with the waiter. An executor for this class
 * has to be chosen with the caller the application actually has &mdash; see the README's
 * "Choosing the executor".
 *
 * <p>Like the {@code ForkJoinPool} before it, this keeps none of the
 * {@code ThreadPoolExecutor} sizing apparatus that the Java 21 migration deleted: no core
 * size, no keepalive, no thread factory, and nothing to resize when the partition control
 * moves.
 */
public final class SimulationEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SimulationEngine.class);

    private static final long SHUTDOWN_TIMEOUT_MS = 5_000L;

    /** Partition count before anything configures one. A single band runs inline. */
    private static final int INITIAL_PARTITIONS = 1;

    /** Lifecycle states reported to listeners. */
    public enum State {
        /** Idle; the board only changes in response to explicit commands. */
        PAUSED,
        /** Advancing generations continuously. */
        RUNNING,
        /** Shut down; no further commands are processed. */
        STOPPED,
        /** A generation could not be computed. The engine is paused and the error was reported. */
        FAILED
    }

    private final BlockingQueue<Command> commands = new LinkedBlockingQueue<>();
    private final EngineListeners listeners = new EngineListeners();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LifeRules rules;
    private final Thread simulationThread;
    private final int maxPartitions;

    /**
     * The single publication point. The board and its statistics are always observed
     * together, so a reader can never see a new generation with stale counters.
     * Written only by the simulation thread (and once by the constructor); read by
     * any thread.
     */
    private volatile EngineSnapshot snapshot;

    /** Lifecycle latch. A separate volatile rather than part of {@link #snapshot}: it is
     * written by {@link #close()} on the caller's thread too, and bundling it would let a
     * concurrent close overwrite a newer generation. */
    private volatile State state = State.PAUSED;

    // ---- Simulation-thread-confined state. Never read or written from anywhere else. ----
    private Generation board;
    private boolean running;
    private boolean shutdownRequested;
    private int partitions = INITIAL_PARTITIONS;
    private final RunClock clock = new RunClock();

    /**
     * Creates an engine with an empty board and starts its simulation thread paused.
     *
     * @param width         board width in cells
     * @param height        board height in cells
     * @param maxPartitions upper bound on concurrent row bands
     */
    public SimulationEngine(int width, int height, int maxPartitions) {
        if (maxPartitions < 1) {
            throw new IllegalArgumentException("maxPartitions must be at least 1 but was " + maxPartitions);
        }
        this.maxPartitions = maxPartitions;
        this.snapshot = EngineSnapshot.initial(width, height);
        this.board = snapshot.generation();

        // LifeRules runs each generation inside its own StructuredTaskScope, so no executor
        // is owned by the engine anymore.
        this.rules = new LifeRules();
        // The simulation thread spends its life blocked in take()/join(), so a
        // virtual thread is the right fit: it unmounts from any carrier while blocked and
        // costs nothing while idle. Virtual threads are daemon by
        // default, so the JVM can still exit if a shutdown path is missed.
        this.simulationThread = Thread.ofVirtual()
                .name("conway-simulation")
                .start(this::runLoop);
    }

    // ------------------------------------------------------------------
    // Commands. Each simply enqueues; none blocks on the simulation thread.
    // ------------------------------------------------------------------

    /** Begins advancing generations continuously. */
    public void start() {
        submit(Command.Signal.START);
    }

    /** Stops advancing generations. The board is retained. */
    public void pause() {
        submit(Command.Signal.PAUSE);
    }

    /** Advances exactly one generation. Requests are queued and never dropped. */
    public void step() {
        submit(Command.Signal.STEP);
    }

    /** Empties the board and resets the generation counter. */
    public void clear() {
        submit(Command.Signal.CLEAR);
    }

    /** Inverts the state of a single cell. Coordinates outside the board are ignored. */
    public void toggleCell(int row, int col) {
        submit(new Command.ToggleCell(row, col));
    }

    /** Stamps a pattern onto the board, clipping anything that falls outside it. */
    public void place(Pattern pattern, int row, int col) {
        submit(new Command.PlacePattern(Objects.requireNonNull(pattern, "pattern"), row, col));
    }

    /**
     * Sets how many row bands each generation is split into.
     *
     * <p>The change is applied by the simulation thread between generations, so it can
     * never tear against work in flight.
     *
     * @param count clamped to {@code [1, maxPartitions]}
     */
    public void setPartitions(int count) {
        submit(new Command.SetPartitions(Math.clamp(count, 1, maxPartitions)));
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * The most recently published board together with its statistics, as one immutable
     * bundle. Every call may return a newer instance; the result is safe to hold.
     */
    public EngineSnapshot snapshot() {
        return snapshot;
    }

    /**
     * The most recently published board. Every call may return a newer instance;
     * the returned snapshot is immutable and safe to hold.
     */
    public Generation currentGeneration() {
        return snapshot.generation();
    }

    /** Throughput measurement for the current run. */
    public SimulationStats stats() {
        return snapshot.stats();
    }

    public State state() {
        return state;
    }

    public int maxPartitions() {
        return maxPartitions;
    }

    public void addListener(EngineListener listener) {
        listeners.add(listener);
    }

    /**
     * Blocks until every command enqueued before this call has been processed.
     *
     * <p>Intended for tests and for deterministic start-up sequencing.
     *
     * @return {@code false} if the timeout expired or the engine shut down first
     */
    public boolean sync(long timeout, TimeUnit unit) throws InterruptedException {
        if (closed.get()) {
            return false;
        }
        CountDownLatch latch = new CountDownLatch(1);
        submit(new Command.Barrier(latch));
        return latch.await(timeout, unit);
    }

    /**
     * Stops the simulation thread.
     *
     * <p>Idempotent. Blocks until the thread has stopped or a timeout expires.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Shutting down simulation engine");
        commands.add(Command.Signal.SHUTDOWN);
        try {
            simulationThread.join(SHUTDOWN_TIMEOUT_MS);
            if (simulationThread.isAlive()) {
                LOG.warn("Simulation thread did not stop within {} ms; interrupting", SHUTDOWN_TIMEOUT_MS);
                simulationThread.interrupt();
                simulationThread.join(SHUTDOWN_TIMEOUT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        setState(State.STOPPED);
        LOG.info("Simulation engine stopped");
    }

    // ------------------------------------------------------------------
    // Simulation thread
    // ------------------------------------------------------------------

    private void submit(Command command) {
        if (closed.get()) {
            LOG.debug("Ignoring {} after shutdown", command);
            return;
        }
        commands.add(command);
    }

    private void runLoop() {
        LOG.info("Simulation thread started");
        try {
            while (!shutdownRequested) {
                // Running: drain pending commands without blocking, then advance.
                // Paused: block until something arrives, so an idle engine burns no CPU.
                Command command = running ? commands.poll() : commands.take();
                if (command != null) {
                    handle(command);
                } else {
                    advance(true);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("Simulation thread interrupted; stopping");
        } finally {
            releasePendingBarriers();
            LOG.info("Simulation thread finished");
        }
    }

    /**
     * Routes a single {@link Command} to its handler, on the simulation thread. The switch is
     * exhaustive over the sealed {@link Command} hierarchy, so a command without a handler
     * does not compile.
     */
    private void handle(Command command) throws InterruptedException {
        switch (command) {
            case Command.Signal.START -> handleStart();
            case Command.Signal.PAUSE -> handlePause();
            case Command.Signal.STEP -> handleStep();
            case Command.Signal.CLEAR -> handleClear();
            case Command.ToggleCell toggle -> handleToggleCell(toggle);
            case Command.PlacePattern place -> handlePlacePattern(place);
            case Command.SetPartitions set -> handleSetPartitions(set);
            case Command.Barrier barrier -> handleBarrier(barrier);
            case Command.Signal.SHUTDOWN -> handleShutdown();
        }
    }

    // --- Per-command handlers (small, intent-revealing, single responsibility) ---

    /**
     * Starts continuous advancement of generations if not already running.
     * Updates run clock and notifies listeners of {@link State#RUNNING}.
     */
    private void handleStart() {
        if (!running) {
            running = true;
            clock.startRun();
            LOG.info("Run started with {} partition(s)", partitions);
            setState(State.RUNNING);
        }
    }

    /**
     * Pauses the simulation if it is running and logs a run summary. Always leaves the
     * engine {@link State#PAUSED}, which also clears a {@link State#FAILED}.
     */
    private void handlePause() {
        if (running) {
            running = false;
            logRunSummary();
        }
        setState(State.PAUSED);
    }

    /**
     * Advances the simulation by exactly one generation (not counted in throughput).
     */
    private void handleStep() throws InterruptedException {
        advance(false);
    }

    /**
     * Clears the board, resets throughput counters, and publishes an empty snapshot.
     * Transitions the engine to {@link State#PAUSED}.
     */
    private void handleClear() {
        running = false;
        clock.reset();
        board = Generation.empty(board.width(), board.height());
        publish(board, new SimulationStats(0L, 0L, 0L, partitions));
        LOG.info("Board cleared");
        setState(State.PAUSED);
    }

    /**
     * Places a pattern onto the board (clipped to bounds) and republishes the snapshot.
     */
    private void handlePlacePattern(Command.PlacePattern place) {
        board = board.withPattern(place.pattern(), place.row(), place.col());
        publish(board, snapshot.stats());
        LOG.debug("Placed {} at ({}, {})", place.pattern().name(), place.row(), place.col());
    }

    /**
     * Updates the partitions used for banded computation and republishes stats if changed.
     */
    private void handleSetPartitions(Command.SetPartitions setPartitions) {
        if (partitions != setPartitions.count()) {
            partitions = setPartitions.count();
            LOG.info("Partition count changed to {}", partitions);
            publish(board, new SimulationStats(
                    board.number(), clock.generations(), clock.elapsedNanos(), partitions));
        }
    }

    /**
     * Releases the caller blocked in {@link #sync}: every command queued before this barrier
     * has now been processed.
     */
    private void handleBarrier(Command.Barrier barrier) {
        barrier.latch().countDown();
    }

    /**
     * Stops continuous advancement, logs a summary, and requests shutdown of the run loop.
     */
    private void handleShutdown() {
        if (running) {
            running = false;
            logRunSummary();
        }
        shutdownRequested = true;
    }

    /**
     * Toggles a single cell if its coordinates are on the board, and republishes the board
     * with unchanged statistics: an edit does not count toward throughput.
     */
    private void handleToggleCell(Command.ToggleCell toggle) {
        Generation current = board;
        if (toggle.row() < 0 || toggle.row() >= current.height()
                || toggle.col() < 0 || toggle.col() >= current.width()) {
            return;
        }
        boolean live = !current.isAlive(toggle.row(), toggle.col());
        board = current.withCell(toggle.row(), toggle.col(), live);
        // Republish the current statistics with the edited board, so an edit never resets
        // the status bar's generation count or rate.
        publish(board, snapshot.stats());
        LOG.debug("{} cell at (row {}, col {})", live ? "Added" : "Removed", toggle.row(), toggle.col());
    }

    /**
     * Computes and publishes the next generation.
     *
     * @param timed whether this advance counts toward the throughput measurement;
     *              manual steps are excluded so human think time does not skew the rate
     */
    private void advance(boolean timed) throws InterruptedException {
        try {
            Generation next = rules.next(board, partitions);
            if (timed) {
                clock.recordGeneration();
            }
            board = next;
            publish(next, new SimulationStats(
                    next.number(), clock.generations(), clock.elapsedNanos(), partitions));
        } catch (RuntimeException e) {
            running = false;
            LOG.error("Generation {} failed; simulation paused", board.number() + 1, e);
            setState(State.FAILED);
            listeners.error(e);
        }
    }

    /**
     * Single write point for the volatile snapshot: generation and stats change together,
     * ensuring readers always observe a consistent pair.
     */
    private void publish(Generation generation, SimulationStats stats) {
        snapshot = new EngineSnapshot(generation, stats);
    }

    /**
     * Logs a concise throughput summary for the last run period.
     */
    private void logRunSummary() {
        SimulationStats stats = snapshot.stats();
        LOG.info("Run finished: {} generations in {} s using {} partition(s), {} generations/s",
                stats.generationsInRun(),
                String.format("%.3f", stats.elapsedSeconds()),
                stats.partitions(),
                String.format("%.1f", stats.generationsPerSecond()));
    }

    /**
     * Updates the externally visible lifecycle {@link State} and notifies listeners
     * if it changed.
     */
    private void setState(State newState) {
        if (state == newState) {
            return;
        }
        state = newState;
        listeners.stateChanged(newState);
    }

    /**
     * Prevents callers blocked in {@link #sync(long, TimeUnit)} from waiting forever after
     * shutdown by draining the command queue and releasing any lingering barriers.
     */
    private void releasePendingBarriers() {
        List<Command> pending = new ArrayList<>();
        commands.drainTo(pending);
        for (Command command : pending) {
            if (command instanceof Command.Barrier(CountDownLatch latch)) {
                latch.countDown();
            }
        }
    }
}
