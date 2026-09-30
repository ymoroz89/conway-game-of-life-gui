package drive;

import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;

import com.conway.config.LifeConfig;
import com.conway.engine.SimulationEngine;
import com.conway.engine.rules.LifeRules;
import com.conway.engine.support.SimulationSeeder;
import com.conway.model.Generation;

/**
 * Determinism gate for a cross-JDK comparison. Not a benchmark: it prints no timing and
 * produces no table.
 *
 * <p>The gate exists because matching generation <em>counts</em> never proved matching
 * <em>work</em> — an arm quietly computing something different looks exactly like an arm
 * being fast. It answers one question: do both JVMs evolve the same board to the same
 * state after an identical number of generations?
 *
 * <p>Why this does not reuse {@link GuiDrive}: a free-running engine stops on the first
 * published snapshot at or past the target, so two runs legitimately land on different
 * generation numbers and their final boards differ for that reason alone. Here the step
 * count is exact, so any difference between arms is a real divergence.
 *
 * <p>{@link LifeRules#next} is called directly rather than through the engine's command
 * queue, so the loop is synchronous and carries no scheduling nondeterminism. The board
 * is still seeded through {@link SimulationSeeder} so the starting state is byte-identical
 * to what the application starts with.
 *
 * <p>{@link Arrays#hashCode(boolean[])} is specified by the JDK (the 1231/1237 constants),
 * so it is deterministic across versions and vendors — which a hash relied on for a
 * cross-JDK comparison has to be.
 */
public final class GateCheck {

    private GateCheck() {
    }

    public static void main(String[] args) throws Exception {
        int steps = args.length > 0 ? Integer.parseInt(args[0]) : 10_000;

        LifeConfig config = LifeConfig.fromSystemProperties();
        int grid = config.gridSize();
        int partitions = config.defaultPartitions();

        // The engine is used only to seed, so the gate starts from exactly the board the
        // application starts from. It is never started, so it computes no generations.
        Generation board;
        try (SimulationEngine engine =
                new SimulationEngine(grid, grid, config.maxPartitions())) {
            engine.setPartitions(partitions);
            SimulationSeeder.seedBoard(engine, grid);
            board = engine.snapshot().generation();
        }

        System.out.printf(
                "GATE seed grid=%d partitions=%d population=%d aliveHash=%d%n",
                grid, partitions, board.population(),
                Arrays.hashCode(board.aliveCells()));

        try (ForkJoinPool pool = new ForkJoinPool(partitions)) {
            LifeRules rules = new LifeRules(pool);
            for (int i = 0; i < steps; i++) {
                board = rules.next(board, partitions);
            }
        }

        System.out.printf(
                "GATE result steps=%d gen=%d population=%d aliveHash=%d everAliveHash=%d%n",
                steps, board.number(), board.population(),
                Arrays.hashCode(board.aliveCells()),
                Arrays.hashCode(board.everAliveCells()));
    }
}
