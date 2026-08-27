package com.conway.bench

import com.conway.engine.LifeRules
import com.conway.model.Generation
import com.conway.model.PatternLoader
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Headless throughput comparison across partition counts.
 *
 * The application's premise is that splitting a generation across threads is
 * worthwhile. That claim needs evidence, and previously there was none: the only
 * metric was computed from millisecond wall-clock timing with integer division, so it
 * reported zero for any run shorter than a second.
 *
 * Run it with:
 *
 * ```bash
 * ./gradlew bench
 * ./gradlew bench --args="401 300 virtual"
 * ```
 *
 * Optional arguments: grid size, generations per measurement, and an executor mode of
 * `platform` (default) or `virtual`. The virtual mode uses a virtual-thread-per-task
 * executor, which is how the band workers would run if the pool were replaced; the two
 * modes are compared to decide whether that replacement is worth making.
 *
 * This is a deliberately simple harness, not a rigorous microbenchmark. It warms
 * up, resets to an identical board before every measurement and takes the best of
 * several repetitions, which is enough to show the shape of the curve. Use JMH if
 * these numbers ever need to support a decision more precise than "does adding
 * threads help at all".
 */
private const val DEFAULT_GRID_SIZE = 201
private const val DEFAULT_GENERATIONS = 300
private const val WARMUP_GENERATIONS = 400
private const val REPETITIONS = 5

fun main(args: Array<String>) {
    val gridSize = args.getOrNull(0)?.toInt() ?: DEFAULT_GRID_SIZE
    val generations = args.getOrNull(1)?.toInt() ?: DEFAULT_GENERATIONS
    val virtual = args.getOrNull(2) == "virtual"
    val cores = Runtime.getRuntime().availableProcessors()
    val maxPartitions = minOf(gridSize, cores * 2)

    System.out.printf(
        Locale.ROOT,
        "Board %dx%d, %d generations per measurement, best of %d, %d available processors, %s executor%n%n",
        gridSize, gridSize, generations, REPETITIONS, cores, if (virtual) "virtual" else "platform",
    )

    val seed = seededBoard(gridSize)
    // ExecutorService has been AutoCloseable since Java 19: close() shuts the pool
    // down and waits for it to terminate, replacing the manual shutdown block.
    val executor: ExecutorService = if (virtual) {
        Executors.newVirtualThreadPerTaskExecutor()
    } else {
        Executors.newFixedThreadPool(maxPartitions)
    }
    executor.use { pool ->
        val rules = LifeRules(pool)
        warmUp(rules, seed, maxPartitions)

        val results = LinkedHashMap<Int, Double>()
        var partitions = 1
        while (partitions <= maxPartitions) {
            results[partitions] = measure(rules, seed, partitions, generations)
            partitions *= 2
        }
        if (!results.containsKey(maxPartitions)) {
            results[maxPartitions] = measure(rules, seed, maxPartitions, generations)
        }

        report(results)
    }
}

/** Two Gosper glider guns, matching the board the application starts with. */
private fun seededBoard(gridSize: Int): Generation {
    val gun = PatternLoader.fromResource(PatternLoader.GOSPER_GLIDER_GUN)
    return Generation.empty(gridSize, gridSize)
        .withPattern(gun, 1, 1)
        .withPattern(gun.rotate180(), gridSize - gun.height - 1, gridSize - gun.width - 1)
}

private fun warmUp(rules: LifeRules, seed: Generation, maxPartitions: Int) {
    for (partitions in intArrayOf(1, maxPartitions)) {
        var board = seed
        repeat(WARMUP_GENERATIONS) {
            board = rules.next(board, partitions)
        }
    }
}

private fun measure(
    rules: LifeRules,
    seed: Generation,
    partitions: Int,
    generations: Int,
): Double {
    var best = 0.0
    repeat(REPETITIONS) {
        var board = seed
        val start = System.nanoTime()
        repeat(generations) {
            board = rules.next(board, partitions)
        }
        val elapsed = System.nanoTime() - start
        // Consume the result so the loop cannot be optimised away.
        check(board.population >= 0) { "unreachable" }
        best = maxOf(best, generations * TimeUnit.SECONDS.toNanos(1L).toDouble() / elapsed)
    }
    return best
}

private fun report(results: Map<Int, Double>) {
    val baseline = results.getValue(1)
    System.out.printf(Locale.ROOT, "%-12s %14s %10s%n", "Partitions", "Generations/s", "Speedup")
    println("-".repeat(38))
    for ((partitions, rate) in results) {
        System.out.printf(Locale.ROOT, "%-12d %14.1f %9.2fx%n", partitions, rate, rate / baseline)
    }
}
