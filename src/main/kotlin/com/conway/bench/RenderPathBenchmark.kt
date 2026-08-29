package com.conway.bench

import com.conway.engine.EngineSnapshot
import com.conway.engine.LifeRules
import com.conway.engine.SimulationStats
import com.conway.model.Generation
import com.conway.model.PatternLoader
import com.conway.ui.BoardRenderer
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Headless measurement of the two per-frame costs on the render path.
 *
 * [GenerationBenchmark] covers the simulation; nothing covered the UI side, so two
 * decisions in `App`/`BoardRenderer` rested on reasoning about what Compose and
 * `Generation.equals` do rather than on a measurement. Both turned out to be worth
 * checking: one of them was not costing what it appeared to.
 *
 * What is measured:
 *
 *  - **Snapshot equality.** The render loop assigns the newest [EngineSnapshot] into
 *    Compose state every frame, and the state's equality policy decides whether that
 *    counts as a change. The three cases are the ones the loop can actually produce --
 *    the same instance while paused, consecutive generations while running -- plus the
 *    worst case behind them, two distinct boards agreeing on every field the comparison
 *    checks before it reaches the cell arrays.
 *  - **Re-rendering a board already on screen.** A draw is not always caused by a new
 *    generation: a resize or a re-layout repaints a board that has not moved. The
 *    difference between scanning the whole board to find nothing and returning
 *    immediately is what [BoardRenderer]'s identity check is worth.
 *
 * Like [GenerationBenchmark] this is a deliberately simple harness, not a rigorous
 * microbenchmark: it warms up, takes the best of several repetitions, and feeds every
 * result into a sink so the JIT cannot delete the work. Use JMH if a number here ever has
 * to support a finer decision than "is this cost real or not".
 *
 * ```bash
 * ./gradlew renderBench
 * ./gradlew renderBench --args="201 801"
 * ```
 */
private const val REPETITIONS = 7
private const val WARMUP_GENERATIONS = 50
private val DEFAULT_SIZES = intArrayOf(201, 801)

/** Successive generations cycled through by the equality rows. Must be a power of two. */
private const val RING_SIZE = 8
private const val RING_MASK = RING_SIZE - 1

/** Accumulates every measured result so nothing being timed is dead code. */
private var sink = 0L

fun main(args: Array<String>) {
    val sizes = if (args.isEmpty()) DEFAULT_SIZES else args.map { it.toInt() }.toIntArray()

    Executors.newVirtualThreadPerTaskExecutor().use { pool ->
        val rules = LifeRules(pool)
        for (size in sizes) {
            var board = seededBoard(size)
            repeat(WARMUP_GENERATIONS) { board = rules.next(board, 1) }

            val current = board
            val next = rules.next(current, 1)
            // Same contents and same generation number as `current`, but a distinct
            // instance: the only shape that gets past every cheap field in
            // Generation.equals down to the cell arrays.
            val twin = Generation.adopt(
                size, size,
                current.aliveCells().copyOf(), current.everAliveCells().copyOf(),
                current.number,
            )

            System.out.printf(
                Locale.ROOT, "%n=== %dx%d board, population %d%n",
                size, size, current.population,
            )
            reportEquality(rules, current, twin)
            reportRendering(size, current, next, twin)
        }
    }
    // Printed so the sink is genuinely observable and cannot be optimised away.
    System.out.printf(Locale.ROOT, "%n(sink %d)%n", sink)
}

/** Two Gosper glider guns, matching the board the application starts with. */
private fun seededBoard(gridSize: Int): Generation {
    val gun = PatternLoader.fromResource(PatternLoader.GOSPER_GLIDER_GUN)
    return Generation.empty(gridSize, gridSize)
        .withPattern(gun, 1, 1)
        .withPattern(gun.rotate180(), gridSize - gun.height - 1, gridSize - gun.width - 1)
}

private fun reportEquality(rules: LifeRules, current: Generation, twin: Generation) {
    fun snapshotOf(generation: Generation) =
        EngineSnapshot(generation, SimulationStats(generation.number, 0L, 0L, 1))

    // A ring of successive generations rather than one fixed pair. Comparing the same two
    // instances in a loop lets the JIT hoist the result out and report an honest-looking
    // zero; rotating through the ring forces the comparison to happen each time, and is
    // also what the render loop genuinely does.
    var board = current
    val ring = Array(RING_SIZE) { snapshotOf(board).also { board = rules.next(board, 1) } }
    val twinSnapshot = snapshotOf(twin)

    println("structural equality on the render-loop state:")
    row("same instance (engine paused)", 500_000) { i ->
        val snapshot = ring[i and RING_MASK]
        if (snapshot == snapshot) 1L else 0L
    }
    row("consecutive generations (running)", 500_000) { i ->
        if (ring[i and RING_MASK] == ring[(i + 1) and RING_MASK]) 1L else 0L
    }
    row("equal contents and equal number", 5_000) {
        if (ring[0] == twinSnapshot) 1L else 0L
    }
}

private fun reportRendering(size: Int, current: Generation, next: Generation, twin: Generation) {
    println("BoardRenderer.render:")

    // A distinct-but-identical instance defeats the identity check and forces the full
    // diff, which is what a redraw of an unchanged board used to cost.
    val diffing = BoardRenderer(size, size, 4, 1.0)
    diffing.render(current)
    row("unchanged board, full diff", 5_000) { i ->
        diffing.render(if (i and 1 == 0) twin else current)[0].toLong()
    }

    val identity = BoardRenderer(size, size, 4, 1.0)
    identity.render(current)
    row("unchanged board, identity check", 500_000) { identity.render(current)[0].toLong() }

    val changing = BoardRenderer(size, size, 4, 1.0)
    changing.render(current)
    row("new generation, for scale", 5_000) { i ->
        changing.render(if (i and 1 == 0) next else current)[0].toLong()
    }
}

/** Times [body] over [iterations], best of [REPETITIONS], and prints nanoseconds per call. */
private fun row(label: String, iterations: Int, body: (Int) -> Long) {
    var best = Double.MAX_VALUE
    repeat(REPETITIONS) {
        var local = 0L
        val start = System.nanoTime()
        for (i in 0 until iterations) {
            local += body(i)
        }
        val elapsed = System.nanoTime() - start
        sink += local
        best = minOf(best, elapsed.toDouble() / iterations)
    }
    System.out.printf(Locale.ROOT, "  %-36s %12.1f ns%n", label, best)
}
