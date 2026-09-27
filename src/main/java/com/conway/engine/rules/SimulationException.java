package com.conway.engine.rules;

import java.io.Serial;

/** Thrown when a generation cannot be computed. */
public class SimulationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SimulationException(String message, Throwable cause) {
        super(message, cause);
    }
}
