package com.conway.engine;

/** Thrown when a generation cannot be computed. */
public class SimulationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SimulationException(String message, Throwable cause) {
        super(message, cause);
    }
}
