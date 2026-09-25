package com.conway.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
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
 * caller-facing method does nothing but enqueue a {@link Command}; nothing outside
 * this class touches the board.
 *
 * <p>The board and its statistics are handed to readers as one immutable
 * {@link EngineSnapshot} published through a single {@code volatile} field. A reader
 * therefore sees either generation N or generation N+1 in full, always with the
 * throughput numbers that belong to it, and needs no lock. This is what allows the
 * Swing event dispatch thread to render while the simulation runs.
 *
 * <p>Pausing blocks on {@link BlockingQueue#take()} rather than
 * {@code Object.wait()}, so a step request enqueued at any moment is always honoured:
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
 * be checked by reading one class.
 *
 * <h2>Threads</h2>
 * The simulation thread is a <em>virtual</em> thread: it spends its life blocked in
 * {@code take()}/{@code Future.get()}, so it unmounts from any carrier while blocked and
 * costs nothing while idle. Virtual threads are daemon by
 * default, so the JVM can still exit if a shutdown path is missed.
 *
 * <p>The band workers are a <em>{@link ForkJoinPool}</em>, because the work is pure CPU
 * that never blocks — the case virtual threads do not address. Two properties earn it:
 *
 * <ul>
 *   <li><strong>Work stealing.</strong> Bands are uneven, since {@code computeBand} clips
 *       rule evaluation to the active region, and the cores are asymmetric (performance
 *       plus efficiency). A generation ends at a barrier, so the slowest band sets the
 *       pace; stealing lets a core that finishes early absorb the rest instead of idling.
 *   <li><strong>Cheaper hand-off.</strong> Per-worker deques avoid the single contended
 *       queue of a fixed pool, which matters at roughly 50,000 band dispatches a second.
 * </ul>
 *
 * <p>Measured with {@code GenerationBenchmark}'s three executor modes, interleaved: on the
 * default 201 board this is 12–19% faster than virtual threads at the recommended
 * partition count, and on an 801 board all three modes land within noise, where bands are
 * large enough that dispatch cost stops mattering.
 *
 * <p>Crucially this does <em>not</em> bring back the {@code ThreadPoolExecutor} sizing
 * apparatus that the Java 21 migration deleted. Parallelism is fixed at construction from
 * the core count and never tracks the partition count, so there is still no core size to
 * keep in sync, no keepalive and no thread factory.
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
    private final ExecutorService workers;
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

        // Parallelism is the core count, never the partition count: stealing redistributes
        // work, so unlike the old ThreadPoolExecutor there is nothing to resize when the
        // partition control moves. Capped by maxPartitions so a tiny board does not start
        // more carriers than it can ever use. Daemon by default, as before.
        this.workers = new ForkJoinPool(
                Math.min(maxPartitions, Runtime.getRuntime().availableProcessors()));

        this.rules = new LifeRules(workers);
        // The simulation thread spends its life blocked in take()/Future.get(), so a
        // virtual thread is the right fit: it unmounts from any carrier while blocked and
        // costs nothing while idle. Virtual threads are daemon
        // by default, so the JVM can still exit if a shutdown path is missed.
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
     * Stops the simulation thread and releases the worker executor.
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

        // Virtual threads are daemon, so this is hygiene rather than a requirement for the
        // JVM to exit. close() waits for in-flight bands to finish.
        workers.close();
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

    private void handle(Command command) throws InterruptedException {
        switch (command) {
            case Command.Signal.START -> {
                if (!running) {
                    running = true;
                    clock.startRun();
                    LOG.info("Run started with {} partition(s)", partitions);
                    setState(State.RUNNING);
                }
            }
            case Command.Signal.PAUSE -> {
                if (running) {
                    running = false;
                    logRunSummary();
                }
                setState(State.PAUSED);
            }
            case Command.Signal.STEP -> advance(false);
            case Command.Signal.CLEAR -> {
                running = false;
                clock.reset();
                board = Generation.empty(board.width(), board.height());
                publish(board, new SimulationStats(0L, 0L, 0L, partitions));
                LOG.info("Board cleared");
                setState(State.PAUSED);
            }
            case Command.ToggleCell toggle -> applyToggle(toggle);
            case Command.PlacePattern place -> {
                board = board.withPattern(place.pattern(), place.row(), place.col());
                publish(board, snapshot.stats());
                LOG.debug("Placed {} at ({}, {})", place.pattern().name(), place.row(), place.col());
            }
            case Command.SetPartitions setPartitions -> {
                if (partitions != setPartitions.count()) {
                    partitions = setPartitions.count();
                    LOG.info("Partition count changed to {}", partitions);
                    publish(board, new SimulationStats(
                            board.number(), clock.generations(), clock.elapsedNanos(), partitions));
                }
            }
            case Command.Barrier barrier -> barrier.latch().countDown();
            case Command.Signal.SHUTDOWN -> {
                if (running) {
                    running = false;
                    logRunSummary();
                }
                shutdownRequested = true;
            }
        }
    }

    private void applyToggle(Command.ToggleCell toggle) {
        Generation current = board;
        if (toggle.row() < 0 || toggle.row() >= current.height()
                || toggle.col() < 0 || toggle.col() >= current.width()) {
            return;
        }
        boolean live = !current.isAlive(toggle.row(), toggle.col());
        board = current.withCell(toggle.row(), toggle.col(), live);
        // The throughput counters are unaffected by an edit; republish the unchanged stats
        // with the new board so the navigation clock never rewinds.
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

    /** Single write point for the volatile snapshot: generation and stats change together. */
    private void publish(Generation generation, SimulationStats stats) {
        snapshot = new EngineSnapshot(generation, stats);
    }

    private void logRunSummary() {
        SimulationStats snapshot = this.snapshot.stats();
        LOG.info("Run finished: {} generations in {} s using {} partition(s), {} generations/s",
                snapshot.generationsInRun(),
                String.format("%.3f", snapshot.elapsedSeconds()),
                snapshot.partitions(),
                String.format("%.1f", snapshot.generationsPerSecond()));
    }

    private void setState(State newState) {
        if (state == newState) {
            return;
        }
        state = newState;
        listeners.stateChanged(newState);
    }

    /** Prevents callers blocked in {@link #sync} from waiting forever after shutdown. */
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
