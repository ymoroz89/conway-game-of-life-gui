package com.conway.engine

/** Thrown when a generation cannot be computed. */
class SimulationException(message: String, cause: Throwable) : RuntimeException(message, cause)