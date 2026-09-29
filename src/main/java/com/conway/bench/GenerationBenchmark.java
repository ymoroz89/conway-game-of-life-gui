package com.conway.bench;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
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
 * <p>Run it with {@code java -cp target/classes com.conway.bench.GenerationBenchmark}
 * or from the shaded jar. Optional arguments: grid size, generations per measurement,
 * and an executor mode.
 *
 * <ul>
 *   <li>{@code platform} (default) &mdash; a fixed pool of platform threads.</li>
 *   <li>{@code virtual} &mdash; virtual-thread-per-task. This harness measured it at parity
 *       with the fixed pool; the application has since moved to a {@code ForkJoinPool} band
 *       pool (see {@link com.conway.engine.SimulationEngine}), which measured 12-19% faster
 *       at 201/4 partitions with parity at 801. The mode remains for comparison.</li>
 *   <li>{@code forkjoin} &mdash; a dedicated {@link java.util.concurrent.ForkJoinPool}
 *       with parallelism {@code min(maxPartitions, cores)}, the configuration the
 *       application ships. Bands are uneven, because
 *       {@code computeBand} clips rule evaluation to the active region, and the cores are
 *       asymmetric; work stealing is the standard answer to both, so it is worth
 *       measuring against the other two.</li>
 * </ul>
 *
 * <p>The modes exist so that a change of executor is decided by measurement rather than
 * by argument. Compare them on the same board and partition count.</p>
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
        String mode = args.length > 2 ? args[2] : "platform";
        int cores = Runtime.getRuntime().availableProcessors();
        // The same cap the application gives conway.maxPartitions by default.
        int maxPartitions = PartitionPlanner.hardwareCap(gridSize);

        System.out.printf(Locale.ROOT,
                "Board %dx%d, %d generations per measurement, best of %d, %d available processors, %s executor%n%n",
                gridSize, gridSize, generations, REPETITIONS, cores, mode);

        Generation seed = seededBoard(gridSize);
        // ExecutorService has been AutoCloseable since Java 19: close() shuts the pool
        // down and waits for it to terminate, replacing the manual shutdown block.
        try (ExecutorService executor = newExecutor(mode, maxPartitions, cores)) {
            LifeRules rules = new LifeRules(executor);
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
    }

    /**
     * @param mode one of {@code platform}, {@code virtual}, {@code forkjoin}
     * @throws IllegalArgumentException on an unknown mode, so a typo is not silently
     *         measured as the default
     */
    private static ExecutorService newExecutor(String mode, int maxPartitions, int cores) {
        return switch (mode) {
            case "platform" -> Executors.newFixedThreadPool(maxPartitions);
            case "virtual" -> Executors.newVirtualThreadPerTaskExecutor();
            // Parallelism is the core count, not the band count: stealing redistributes
            // work, so there is no need for a thread per band the way a fixed pool needs.
            case "forkjoin" -> new ForkJoinPool(Math.min(maxPartitions, cores));
            default -> throw new IllegalArgumentException(
                    "Unknown executor mode '" + mode + "'; expected platform, virtual or forkjoin");
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
