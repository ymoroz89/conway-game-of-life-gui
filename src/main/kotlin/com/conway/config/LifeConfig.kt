package com.conway.config

import com.conway.engine.PartitionPlanner
import org.slf4j.LoggerFactory

/**
 * Externalised application settings.
 *
 * Every value that used to be a compile-time constant scattered across the UI, the
 * controller and the board lives here and can be overridden with a system property, for
 * example `-Dconway.gridSize=401`.
 */
class LifeConfig private constructor(
    val gridSize: Int,
    private val targetBoardSizePx: Int,
    val maxPartitions: Int,
    val defaultPartitions: Int,
) {
    /** Edge length of a single cell in pixels, never less than one. */
    val cellSizePx: Int = maxOf(1, targetBoardSizePx / gridSize)

    /**
     * Cell size for a board of [gridSize] cells, so the board can be resized at runtime
     * without rebuilding the configuration.
     */
    fun cellSizePxFor(gridSize: Int): Int {
        require(gridSize > 0) { "gridSize must be positive but was $gridSize" }
        return maxOf(1, targetBoardSizePx / gridSize)
    }

    /** Board edge length in pixels. */
    val boardSizePx: Int get() = cellSizePx * gridSize

    companion object {
        private val LOG = LoggerFactory.getLogger(LifeConfig::class.java)

        private const val PREFIX = "conway."

        private const val DEFAULT_GRID_SIZE = 201
        private const val DEFAULT_BOARD_SIZE_PX = 1000

        /** Reads configuration from system properties, falling back to defaults. */
        fun fromSystemProperties(): LifeConfig {
            val gridSize = intProperty("gridSize", DEFAULT_GRID_SIZE, 3, 10_000)
            val boardSizePx = intProperty("boardSizePx", DEFAULT_BOARD_SIZE_PX, 50, 20_000)

            // More partitions than rows would produce empty work units, and more than a small
            // multiple of the core count adds scheduling overhead without adding parallelism.
            val maxPartitions = intProperty(
                "maxPartitions", PartitionPlanner.hardwareCap(gridSize), 1, gridSize,
            )

            val minRowsPerPartition = intProperty(
                "minRowsPerPartition", PartitionPlanner.DEFAULT_MIN_ROWS_PER_PARTITION, 1, gridSize,
            )
            val recommended = PartitionPlanner.recommend(gridSize, maxPartitions, minRowsPerPartition)
            val defaultPartitions = intProperty("partitions", recommended, 1, maxPartitions)

            val config = LifeConfig(
                gridSize, boardSizePx, maxPartitions, defaultPartitions,
            )
            LOG.info(
                "Configuration: grid {}x{}, cell {}px, partitions {} of at " +
                    "most {} ({} cores available)",
                gridSize, gridSize, config.cellSizePx,
                defaultPartitions, maxPartitions, Runtime.getRuntime().availableProcessors(),
            )
            return config
        }

        private fun intProperty(name: String, defaultValue: Int, min: Int, max: Int): Int {
            val raw = System.getProperty(PREFIX + name)
            if (raw == null || raw.isBlank()) return defaultValue
            return try {
                val value = raw.trim().toInt()
                if (value < min || value > max) {
                    LOG.warn("Ignoring {}{}={}: outside [{}, {}], using {}",
                        PREFIX, name, value, min, max, defaultValue)
                    defaultValue
                } else {
                    value
                }
            } catch (e: NumberFormatException) {
                LOG.warn("Ignoring {}{}='{}': not an integer, using {}", PREFIX, name, raw, defaultValue)
                defaultValue
            }
        }
    }
}