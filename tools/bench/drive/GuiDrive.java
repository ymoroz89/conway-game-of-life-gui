package drive;

import java.awt.Frame;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import com.conway.config.LifeConfig;
import com.conway.engine.SimulationEngine;
import com.conway.engine.support.SimulationSeeder;
import com.conway.model.Generation;
import com.conway.ui.LifeGuiLauncher;

/**
 * Drives the real application for a fixed number of generations.
 *
 * <p>Wires the app exactly as {@link com.conway.Main} does -- same config, same engine,
 * same seed, same GUI launch -- and then issues the same engine commands the Start/Stop
 * buttons send. The point is that this measures the shipping application, GUI and engine
 * together, rather than a harness that happens to share the engine.
 *
 * <p>The app's own {@code Run finished:} log line remains the throughput ground truth.
 * The markers printed here exist to prove the run was valid, not to measure it:
 *
 * <pre>
 *   GUI-DRIVE BOOT|START|PAUSE &lt;epochMillis&gt;
 *   GUI-DRIVE WINDOW &lt;phase&gt; visible=.. active=.. focused=..
 *   GUI-DRIVE RESULT gens=.. elapsedMs=.. rate=..
 * </pre>
 *
 * <p>The WINDOW marker is the one that earns its keep. macOS gives a foreground window
 * different CPU treatment -- worth up to 3.3x on this workload, against a JDK effect of a
 * few percent -- and the treatment is graded rather than binary, so a window that merely
 * loses focus mid-run produces a plausible-looking number from a different environment.
 * Recording the state at start and at pause lets the analysis reject such a run on the
 * marker rather than on its throughput looking odd.
 *
 * <p>Compile once against a release=21 jar so a single bytecode (major 65) drives every
 * JVM in a comparison:
 *
 * <pre>
 *   javac -cp tools/bench/shared-r21.jar -d tools/bench/out tools/bench/drive/GuiDrive.java
 * </pre>
 *
 * <p>Usage: {@code drive.GuiDrive <generations> [startDelaySeconds]}
 */
public final class GuiDrive {

    /** Polling granularity. At ~20k gen/s this overshoots the target by a few hundred
     *  generations, which per-generation metrics absorb; polling harder would steal CPU
     *  from the thing being measured. */
    private static final long POLL_INTERVAL_MS = 5;

    private static final long WINDOW_WAIT_TIMEOUT_MS = 30_000;

    private GuiDrive() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: drive.GuiDrive <generations> [startDelaySeconds]");
            System.exit(2);
        }
        long targetGenerations = Long.parseLong(args[0]);
        long startDelayMs = args.length > 1 ? Long.parseLong(args[1]) * 1000L : 3000L;

        Thread.setDefaultUncaughtExceptionHandler(
                (thread, error) -> {
                    System.err.println("Uncaught exception on " + thread.getName());
                    error.printStackTrace();
                });

        System.out.println("GUI-DRIVE BOOT " + System.currentTimeMillis());

        LifeConfig config = LifeConfig.fromSystemProperties();
        SimulationEngine engine = new SimulationEngine(
                config.gridSize(), config.gridSize(), config.maxPartitions());
        engine.setPartitions(config.defaultPartitions());
        SimulationSeeder.seedBoard(engine, config.gridSize());

        LifeGuiLauncher.launch(config, engine);

        Frame frame = awaitFrame();
        if (frame == null) {
            System.err.println("GUI-DRIVE ERROR no application window appeared");
            engine.close();
            System.exit(3);
        }
        raise(frame);

        // Let the window settle and the render loop reach steady state before starting.
        Thread.sleep(startDelayMs);
        reportWindow(frame, "at-start");

        long startMillis = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        engine.start();
        System.out.println("GUI-DRIVE START " + startMillis);

        long generations = awaitGenerations(engine, targetGenerations);

        engine.pause();
        long elapsedNanos = System.nanoTime() - startNanos;
        System.out.println("GUI-DRIVE PAUSE " + System.currentTimeMillis());
        reportWindow(frame, "at-pause");

        double elapsedMs = elapsedNanos / 1_000_000.0;
        double rate = generations / (elapsedNanos / 1_000_000_000.0);
        System.out.printf(
                "GUI-DRIVE RESULT gens=%d elapsedMs=%.1f rate=%.1f%n",
                generations, elapsedMs, rate);

        // Final-state provenance, NOT a cross-arm determinism gate. A free-running GUI
        // stops on the first snapshot at or past the target, so arms legitimately land on
        // different generation numbers (2,000,143-2,000,311 was the observed spread) and
        // their final boards differ for that reason alone. Comparing arms at an exact step
        // count is drive.GateCheck's job. This line is here to catch gross divergence and
        // to record what state the timed run actually ended on.
        Generation last = engine.snapshot().generation();
        System.out.printf(
                "GUI-DRIVE FINALSTATE gen=%d population=%d aliveHash=%d everAliveHash=%d%n",
                last.number(), last.population(),
                java.util.Arrays.hashCode(last.aliveCells()),
                java.util.Arrays.hashCode(last.everAliveCells()));

        // Give the engine's own "Run finished:" line -- the throughput ground truth --
        // time to reach the log before the JVM exits and JFR dumps.
        Thread.sleep(250);
        engine.close();
        System.exit(0);
    }

    /** The launcher builds the frame on the EDT, so it is not there when launch returns. */
    private static Frame awaitFrame() throws Exception {
        long deadline = System.currentTimeMillis() + WINDOW_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<Frame> found = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof Frame candidate && candidate.isVisible()) {
                        found.set(candidate);
                        return;
                    }
                }
            });
            if (found.get() != null) {
                return found.get();
            }
            Thread.sleep(50);
        }
        return null;
    }

    /** Best-effort foreground. macOS may decline, which is exactly why it is reported. */
    private static void raise(Frame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame.setExtendedState(Frame.NORMAL);
            frame.toFront();
            frame.requestFocus();
        });
    }

    private static void reportWindow(Frame frame, String phase) throws Exception {
        AtomicReference<String> state = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> state.set(String.format(
                "visible=%s active=%s focused=%s",
                frame.isVisible(), frame.isActive(), frame.isFocused())));
        System.out.println("GUI-DRIVE WINDOW " + phase + " " + state.get());
    }

    /**
     * Waits on the engine's published snapshot rather than counting generations here,
     * so the driver observes exactly what the renderer observes and adds no work per
     * generation to the thing being measured.
     */
    private static long awaitGenerations(SimulationEngine engine, long target)
            throws InterruptedException {
        long generations = 0;
        long lastProgress = 0;
        while (generations < target) {
            generations = engine.snapshot().generation().number();
            if (generations - lastProgress >= target / 2 && generations < target) {
                System.out.println("GUI-DRIVE PROGRESS " + generations);
                lastProgress = generations;
            }
            if (engine.state() == SimulationEngine.State.FAILED) {
                System.err.println("GUI-DRIVE ERROR engine entered FAILED at " + generations);
                break;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return generations;
    }
}
