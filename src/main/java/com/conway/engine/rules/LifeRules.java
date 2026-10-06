package com.conway.engine.rules;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.ThreadFactory;

import com.conway.engine.rules.BandKernel.BandResult;
import com.conway.model.ActiveRegion;
import com.conway.model.Generation;

/**
 * Computes successive generations of Conway's Game of Life.
 *
 * <p>This class owns the scheduling: it splits a generation into contiguous bands of rows,
 * runs them, and assembles the result. What a band computes is {@link BandKernel}'s job, and
 * how many bands to use is {@link PartitionPlanner}'s.
 *
 * <p>Each band writes only its own disjoint slice of the destination arrays, so no
 * synchronization is needed between workers; joining the scope then supplies the
 * happens-before edge that makes every write visible to the thread that assembles the result.
 *
 * <p>The calling thread computes one band itself rather than waiting for all of them to be
 * computed elsewhere, so the degree of parallelism is unchanged but one hand-off per
 * generation is avoided.
 *
 * <p>This class is immutable and safe to call from any thread. In practice, it is only ever
 * called from the simulation thread.
 *
 * <h2>Structured concurrency</h2>
 * Every multi-band generation runs inside one {@link StructuredTaskScope} (JEP 533, preview
 * in JDK 27). The scope is the unit of lifetime: bands are forked into it, band zero is
 * computed on the calling thread, and the scope is closed before this class returns. No band
 * can outlive the generation that forked it, and that is structural rather than a convention.
 *
 * <p>Three things the scope does that the previous {@code ExecutorService} needed code for:
 * <ul>
 *   <li>a band that throws cancels its siblings, so they are interrupted instead of running
 *       to completion unseen &mdash; this replaces a hand-rolled {@code cancel(true)} loop;</li>
 *   <li>the {@link Joiner} maps the failure to {@link SimulationException} with the band's own
 *       exception as its cause, so there is no {@code ExecutionException} to unwrap anywhere;</li>
 *   <li>there is no pool, so nothing to size, drain or shut down.</li>
 * </ul>
 *
 * <p>The price is a preview API: the build requires exactly the JDK it is compiled against and
 * the application must be launched with {@code --enable-preview}. See
 * {@code docs/structured-concurrency.md}.
 */
public final class LifeRules {

    private static final String FAILURE_MESSAGE = "Generation computation failed";

    /**
     * Names the scope so it is identifiable in a thread dump or JFR recording.
     */
    private static final String SCOPE_NAME = "conway-generation";

    private final ThreadFactory bandThreads;
    private final BandWork bandWork;

    /**
     * Bands run on named virtual threads, which is what the application ships.
     */
    public LifeRules() {
        this(Thread.ofVirtual().name("conway-band-", 0L).factory());
    }

    /**
     * @param bandThreads creates the thread for each forked band. The scope owns every thread
     *                    it creates and closes over them, so this must not be a pooling
     *                    factory; {@code GenerationBenchmark} uses it to compare virtual
     *                    bands against platform ones.
     */
    public LifeRules(ThreadFactory bandThreads) {
        this(bandThreads, BandKernel::computeBand);
    }

    /**
     * Test seam: substitutes what a band computes, so a band failure can be injected into the
     * real dispatch path.
     *
     * <p>The previous seam was a custom {@code ExecutorService} that discarded the submitted
     * task. A scope creates its threads from a plain {@link ThreadFactory} and there is
     * nothing to intercept between {@code fork} and the task, so the seam moved here. It also
     * reaches band zero, which the executor trick never could.
     */
    LifeRules(ThreadFactory bandThreads, BandWork bandWork) {
        this.bandThreads = Objects.requireNonNull(bandThreads, "bandThreads");
        this.bandWork = Objects.requireNonNull(bandWork, "bandWork");
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
     * <p>Every band but the first is forked, and the first is computed here rather than
     * blocking for all of them. The calling thread is the simulation thread, which otherwise
     * parks for the whole generation -- a wall-clock profile measured it blocked 88% of the
     * time while the workers did the work. With a single band, nothing is forked at all and no
     * scope is opened.
     *
     * <p>However a generation fails, it escapes as a {@link SimulationException} whose cause is
     * the band's own exception, so a failure reports the same way whichever band hit it.
     */
    private BandResult[] computeBands(BandKernel kernel, int[] bounds) throws InterruptedException {
        int bands = bounds.length - 1;
        BandResult[] results = new BandResult[bands];

        if (bands == 1) {
            results[0] = bandWork.compute(kernel, bounds[0], bounds[1]);
            return results;
        }

        try (var scope = StructuredTaskScope.<BandResult, List<BandResult>, SimulationException>open(
                Joiner.allSuccessfulOrThrow(cause -> new SimulationException(FAILURE_MESSAGE, cause)),
                config -> config.withName(SCOPE_NAME).withThreadFactory(bandThreads))) {

            for (int i = 1; i < bands; i++) {
                int from = bounds[i];
                int to = bounds[i + 1];
                scope.fork(() -> bandWork.compute(kernel, from, to));
            }

            // Band zero belongs to the owner thread, so the scope can neither cancel it nor see
            // it fail. Record the failure instead of throwing here: the scope still has to be
            // joined, because forking without joining is a structure violation that close()
            // reports in place of the real failure.
            SimulationException inlineFailure = null;
            try {
                results[0] = bandWork.compute(kernel, bounds[0], bounds[1]);
            } catch (SimulationException e) {
                inlineFailure = e;
            } catch (RuntimeException e) {
                inlineFailure = new SimulationException(FAILURE_MESSAGE, e);
            }

            // Joining is also the happens-before edge for the forked bands; band zero was
            // written by this thread, so its writes were already visible. A band that threw has
            // already cancelled its siblings, and the Joiner hands back the failure as a
            // SimulationException carrying that band's own exception.
            //
            // When band zero is the one that failed, the forked bands are waited for rather
            // than cancelled. A band is microseconds of pure CPU and never blocks, so there is
            // nothing to cut short -- the old cancel(true) could not interrupt them either.
            List<BandResult> forked;
            try {
                forked = scope.join();
            } catch (SimulationException joinFailure) {
                if (inlineFailure == null) {
                    throw joinFailure;
                }
                // Band zero wins, matching the precedence the hand-rolled cleanup had, but the
                // forked failure rides along rather than vanishing.
                inlineFailure.addSuppressed(joinFailure);
                throw inlineFailure;
            }
            if (inlineFailure != null) {
                throw inlineFailure;
            }

            // allSuccessfulOrThrow returns results in fork order, which is band order from one up.
            for (int i = 1; i < bands; i++) {
                results[i] = forked.get(i - 1);
            }
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

    /**
     * What one band computes. Always {@link BandKernel#computeBand} in production; see the
     * package-private {@link LifeRules} constructor for why it is substitutable.
     */
    @FunctionalInterface
    interface BandWork {
        BandResult compute(BandKernel kernel, int fromRow, int toRow);
    }
}
