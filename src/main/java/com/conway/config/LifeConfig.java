package com.conway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conway.engine.PartitionPlanner;

/**
 * Externalised application settings.
 *
 * <p>Every value that used to be a compile-time constant scattered across the UI,
 * the controller and the board lives here and can be overridden with a system
 * property, for example {@code -Dconway.gridSize=401}.
 */
public final class LifeConfig {

    private static final Logger LOG = LoggerFactory.getLogger(LifeConfig.class);

    private static final String PREFIX = "conway.";

    private static final int DEFAULT_GRID_SIZE = 201;
    private static final int DEFAULT_BOARD_SIZE_PX = 1000;
    private static final int DEFAULT_TARGET_FPS = 60;

    private final int gridSize;
    private final int targetBoardSizePx;
    private final int cellSizePx;
    private final int targetFps;
    private final int maxPartitions;
    private final int defaultPartitions;

    private LifeConfig(int gridSize, int boardSizePx, int targetFps,
                       int maxPartitions, int defaultPartitions) {
        this.gridSize = gridSize;
        this.targetBoardSizePx = boardSizePx;
        this.cellSizePx = Math.max(1, boardSizePx / gridSize);
        this.targetFps = targetFps;
        this.maxPartitions = maxPartitions;
        this.defaultPartitions = defaultPartitions;
    }

    /** Reads configuration from system properties, falling back to defaults. */
    public static LifeConfig fromSystemProperties() {
        int gridSize = intProperty("gridSize", DEFAULT_GRID_SIZE, 3, 10_000);
        int boardSizePx = intProperty("boardSizePx", DEFAULT_BOARD_SIZE_PX, 50, 20_000);
        int targetFps = intProperty("targetFps", DEFAULT_TARGET_FPS, 1, 240);

        // More partitions than rows would produce empty work units, and more than a
        // small multiple of the core count adds scheduling overhead without adding
        // parallelism. Oversubscribing by 2x still lets the user observe the effect
        // of raising the count past the hardware limit.
        int maxPartitions =
                intProperty("maxPartitions", PartitionPlanner.hardwareCap(gridSize), 1, gridSize);

        // Chosen from the board size and the machine rather than hard-coded to 1. The
        // rule and the measurements behind it are documented on PartitionPlanner.
        int minRowsPerPartition = intProperty("minRowsPerPartition",
                PartitionPlanner.DEFAULT_MIN_ROWS_PER_PARTITION, 1, gridSize);
        int recommended = PartitionPlanner.recommend(gridSize, maxPartitions, minRowsPerPartition);
        int defaultPartitions = intProperty("partitions", recommended, 1, maxPartitions);

        LifeConfig config = new LifeConfig(
                gridSize, boardSizePx, targetFps, maxPartitions, defaultPartitions);
        LOG.info("Configuration: grid {}x{}, cell {}px, target {} fps, "
                        + "partitions {} of at most {} ({} cores available)",
                gridSize, gridSize, config.cellSizePx, targetFps,
                defaultPartitions, maxPartitions, Runtime.getRuntime().availableProcessors());
        return config;
    }

    /** Board width and height in cells. */
    public int gridSize() {
        return gridSize;
    }

    /** Edge length of a single cell in pixels, never less than one. */
    public int cellSizePx() {
        return cellSizePx;
    }

    /**
     * Cell size for a board of {@code gridSize} cells, so the board can be resized at
     * runtime without rebuilding the configuration.
     *
     * @throws IllegalArgumentException if {@code gridSize} is not positive
     */
    public int cellSizePxFor(int gridSize) {
        if (gridSize <= 0) {
            throw new IllegalArgumentException("gridSize must be positive but was " + gridSize);
        }
        return Math.max(1, targetBoardSizePx / gridSize);
    }

    /** Board edge length in pixels. */
    public int boardSizePx() {
        return cellSizePx * gridSize;
    }

    /** Repaint rate. Rendering is deliberately decoupled from the simulation rate. */
    public int targetFps() {
        return targetFps;
    }

    /** Upper bound the UI allows for the number of parallel row bands. */
    public int maxPartitions() {
        return maxPartitions;
    }

    /**
     * Partition count the application starts with, sized for this board on this machine.
     *
     * @see com.conway.engine.PartitionPlanner
     */
    public int defaultPartitions() {
        return defaultPartitions;
    }

    private static int intProperty(String name, int defaultValue, int min, int max) {
        String raw = System.getProperty(PREFIX + name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                LOG.warn("Ignoring {}{}={}: outside [{}, {}], using {}",
                        PREFIX, name, value, min, max, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException e) {
            LOG.warn("Ignoring {}{}='{}': not an integer, using {}", PREFIX, name, raw, defaultValue);
            return defaultValue;
        }
    }
}
