package com.conway.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Locale;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conway.config.LifeConfig;
import com.conway.engine.EngineListener;
import com.conway.engine.SimulationEngine;
import com.conway.engine.SimulationStats;

/**
 * The application window: controls, board and the render loop that drives them.
 *
 * <p>The frame never computes anything. It translates user gestures into engine
 * commands and, on a fixed timer, pulls the newest published snapshot for display.
 * Decoupling the two means the simulation is not throttled by the repaint rate and
 * the repaint rate is not swamped by the simulation.
 *
 * <p>Confined to the event dispatch thread. Engine callbacks arrive on the simulation
 * thread and are marshalled here explicitly.
 */
@SuppressWarnings("serial") // Swing components inherit Serializable; this application never serialises them.
public final class LifeFrame extends JFrame {

    private static final Logger LOG = LoggerFactory.getLogger(LifeFrame.class);

    private final SimulationEngine engine;
    private final BoardView boardView;
    private final Timer renderTimer;

    private final JSpinner partitionSpinner;
    private final JButton startButton = new JButton("Start");
    private final JButton stopButton = new JButton("Stop");
    private final JButton stepButton = new JButton("Next generation");
    private final JButton clearButton = new JButton("Clear");
    private final JButton quitButton = new JButton("Quit");
    private final JLabel statusLabel = new JLabel(" ");

    public LifeFrame(LifeConfig config, SimulationEngine engine) {
        super("Game of Life by Conway");
        this.engine = Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(config, "config");

        this.boardView = new BoardView(
                config.gridSize(), config.gridSize(), config.cellSizePx(), engine::toggleCell);
        this.partitionSpinner = new JSpinner(
                new SpinnerNumberModel(1, 1, engine.maxPartitions(), 1));

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.setBackground(Color.LIGHT_GRAY);
        content.add(buildControlPanel(engine.maxPartitions()), BorderLayout.WEST);
        content.add(boardView, BorderLayout.CENTER);
        content.add(statusLabel, BorderLayout.SOUTH);
        setContentPane(content);

        wireActions();

        engine.addListener(new EngineListener() {
            @Override
            public void onStateChanged(SimulationEngine.State state) {
                SwingUtilities.invokeLater(() -> applyState(state));
            }

            @Override
            public void onError(Throwable error) {
                SwingUtilities.invokeLater(() -> showError(error));
            }
        });

        // Rendering is driven at a fixed rate, independent of how fast generations
        // are produced. Previously every generation queued a repaint, so throughput
        // measurements partly reflected repaint backpressure.
        int delayMillis = Math.max(1, 1000 / config.targetFps());
        this.renderTimer = new Timer(delayMillis, e -> refresh());
        this.renderTimer.setCoalesce(true);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                shutdown();
            }
        });

        applyState(engine.state());
        refresh();
        pack();
        setMinimumSize(new Dimension(getWidth() / 2, getHeight() / 2));
        setLocationRelativeTo(null);
    }

    /** Starts the render loop. Call after the frame is visible. */
    public void startRendering() {
        renderTimer.start();
    }

    private JPanel buildControlPanel(int maxPartitions) {
        JLabel partitionLabel = new JLabel("Parallel partitions");
        partitionLabel.setToolTipText(
                "Number of row bands each generation is split across (1 to " + maxPartitions + ")");

        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 6));
        panel.setBackground(Color.LIGHT_GRAY);
        panel.add(partitionLabel);
        panel.add(partitionSpinner);
        panel.add(startButton);
        panel.add(stopButton);
        panel.add(stepButton);
        panel.add(clearButton);
        panel.add(quitButton);
        panel.add(Box.createVerticalGlue());

        startButton.setMnemonic('S');
        stopButton.setMnemonic('T');
        stepButton.setMnemonic('N');
        clearButton.setMnemonic('C');
        quitButton.setMnemonic('Q');
        return panel;
    }

    private void wireActions() {
        // A spinner is self-validating, so the parse-and-warn dialog that used to
        // guard a free-text field is gone along with the invalid states it allowed.
        partitionSpinner.addChangeListener(e -> engine.setPartitions((Integer) partitionSpinner.getValue()));

        startButton.addActionListener(e -> {
            LOG.info("Start requested");
            engine.setPartitions((Integer) partitionSpinner.getValue());
            engine.start();
        });
        stopButton.addActionListener(e -> {
            LOG.info("Stop requested");
            engine.pause();
        });
        stepButton.addActionListener(e -> {
            LOG.info("Single step requested");
            engine.setPartitions((Integer) partitionSpinner.getValue());
            engine.step();
        });
        clearButton.addActionListener(e -> {
            LOG.info("Clear requested");
            engine.clear();
        });
        quitButton.addActionListener(e -> {
            LOG.info("Quit requested");
            dispose();
        });
    }

    /** Pulls the latest snapshot and statistics. Runs on the event dispatch thread. */
    private void refresh() {
        boardView.setGeneration(engine.currentGeneration());

        SimulationStats stats = engine.stats();
        statusLabel.setText(String.format(Locale.ROOT,
                "Generation %,d   |   %,.1f generations/s   |   %d partition(s)",
                stats.generation(), stats.generationsPerSecond(), stats.partitions()));
    }

    private void applyState(SimulationEngine.State state) {
        boolean running = state == SimulationEngine.State.RUNNING;
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        stepButton.setEnabled(!running);
        clearButton.setEnabled(!running);
        partitionSpinner.setEnabled(!running);
        // Editing stays available while running: cell toggles are queued to the
        // simulation thread rather than written directly to shared state.
    }

    private void showError(Throwable error) {
        JOptionPane.showMessageDialog(this,
                "The simulation stopped because a generation could not be computed:\n"
                        + error.getMessage(),
                "Simulation error", JOptionPane.ERROR_MESSAGE);
    }

    private void shutdown() {
        renderTimer.stop();
        // Replaces System.exit(0): the engine stops its thread and releases its
        // worker pool, so shutdown is orderly rather than abrupt.
        engine.close();
    }
}
