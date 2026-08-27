package com.conway.testsupport

import com.conway.model.Generation
import com.conway.model.Pattern
import java.util.Random

/** Helpers for expressing boards as readable literals in tests. */

/**
 * Builds a board from plaintext rows, where `O` is alive and `.` is dead.
 * All rows must be the same length.
 */
fun board(vararg rows: String): Generation {
    val pattern = Pattern.parse("test-board", rows.toList())
    return Generation.empty(pattern.width, pattern.height).withPattern(pattern, 0, 0)
}

/** Renders the alive cells of a board back to the same plaintext form. */
fun render(generation: Generation): List<String> =
    List(generation.height) { row ->
        buildString(generation.width) {
            for (col in 0 until generation.width) {
                append(if (generation.isAlive(row, col)) 'O' else '.')
            }
        }
    }

/** Builds a reproducible random board with the given live-cell probability. */
fun random(width: Int, height: Int, random: Random, density: Double): Generation {
    var result = Generation.empty(width, height)
    for (row in 0 until height) {
        for (col in 0 until width) {
            if (random.nextDouble() < density) {
                result = result.withCell(row, col, true)
            }
        }
    }
    return result
}
