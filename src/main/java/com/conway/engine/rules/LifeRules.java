package com.conway.engine.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

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

    private final ExecutorService executor;

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
        BandResult[] results = computeBands(kernel, boundaries(height, bands));

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
    private BandResult[] computeBands(BandKernel kernel, int[] bounds) throws InterruptedException {
        int bands = bounds.length - 1;
        List<Future<BandResult>> futures = new ArrayList<>(bands - 1);
        for (int i = 1; i < bands; i++) {
            int from = bounds[i];
            int to = bounds[i + 1];
            futures.add(executor.submit(() -> kernel.computeBand(from, to)));
        }

        BandResult[] results = new BandResult[bands];
        try {
            results[0] = kernel.computeBand(bounds[0], bounds[1]);
        } catch (RuntimeException e) {
            cancelAll(futures);
            throw new SimulationException("Generation computation failed", e);
        }

        joinDispatchedBands(bands, results, futures);
        return results;
    }

    /**
     * Joins the dispatched bands in band order, filling {@code results[1]} through
     * {@code results[bands - 1]}.
     *
     * <p>{@code results[0]} was computed inline by the calling thread in {@link
     * #computeBands}, so only bands {@code 1} through {@code bands - 1} are joined here.
     * {@code futures.get(i - 1)} holds band {@code i}: band zero was never submitted, hence
     * the off-by-one. With a single band there is nothing to join and the loop is a no-op.
     *
     * <p>{@link Future#get()} supplies the happens-before edge that makes each worker's
     * writes to the destination arrays visible to the assembling thread; band zero needs no
     * such edge because this thread wrote it itself. The Java 19 accessors ({@code state()},
     * {@code resultNow()}, {@code exceptionNow()}) cannot replace this call: none of them
     * waits, and there is nothing to read until the band has actually been joined.
     *
     * <p>A band failure escapes as a {@link SimulationException} whose cause is the band's
     * own exception, matching the inline band in {@link #computeBands}, so a failure reports
     * the same way whichever band hit it. The bands not yet joined are canceled via {@link
     * #cancelAll} rather than left to run to completion unseen.
     *
     * @param bands   total band count; equals {@code results.length} and one more than
     *                {@code futures.size()}
     * @param results band results in band order; entry zero is already filled, entries
     *                {@code 1} through {@code bands - 1} are written by this method
     * @param futures dispatched bands {@code 1} through {@code bands - 1} in order, one
     *                future per band
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws SimulationException  if any band fails
     */
    private static void joinDispatchedBands(int bands, BandResult[] results,
                                            List<Future<BandResult>> futures) throws InterruptedException {
        for (int i = 1; i < bands; i++) {
            try {
                results[i] = futures.get(i - 1).get();
            } catch (ExecutionException e) {
                // Whatever has not been joined yet is pure loss in a failed generation.
                cancelAll(futures.subList(i, futures.size()));
                throw new SimulationException("Generation computation failed", e.getCause());
            }
        }
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

    /**
     * Marks pending band futures as canceled because a sibling band failed.
     *
     * <p>Bands that already completed are no-ops: {@link Future#cancel(boolean)} on a done
     * task does nothing. The interrupt flag is best-effort &mdash; {@code computeBand} never
     * blocks, so it cannot be cut short &mdash; but the generation is already lost, and the
     * alternative is letting the surviving bands run to completion unseen.
     */
    private static void cancelAll(List<Future<BandResult>> futures) {
        for (Future<BandResult> future : futures) {
            future.cancel(true);
        }
    }
}
