package com.conway.bench;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.conway.engine.rules.LifeRules;
import com.conway.engine.rules.PartitionPlanner;
import com.conway.model.Generation;
import com.conway.model.Pattern;
import com.conway.model.PatternLoader;

/**
 * Headless throughput comparison across partition counts.
 *
 * <p>The application's premise is that splitting a generation across threads is
 * worthwhile. That claim needs evidence, and previously there was none: the only
 * metric was computed from millisecond wall-clock timing with integer division, so it
 * reported zero for any run shorter than a second.
 *
 * <p>Run it with {@code java --enable-preview -cp target/classes
 * com.conway.bench.GenerationBenchmark} or from the shaded jar; the flag is required because
 * {@link LifeRules} computes each generation inside a {@code StructuredTaskScope}. Optional
 * arguments: grid size, generations per measurement, and a band-thread mode.
 *
 * <ul>
 *   <li>{@code virtual} (default) &mdash; named virtual threads, which is what the
 *       application ships (see {@link com.conway.engine.SimulationEngine}).</li>
 *   <li>{@code platform} &mdash; platform threads, kept as a comparator. Note what this means
 *       under structured concurrency: a scope owns the threads it creates, so this starts a
 *       fresh OS thread per band per generation with no pool behind it. It is not comparable
 *       to the pooled {@code platform} figures recorded before 2026-10-06, and it is not a
 *       proposal.</li>
 * </ul>
 *
 * <p>The mode selects the {@link ThreadFactory} the per-generation scope forks from, so a
 * change of band threading is decided by measurement rather than by argument. Compare modes
 * on the same board and partition count. {@code forkjoin} was removed when the band executor
 * became a scope: a scope cannot fork into a {@code ForkJoinPool}. Its disposition is
 * recorded in {@code docs/concurrency-features-review.md}.</p>
 *
 * <p><strong>This harness does not reproduce the application's topology.</strong> It calls
 * {@code next()} from {@code main}, a platform thread; the application calls it from a
 * virtual thread, and that difference has reversed a ranking before &mdash; a
 * {@code ForkJoinPool} was adopted on this harness's evidence in 2026-09 and reverted once it
 * was measured in the application, where it cost 23%. Treat a band-threading decision taken
 * from these numbers alone as unproven. See the README's "Choosing the executor".</p>
 *
 * <p>This is a deliberately simple harness, not a rigorous microbenchmark. It warms
 * up, resets to an identical board before every measurement and takes the best of
 * several repetitions, which is enough to show the shape of the curve. Use JMH if
 * these numbers ever need to support a decision more precise than "does adding
 * threads help at all".
 */
public final class GenerationBenchmark {

    private static final int DEFAULT_GRID_SIZE = 201;
    private static final int DEFAULT_GENERATIONS = 300;
    private static final int WARMUP_GENERATIONS = 400;
    private static final int REPETITIONS = 5;

    private GenerationBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        int gridSize = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_GRID_SIZE;
        int generations = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_GENERATIONS;
        String mode = args.length > 2 ? args[2] : "virtual";
        int cores = Runtime.getRuntime().availableProcessors();
        // The same cap the application gives conway.maxPartitions by default.
        int maxPartitions = PartitionPlanner.hardwareCap(gridSize);

        System.out.printf(Locale.ROOT,
                "Board %dx%d, %d generations per measurement, best of %d, %d available processors, %s band threads%n%n",
                gridSize, gridSize, generations, REPETITIONS, cores, mode);

        Generation seed = seededBoard(gridSize);
        // No pool to close: the per-generation scope owns the threads it creates from this
        // factory, and closes over them before next() returns.
        LifeRules rules = new LifeRules(bandThreads(mode));
        warmUp(rules, seed, maxPartitions);

        Map<Integer, Double> results = new LinkedHashMap<>();
        for (int partitions = 1; partitions <= maxPartitions; partitions *= 2) {
            results.put(partitions, measure(rules, seed, partitions, generations));
        }
        if (!results.containsKey(maxPartitions)) {
            results.put(maxPartitions, measure(rules, seed, maxPartitions, generations));
        }

        report(results);

    }

    /**
     * The factory each generation's task scope forks its bands from.
     *
     * <p>Neither mode pools: a scope creates a thread per subtask and closes over it. That is
     * free for virtual threads and is the point of them here; for platform threads it is an OS
     * thread per band per generation, which is why that mode is a comparator and not an option.
     *
     * @param mode {@code virtual} or {@code platform}
     * @throws IllegalArgumentException on an unknown mode, so a typo is not silently
     *         measured as the default
     */
    private static ThreadFactory bandThreads(String mode) {
        return switch (mode) {
            case "virtual" -> Thread.ofVirtual().name("band-", 0L).factory();
            case "platform" -> Thread.ofPlatform().name("band-", 0L).factory();
            default -> throw new IllegalArgumentException(
                    "Unknown band-thread mode '" + mode + "'; expected virtual or platform");
        };
    }

    /** Two Gosper glider guns, matching the board the application starts with. */
    private static Generation seededBoard(int gridSize) {
        Pattern gun = PatternLoader.fromResource(PatternLoader.GOSPER_GLIDER_GUN);
        return Generation.empty(gridSize, gridSize)
                .withPattern(gun, 1, 1)
                .withPattern(gun.rotate180(), gridSize - gun.height() - 1, gridSize - gun.width() - 1);
    }

    private static void warmUp(LifeRules rules, Generation seed, int maxPartitions) throws Exception {
        for (int partitions : new int[] {1, maxPartitions}) {
            Generation board = seed;
            for (int i = 0; i < WARMUP_GENERATIONS; i++) {
                board = rules.next(board, partitions);
            }
        }
    }

    private static double measure(LifeRules rules, Generation seed, int partitions, int generations)
            throws Exception {
        double best = 0.0d;
        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            Generation board = seed;
            long start = System.nanoTime();
            for (int i = 0; i < generations; i++) {
                board = rules.next(board, partitions);
            }
            long elapsed = System.nanoTime() - start;
            // Consume the result so the loop cannot be optimized away.
            if (board.population() < 0) {
                throw new IllegalStateException("unreachable");
            }
            best = Math.max(best, generations * (double) TimeUnit.SECONDS.toNanos(1L) / elapsed);
        }
        return best;
    }

    private static void report(Map<Integer, Double> results) {
        double baseline = results.get(1);
        System.out.printf(Locale.ROOT, "%-12s %14s %10s%n", "Partitions", "Generations/s", "Speedup");
        System.out.println("-".repeat(38));
        for (Map.Entry<Integer, Double> entry : results.entrySet()) {
            System.out.printf(Locale.ROOT, "%-12d %14.1f %9.2fx%n",
                    entry.getKey(), entry.getValue(), entry.getValue() / baseline);
        }
    }
}
