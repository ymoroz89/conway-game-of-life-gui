package com.conway.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Loads {@link Pattern}s from plaintext {@code .cells} resources on the classpath. */
public final class PatternLoader {

    /** Classpath location of the Gosper glider gun shipped with the application. */
    public static final String GOSPER_GLIDER_GUN = "/patterns/gosper-glider-gun.cells";

    /**
     * The bundled gun, read and parsed at most once.
     *
     * <p>A {@link LazyConstant} (JEP 531, preview in JDK 27) rather than a plain
     * {@code static final}: the resource is opened on first use instead of at class
     * initialisation, so nothing that only calls {@link #fromResource} with its own path
     * pays for it, and the two callers that do want it share one instance instead of
     * re-reading and re-parsing the file. {@link Pattern} is immutable and its cell array
     * never escapes, so one shared instance is safe for any number of threads.
     *
     * <p>What this replaces is the idiom, not a measurement: the file is a few hundred
     * bytes and was parsed once or twice per JVM, so there was no throughput problem to
     * solve. The point is that at-most-once initialisation now needs no holder class and no
     * double-checked lock, the field cannot be observed half-built, and the JIT may treat
     * the value as a true constant once set &mdash; which a lazily assigned
     * {@code static Pattern} field can never be.
     */
    private static final LazyConstant<Pattern> BUNDLED_GLIDER_GUN =
            LazyConstant.of(() -> fromResource(GOSPER_GLIDER_GUN));

    private PatternLoader() {
    }

    /**
     * The Gosper glider gun shipped with the application.
     *
     * <p>Immutable and shared: callers that want a variant derive one, as
     * {@link Pattern#rotate180()} does.
     *
     * @throws IllegalArgumentException if the bundled resource is missing or malformed
     * @throws java.io.UncheckedIOException if it cannot be read
     */
    public static Pattern bundledGliderGun() {
        // A failed computing function leaves the constant uninitialised and surfaces as
        // NoSuchElementException wrapping the real cause, so unwrap it: callers of this
        // method are entitled to the same exceptions fromResource throws directly.
        try {
            return BUNDLED_GLIDER_GUN.get();
        } catch (NoSuchElementException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    /**
     * Loads a pattern from a classpath resource.
     *
     * @throws IllegalArgumentException if the resource does not exist or is malformed
     * @throws UncheckedIOException     if the resource cannot be read
     */
    public static Pattern fromResource(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (InputStream in = PatternLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Pattern resource not found: " + resourcePath);
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            return Pattern.parse(nameOf(resourcePath), lines);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read pattern resource " + resourcePath, e);
        }
    }

    private static String nameOf(String resourcePath) {
        int slash = resourcePath.lastIndexOf('/');
        String fileName = slash < 0 ? resourcePath : resourcePath.substring(slash + 1);
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
