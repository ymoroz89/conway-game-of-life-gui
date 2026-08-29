package com.conway.config

/**
 * A board size offered in the user interface.
 *
 * `cells` is the simulated grid edge in cells; `label` is what the control displays.
 * The two differ: the presets simulate 201, 401 and 801 cells but are shown as 200,
 * 400 and 800, because the round number is the useful thing to read and the extra cell
 * is an implementation detail of the grid.
 */
data class BoardSizeOption(val cells: Int, val label: String) {
    init {
        require(cells > 0) { "cells must be positive but was $cells" }
    }

    /**
     * The sizes the control offers, in ascending order.
     *
     * Always contains [configuredCells]. A grid set through `-Dconway.gridSize` that is
     * not one of the presets is added rather than silently replaced by one, so the
     * property keeps working; because there is no round number to show for it, it is
     * labelled with its actual size.
     */
    companion object {
        /** Grid edge paired with the number displayed for it. */
        private val PRESETS = arrayOf(intArrayOf(201, 200), intArrayOf(401, 400), intArrayOf(801, 800))

        fun options(configuredCells: Int): List<BoardSizeOption> {
            require(configuredCells > 0) { "configuredCells must be positive but was $configuredCells" }

            val options = ArrayList<BoardSizeOption>(PRESETS.size + 1)
            var configuredIsPreset = false
            for (preset in PRESETS) {
                options.add(BoardSizeOption(preset[0], preset[1].toString()))
                configuredIsPreset = configuredIsPreset || preset[0] == configuredCells
            }
            if (!configuredIsPreset) {
                options.add(BoardSizeOption(configuredCells, configuredCells.toString()))
                options.sortBy { it.cells }
            }
            // java.util.List.copyOf rather than Kotlin's toList(): toList() returns a plain
            // ArrayList, which callers could mutate, whereas copyOf is genuinely immutable.
            return java.util.List.copyOf(options)
        }
    }
}