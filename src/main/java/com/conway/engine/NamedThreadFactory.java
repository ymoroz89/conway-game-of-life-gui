package com.conway.engine;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates daemon threads with meaningful names.
 *
 * <p>Default executor threads are named {@code pool-N-thread-M}, which is useless in
 * a thread dump when several pools exist. Daemon status guarantees the JVM can exit
 * even if a shutdown path is missed.
 */
final class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger(1);

    NamedThreadFactory(String prefix) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    }
}
