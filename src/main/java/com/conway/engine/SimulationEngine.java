package com.conway.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>Results are handed to readers as immutable {@link Generation} snapshots
 * published through a {@code volatile} field. A reader therefore sees either
 * generation N or generation N+1 in full, never a mixture of the two, and needs no
 * lock. This is what allows the Swing event dispatch thread to render while the
 * simulation runs.
 *
 * <p>Pausing blocks on {@link BlockingQueue#take()} rather than
 * {@code Object.wait()}, so a step request enqueued at any moment is always honoured:
 * there is no window in which a notification can be lost, and no spurious wakeup can
 * advance the board. Requests are queued, never dropped.
 *
 * <p>All public methods are safe to call from any thread.
 */
public final class SimulationEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SimulationEngine.class);

    private static final long SHUTDOWN_TIMEOUT_MS = 5_000L;
    private static final long WORKER_KEEPALIVE_SECONDS = 60L;

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
    private final List<EngineListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor workers;
    private final LifeRules rules;
    private final Thread simulationThread;
    private final int maxPartitions;

    private volatile Generation current;
    private volatile SimulationStats stats = SimulationStats.idle();
    private volatile State state = State.PAUSED;

    // ---- Simulation-thread-confined state. Never read or written from anywhere else. ----
    private boolean running;
    private boolean shutdownRequested;
    private int partitions = 1;
    private long generationsInRun;
    private long runStartNanos;
    private long elapsedNanos;

    /**
     * Creates an engine with an empty board and starts its simulation thread paused.
     *
     * @param width         board width in cells
     * @param height        board height in cells
     * @param maxPartitions upper bound on concurrent row bands; also the worker pool size
     */
    public SimulationEngine(int width, int height, int maxPartitions) {
        if (maxPartitions < 1) {
            throw new IllegalArgumentException("maxPartitions must be at least 1 but was " + maxPartitions);
        }
        this.maxPartitions = maxPartitions;
        this.current = Generation.empty(width, height);

        // One pool for the lifetime of the engine, not one per run. Core threads are
        // allowed to time out so an unused high partition count does not pin threads.
        this.workers = new ThreadPoolExecutor(
                maxPartitions, maxPartitions,
                WORKER_KEEPALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("conway-worker"));
        this.workers.allowCoreThreadTimeOut(true);

        this.rules = new LifeRules(workers);
        this.simulationThread = new Thread(this::runLoop, "conway-simulation");
        this.simulationThread.setDaemon(true);
        this.simulationThread.start();
    }

    // ------------------------------------------------------------------
    // Commands. Each simply enqueues; none blocks on the simulation thread.
    // ------------------------------------------------------------------

    /** Begins advancing generations continuously. */
    public void start() {
        submit(Command.Start.INSTANCE);
    }

    /** Stops advancing generations. The board is retained. */
    public void pause() {
        submit(Command.Pause.INSTANCE);
    }

    /** Advances exactly one generation. Requests are queued and never dropped. */
    public void step() {
        submit(Command.Step.INSTANCE);
    }

    /** Empties the board and resets the generation counter. */
    public void clear() {
        submit(Command.Clear.INSTANCE);
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
        submit(new Command.SetPartitions(Math.max(1, Math.min(count, maxPartitions))));
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * The most recently published board. Every call may return a newer instance;
     * the returned snapshot is immutable and safe to hold.
     */
    public Generation currentGeneration() {
        return current;
    }

    /** Throughput measurement for the current run. */
    public SimulationStats stats() {
        return stats;
    }

    public State state() {
        return state;
    }

    public int maxPartitions() {
        return maxPartitions;
    }

    public void addListener(EngineListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
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
     * Stops the simulation thread and releases the worker pool.
     *
     * <p>Idempotent. Blocks until the thread has stopped or a timeout expires.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Shutting down simulation engine");
        commands.add(Command.Shutdown.INSTANCE);
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

        workers.shutdown();
        try {
            if (!workers.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
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

    private void handle(Command command) throws InterruptedException {
        if (command instanceof Command.Start) {
            if (!running) {
                running = true;
                generationsInRun = 0L;
                elapsedNanos = 0L;
                runStartNanos = System.nanoTime();
                LOG.info("Run started with {} partition(s)", partitions);
                setState(State.RUNNING);
            }
        } else if (command instanceof Command.Pause) {
            if (running) {
                running = false;
                logRunSummary();
            }
            setState(State.PAUSED);
        } else if (command instanceof Command.Step) {
            advance(false);
        } else if (command instanceof Command.Clear) {
            running = false;
            generationsInRun = 0L;
            elapsedNanos = 0L;
            current = Generation.empty(current.width(), current.height());
            stats = new SimulationStats(0L, 0L, 0L, partitions);
            LOG.info("Board cleared");
            setState(State.PAUSED);
        } else if (command instanceof Command.ToggleCell toggle) {
            applyToggle(toggle);
        } else if (command instanceof Command.PlacePattern place) {
            current = current.withPattern(place.pattern(), place.row(), place.col());
            LOG.debug("Placed {} at ({}, {})", place.pattern().name(), place.row(), place.col());
        } else if (command instanceof Command.SetPartitions setPartitions) {
            if (partitions != setPartitions.count()) {
                partitions = setPartitions.count();
                LOG.info("Partition count changed to {}", partitions);
                stats = new SimulationStats(
                        stats.generation(), stats.generationsInRun(), stats.elapsedNanos(), partitions);
            }
        } else if (command instanceof Command.Barrier barrier) {
            barrier.latch().countDown();
        } else if (command instanceof Command.Shutdown) {
            if (running) {
                running = false;
                logRunSummary();
            }
            shutdownRequested = true;
        }
    }

    private void applyToggle(Command.ToggleCell toggle) {
        Generation board = current;
        if (toggle.row() < 0 || toggle.row() >= board.height()
                || toggle.col() < 0 || toggle.col() >= board.width()) {
            return;
        }
        boolean live = !board.isAlive(toggle.row(), toggle.col());
        current = board.withCell(toggle.row(), toggle.col(), live);
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
            Generation next = rules.next(current, partitions);
            current = next;
            if (timed) {
                generationsInRun++;
                elapsedNanos = System.nanoTime() - runStartNanos;
            }
            stats = new SimulationStats(next.number(), generationsInRun, elapsedNanos, partitions);
        } catch (InterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            running = false;
            LOG.error("Generation {} failed; simulation paused", current.number() + 1, e);
            setState(State.FAILED);
            for (EngineListener listener : listeners) {
                listener.onError(e);
            }
        }
    }

    private void logRunSummary() {
        SimulationStats snapshot = stats;
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
        for (EngineListener listener : listeners) {
            listener.onStateChanged(newState);
        }
    }

    /** Prevents callers blocked in {@link #sync} from waiting forever after shutdown. */
    private void releasePendingBarriers() {
        List<Command> pending = new ArrayList<>();
        commands.drainTo(pending);
        for (Command command : pending) {
            if (command instanceof Command.Barrier barrier) {
                barrier.latch().countDown();
            }
        }
    }

    /**
     * The complete set of operations the simulation thread understands.
     *
     * <p>Modelling these as distinct types replaces the previous
     * {@code runController(boolean, Boolean)} signature, in which four different
     * behaviours were encoded in a flag plus a three-valued {@code Boolean}.
     */
    private sealed interface Command {

        record Start() implements Command {
            static final Start INSTANCE = new Start();
        }

        record Pause() implements Command {
            static final Pause INSTANCE = new Pause();
        }

        record Step() implements Command {
            static final Step INSTANCE = new Step();
        }

        record Clear() implements Command {
            static final Clear INSTANCE = new Clear();
        }

        record Shutdown() implements Command {
            static final Shutdown INSTANCE = new Shutdown();
        }

        record ToggleCell(int row, int col) implements Command {
        }

        record PlacePattern(Pattern pattern, int row, int col) implements Command {
        }

        record SetPartitions(int count) implements Command {
        }

        record Barrier(CountDownLatch latch) implements Command {
        }
    }
}
