package com.conway.engine;

import java.util.concurrent.CountDownLatch;

import com.conway.model.Pattern;

/**
 * The complete set of operations the simulation thread understands.
 *
 * <p>Modelling these as distinct types replaces an earlier
 * {@code runController(boolean, Boolean)} signature, in which four different behaviours
 * were encoded in a flag plus a three-valued {@code Boolean}.
 *
 * <p>The vocabulary splits in two. Commands that carry no data are constants of
 * {@link Signal}, because a singleton per operation is exactly what an enum is for.
 * Commands that carry data are records, because each submission needs its own values:
 * two {@code ToggleCell}s for different cells must be able to sit in the queue at the
 * same time, which enum constants could never express.
 *
 * <p>The interface is {@code sealed} and every variant is declared here, so the switch in
 * {@link SimulationEngine} that handles them is checked for exhaustiveness by the
 * compiler: adding a command without handling it does not compile. That is the open/closed
 * principle with a safety net — the vocabulary is open to extension, and the handler
 * cannot silently ignore the extension.
 *
 * <p>Commands are created on caller threads and consumed on the simulation thread, so
 * every variant is immutable.
 */
sealed interface Command {

    /** Operations that are pure signals, with no accompanying data. */
    enum Signal implements Command {
        /** Begin advancing generations continuously. */
        START,
        /** Stop advancing; the board is retained. */
        PAUSE,
        /** Advance exactly one generation. */
        STEP,
        /** Empty the board and reset the generation counter. */
        CLEAR,
        /** Stop the simulation thread. */
        SHUTDOWN
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
