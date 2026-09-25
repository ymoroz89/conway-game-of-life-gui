package com.conway.engine;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fan-out of low-frequency engine events to registered {@link EngineListener}s.
 *
 * <p>Split out of {@link SimulationEngine} because who is listening is a different reason
 * to change than what the engine computes.
 *
 * <h2>Threading</h2>
 * Registration happens on caller threads while notification happens on the simulation
 * thread, so the backing list is a {@link CopyOnWriteArrayList}: writes are rare, reads
 * happen on the simulation thread, and a read never blocks a registration or vice versa.
 *
 * <p>Callbacks run on the notifying thread. Implementations that touch Swing must marshal
 * to the event dispatch thread themselves; {@code LifeFrame} does.
 */
final class EngineListeners {

    private final List<EngineListener> listeners = new CopyOnWriteArrayList<>();

    void add(EngineListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    void stateChanged(SimulationEngine.State state) {
        for (EngineListener listener : listeners) {
            listener.onStateChanged(state);
        }
    }

    void error(Throwable error) {
        for (EngineListener listener : listeners) {
            listener.onError(error);
        }
    }
}
