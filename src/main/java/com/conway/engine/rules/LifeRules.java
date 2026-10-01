package com.conway.engine.rules;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.Future; // for Javadoc {@link Future#get()} reference resolution

import com.conway.engine.rules.BandKernel.BandResult;
import com.conway.model.ActiveRegion;
import com.conway.model.Generation;

/**
 * Computes successive generations of Conway's Game of Life.
 *
 * <p>This class owns the scheduling: it splits a generation into contiguous bands of rows,
 * runs them on the executor it borrows, and assembles the result. What a band computes is
 * {@link BandKernel}'s job, and how many bands to use is {@link PartitionPlanner}'s.
 *
 * <p>Each band writes only its own disjoint slice of the destination arrays, so no
 * synchronization is needed between workers; {@link Future#get()} then supplies the
 * happens-before edge that makes every write visible to the thread that assembles the result.
 *
 * <p>The calling thread computes one band itself rather than waiting for the pool to compute
 * all of them, so the degree of parallelism is unchanged but one hand-off per generation is
 * avoided.
 *
 * <p>This class is stateless apart from the executor it borrows, and is safe to call from any
 * thread. In practice, it is only ever called from the simulation thread.
 */
public final class LifeRules {
    // Optional legacy executor for compatibility with existing tests/usages.
    // If null, dispatch uses StructuredTaskScope; if non-null, uses the provided ExecutorService.
    private final ExecutorService executor; // nullable

    /** Default constructor: uses Structured Concurrency per generation. */
    public LifeRules() {
        this.executor = null;
    }

    /**
     * Compatibility constructor: when supplied, bands are dispatched via this {@link ExecutorService}.
     *
     * <p>This retains test behavior that injects custom executors (e.g., failing executors) while
     * allowing the default path to use Structured Concurrency when this constructor is not used.
     */
    public LifeRules(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Computes the generation following {@code current}.
     *
     * <p>The result is identical for every value of {@code partitions}; the parameter only
     * controls how the work is distributed.
     *
     * <p>An empty board needs no special case: its single band skips rule evaluation but still
     * carries the ever-alive flags forward, so trails survive the population reaching zero.
     *
     * @param partitions number of row bands to compute concurrently, clamped to
     *                   {@code [1, height]} and capped by the active region
     *                   ({@link PartitionPlanner#bandsFor}). A single band is computed inline
     *                   on the calling thread, giving an honest single-threaded baseline.
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws SimulationException  if any band fails
     */
    public Generation next(Generation current, int partitions) throws InterruptedException {
        Objects.requireNonNull(current, "current");

        int width = current.width();
        int height = current.height();
        boolean[] alive = new boolean[current.aliveCells().length];
        boolean[] everAlive = new boolean[alive.length];
        ActiveRegion active = current.activeRegion().pad(width, height);
        BandKernel kernel = new BandKernel(current, alive, everAlive, active);

        int bands = PartitionPlanner.bandsFor(partitions, height, active);
        BandResult[] results;
        try {
            results = computeBands(kernel, boundaries(height, bands));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new SimulationException("Generation computation failed", cause);
        }

        ActiveRegion region = ActiveRegion.EMPTY;
        int population = 0;
        for (BandResult result : results) {
            region = region.merge(result.region());
            population += result.population();
        }
        // Ownership of both arrays passes to the new generation; nothing here touches them again.
        return Generation.adopt(width, height, alive, everAlive, current.number() + 1, region, population);
    }

    /**
     * Computes every band, returning the results in band order.
     *
     * <p>Every band but the first is dispatched, and the first is computed here rather than
     * blocking for all of them. The calling thread is the simulation thread, which otherwise
     * parks for the whole generation -- a wall-clock profile measured it blocked 88% of the
     * time while the pool did the work. With a single band, nothing is dispatched at all.
     *
     * <p>However a generation fails, it escapes as a {@link SimulationException} whose cause is
     * the band's own exception, so a failure reports the same way whichever band hit it. The
     * bands still pending are canceled rather than left to run to completion unseen.
     */
    private BandResult[] computeBands(BandKernel kernel, int[] bounds) throws InterruptedException, ExecutionException {
        int bands = bounds.length - 1;
        BandResult[] results = new BandResult[bands];

        // Legacy path: dispatch via supplied ExecutorService if present.
        if (executor != null) {
            @SuppressWarnings("unchecked")
            Future<BandResult>[] futures = new Future[bands];
            try {
                // Fork bands 1..N-1 via the executor.
                for (int i = 1; i < bands; i++) {
                    final int from = bounds[i];
                    final int to = bounds[i + 1];
                    futures[i] = executor.submit(() -> kernel.computeBand(from, to));
                }

                // Compute band 0 inline on the calling (simulation) thread.
                results[0] = kernel.computeBand(bounds[0], bounds[1]);

                // Collect dispatched results in order; propagate first failure.
                for (int i = 1; i < bands; i++) {
                    results[i] = futures[i].get(); // may throw ExecutionException/InterruptedException
                }
            } catch (ExecutionException e) {
                // Cancel any still-running tasks; then rethrow to be wrapped uniformly by caller.
                for (int i = 1; i < bands; i++) {
                    if (futures[i] != null) futures[i].cancel(true);
                }
                throw e;
            } catch (RuntimeException e) {
                // Surface uniformly as SimulationException like before.
                throw new SimulationException("Generation computation failed", e);
            }
            return results;
        }

        // Default path: Structured Concurrency with shutdown-on-failure semantics.
        try (var scope = StructuredTaskScope.open()) {
            @SuppressWarnings("unchecked")
            Subtask<BandResult>[] tasks = new Subtask[bands];

            // Fork bands 1..N-1 into the scope.
            for (int i = 1; i < bands; i++) {
                final int from = bounds[i];
                final int to = bounds[i + 1];
                tasks[i] = scope.fork(() -> kernel.computeBand(from, to));
            }

            // Compute band 0 inline on the calling (simulation) thread.
            results[0] = kernel.computeBand(bounds[0], bounds[1]);

            // Wait for all children; with the default policy this blocks until all subtasks complete
            // and cancellation is propagated on first failure. In JDK 27 preview, join() throws
            // ExecutionException on subtask failure; propagate to caller for uniform handling.
            scope.join();

            // Collect results in band order. Subtask#get no longer throws checked exceptions here.
            for (int i = 1; i < bands; i++) {
                results[i] = tasks[i].get();
            }
        } catch (RuntimeException e) {
            // Surface uniformly as SimulationException like before.
            throw new SimulationException("Generation computation failed", e);
        }

        return results;
    }

    /**
     * Splits {@code height} rows into {@code bands} contiguous bands as evenly as possible.
     * Band {@code i} covers rows {@code [bounds[i], bounds[i + 1])}, and the first
     * {@code height % bands} bands take one extra row.
     */
    private static int[] boundaries(int height, int bands) {
        int[] bounds = new int[bands + 1];
        int baseSize = height / bands;
        int remainder = height % bands;
        for (int i = 0; i < bands; i++) {
            bounds[i + 1] = bounds[i] + baseSize + (i < remainder ? 1 : 0);
        }
        return bounds;
    }

}
