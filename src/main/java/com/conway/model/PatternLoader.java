package com.conway.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Loads {@link Pattern}s from plaintext {@code .cells} resources on the classpath. */
public final class PatternLoader {

    /** Classpath location of the Gosper glider gun shipped with the application. */
    public static final String GOSPER_GLIDER_GUN = "/patterns/gosper-glider-gun.cells";

    private PatternLoader() {
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
