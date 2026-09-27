package com.conway.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.Serial;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerListModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conway.config.BoardSizeOption;
import com.conway.config.LifeConfig;
import com.conway.engine.EngineListener;
import com.conway.engine.rules.PartitionPlanner;
import com.conway.engine.support.EngineSnapshot;
import com.conway.engine.support.SimulationSeeder;
import com.conway.engine.support.SimulationStats;
import com.conway.engine.SimulationEngine;
import com.conway.model.Generation;

/**
 * The application window: controls, board and the render loop that drives them.
 *
 * <p>The frame never computes anything. It translates user gestures into engine
 * commands and, on a fixed timer, pulls the newest published snapshot for display.
 * Decoupling the two means the simulation is not throttled by the repaint rate and
 * the repaint rate is not swamped by the simulation.
 *
 * <p>Confined to the event dispatch thread. Engine callbacks arrive on the simulation
 * thread (or, for {@code STOPPED}, on whichever thread closed the engine) and are
 * marshaled here explicitly.
 */
// Swing components inherit Serializable; this application never serializes them.
@SuppressWarnings("serial")
public final class LifeFrame extends JFrame {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(LifeFrame.class);

    private static final int SIDEBAR_WIDTH = 208;
    private static final int CONTROL_HEIGHT = 34;
    private static final int GAP = 10;

    private final LifeConfig config;
    private final Timer renderTimer;

    // Rebuilt when the board size changes: the engine's grid and the view's backing
    // image are both fixed at construction, so a new size means new instances.
    private SimulationEngine engine;
    private BoardView boardView;
    private int gridSize;

    private final JSpinner boardSpinner;
    private final JSpinner partitionSpinner;
    private final FlatButton startButton = new FlatButton("Start", FlatButton.Variant.PRIMARY);
    private final FlatButton stopButton = new FlatButton("Stop", FlatButton.Variant.SECONDARY);
    private final FlatButton stepButton = new FlatButton("Next generation", FlatButton.Variant.SECONDARY);
    private final FlatButton clearButton = new FlatButton("Clear", FlatButton.Variant.SECONDARY);
    private final FlatButton quitButton = new FlatButton("Quit", FlatButton.Variant.DANGER);

    private final JLabel generationValue = Theme.value("0");
    private final JLabel speedValue = Theme.value("0.0");
    private final JLabel populationValue = Theme.value("0");
    private final JLabel partitionsValue = Theme.value("1");
    private final JLabel stateValue = Theme.value("Idle");

    public LifeFrame(LifeConfig config, SimulationEngine engine) {
        super("Game of Life by Conway");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.config = Objects.requireNonNull(config, "config");
        this.gridSize = config.gridSize();

        this.boardView = newBoardView();
        this.partitionSpinner = new JSpinner(new SpinnerNumberModel(
                config.defaultPartitions(), 1, engine.maxPartitions(), 1));

        List<BoardSizeOption> boardSizes = BoardSizeOption.options(gridSize);
        this.boardSpinner = new JSpinner(new SpinnerListModel(boardSizes));
        this.boardSpinner.setValue(BoardSizeOption.find(boardSizes, gridSize));

        JPanel content = new JPanel(new BorderLayout(GAP, 0));
        content.setBorder(Theme.padding(GAP, GAP, 0, GAP));
        content.setBackground(Theme.BACKGROUND);
        content.add(buildSidebar(engine.maxPartitions(), config.defaultPartitions()),
                BorderLayout.WEST);
        content.add(boardView, BorderLayout.CENTER);
        content.add(buildStatusBar(), BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setBackground(Theme.BACKGROUND);

        wireActions();
        attachListener(engine);

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
        getRootPane().setDefaultButton(startButton);
    }

    /** Starts the render loop. Call after the frame is visible. */
    public void startRendering() {
        renderTimer.start();
    }

    private BoardView newBoardView() {
        return new BoardView(gridSize, gridSize, config.cellSizePxFor(gridSize),
                (row, col) -> engine.toggleCell(row, col));
    }

    private void attachListener(SimulationEngine target) {
        target.addListener(new EngineListener() {
            @Override
            public void onStateChanged(SimulationEngine.State state) {
                SwingUtilities.invokeLater(() -> applyState(state));
            }

            @Override
            public void onError(Throwable error) {
                SwingUtilities.invokeLater(() -> showError(error));
            }
        });
    }

    /**
     * Replaces the board with one of {@code cells} squared, discarding the current
     * simulation.
     *
     * <p>Both the engine's grid and the view's backing image are fixed at construction, so
     * this builds new instances and swaps them in rather than resizing in place. The old
     * engine is closed first, which stops its thread and releases its worker pool; leaving
     * it running would leak a thread and a pool per size change.
     *
     * <p>Runs on the event dispatch thread. The control that triggers it is disabled while
     * the simulation is running, so this never races a live run.
     */
    private void applyBoardSize(int cells) {
        if (cells == gridSize) {
            return;
        }
        LOG.info("Board size changing from {} to {}", gridSize, cells);

        renderTimer.stop();
        engine.close();

        gridSize = cells;
        int maxPartitions = Math.min(config.maxPartitions(), cells);
        engine = new SimulationEngine(cells, cells, maxPartitions);
        // Assigned before the spinner model is replaced below, so the change event that
        // fires reaches the new engine rather than the closed one.
        engine.setPartitions(PartitionPlanner.recommend(cells, maxPartitions));
        attachListener(engine);
        SimulationSeeder.seedBoard(engine, cells);

        BoardView replacement = newBoardView();
        getContentPane().remove(boardView);
        getContentPane().add(replacement, BorderLayout.CENTER);
        boardView = replacement;

        partitionSpinner.setModel(new SpinnerNumberModel(
                PartitionPlanner.recommend(cells, maxPartitions), 1, maxPartitions, 1));
        Theme.styleSpinner(partitionSpinner);

        applyState(engine.state());
        refresh();
        // Deliberately no pack(): the window keeps whatever size the user gave it, and the
        // board letterboxes into the space available.
        getContentPane().revalidate();
        getContentPane().repaint();
        renderTimer.start();
    }

    /**
     * The control column. A vertical {@link BoxLayout} with pinned component heights,
     * rather than a {@code GridLayout}, so a button stays button-sized instead of being
     * stretched to an equal share of the column.
     */
    private JPanel buildSidebar(int maxPartitions, int defaultPartitions) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Theme.SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                Theme.padding(16, 16, 16, 16)));
        panel.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
        panel.setMaximumSize(new Dimension(SIDEBAR_WIDTH, Integer.MAX_VALUE));

        JLabel heading = Theme.title("Game of Life");
        JLabel subheading = Theme.caption("Conway");
        heading.setAlignmentX(LEFT_ALIGNMENT);
        subheading.setAlignmentX(LEFT_ALIGNMENT);

        panel.add(heading);
        panel.add(Box.createVerticalStrut(2));
        panel.add(subheading);
        panel.add(Box.createVerticalStrut(18));
        panel.add(Theme.divider());
        panel.add(Box.createVerticalStrut(16));

        JLabel boardCaption = Theme.caption("Board size");
        boardCaption.setAlignmentX(LEFT_ALIGNMENT);
        String boardTooltip = "<html>Board edge in cells.<br>"
                + "Changing it restarts the simulation on a fresh board.</html>";
        boardCaption.setToolTipText(boardTooltip);
        boardSpinner.setToolTipText(boardTooltip);
        panel.add(boardCaption);
        panel.add(Box.createVerticalStrut(6));

        Theme.styleSpinner(boardSpinner);
        // The list is closed, so free-text entry could only produce an invalid value.
        if (boardSpinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            editor.getTextField().setEditable(false);
        }
        Theme.fixHeight(boardSpinner, 30);
        panel.add(boardSpinner);

        panel.add(Box.createVerticalStrut(14));

        JLabel partitionCaption = Theme.caption("Parallel partitions");
        partitionCaption.setAlignmentX(LEFT_ALIGNMENT);
        String tooltip = "<html>Number of row bands each generation is split across "
                + "(1 to " + maxPartitions + ").<br>"
                + "Defaults to " + defaultPartitions
                + ", chosen for this board size and machine.</html>";
        partitionCaption.setToolTipText(tooltip);
        partitionSpinner.setToolTipText(tooltip);
        panel.add(partitionCaption);
        panel.add(Box.createVerticalStrut(6));

        Theme.styleSpinner(partitionSpinner);
        Theme.fixHeight(partitionSpinner, 30);
        panel.add(partitionSpinner);

        panel.add(Box.createVerticalStrut(18));
        panel.add(Theme.divider());
        panel.add(Box.createVerticalStrut(16));

        for (FlatButton button : new FlatButton[] {startButton, stopButton, stepButton, clearButton}) {
            Theme.fixHeight(button, CONTROL_HEIGHT);
            panel.add(button);
            panel.add(Box.createVerticalStrut(8));
        }

        panel.add(Box.createVerticalGlue());
        panel.add(Theme.divider());
        panel.add(Box.createVerticalStrut(12));
        Theme.fixHeight(quitButton, CONTROL_HEIGHT);
        panel.add(quitButton);

        startButton.setMnemonic('S');
        stopButton.setMnemonic('T');
        stepButton.setMnemonic('N');
        clearButton.setMnemonic('C');
        quitButton.setMnemonic('Q');
        return panel;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBackground(Theme.BACKGROUND);
        bar.setBorder(Theme.padding(12, 2, 12, 2));

        addStatusSegment(bar, "Generation", generationValue, false);
        addStatusSegment(bar, "Gen/sec", speedValue, true);
        addStatusSegment(bar, "Population", populationValue, true);
        addStatusSegment(bar, "Partitions", partitionsValue, true);
        bar.add(Box.createHorizontalGlue());
        addStatusSegment(bar, "State", stateValue, false);
        return bar;
    }

    /** A caption above its value, optionally preceded by a vertical rule. */
    private static void addStatusSegment(JPanel bar, String caption, JLabel value, boolean leadingRule) {
        if (leadingRule) {
            bar.add(Box.createHorizontalStrut(16));
            JPanel rule = new JPanel();
            rule.setBackground(Theme.BORDER);
            rule.setPreferredSize(new Dimension(1, 22));
            rule.setMaximumSize(new Dimension(1, 22));
            bar.add(rule);
            bar.add(Box.createHorizontalStrut(16));
        }

        JPanel segment = new JPanel();
        segment.setLayout(new BoxLayout(segment, BoxLayout.Y_AXIS));
        segment.setBackground(Theme.BACKGROUND);

        JLabel captionLabel = Theme.caption(caption);
        captionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        value.setAlignmentX(Component.LEFT_ALIGNMENT);
        segment.add(captionLabel);
        segment.add(Box.createVerticalStrut(3));
        segment.add(value);
        bar.add(segment);
    }

    private void wireActions() {
        // A spinner is self-validating, so the parse-and-warn dialog that used to
        // guard a free-text field is gone along with the invalid states it allowed.
        partitionSpinner.addChangeListener(e -> engine.setPartitions((Integer) partitionSpinner.getValue()));
        boardSpinner.addChangeListener(
                e -> applyBoardSize(((BoardSizeOption) boardSpinner.getValue()).cells()));

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
        // One volatile read instead of three: the generation and its throughput figures
        // are published together, so they can never be observed mismatched.
        EngineSnapshot snapshot = engine.snapshot();
        Generation generation = snapshot.generation();
        boardView.setGeneration(generation);

        SimulationStats stats = snapshot.stats();
        generationValue.setText(String.format(Locale.ROOT, "%,d", stats.generation()));
        speedValue.setText(String.format(Locale.ROOT, "%,.1f", stats.generationsPerSecond()));
        partitionsValue.setText(Integer.toString(stats.partitions()));
        populationValue.setText(String.format(Locale.ROOT, "%,d", generation.population()));
    }

    private void applyState(SimulationEngine.State state) {
        boolean running = state == SimulationEngine.State.RUNNING;
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        stepButton.setEnabled(!running);
        clearButton.setEnabled(!running);
        partitionSpinner.setEnabled(!running);
        // Resizing discards the simulation, so it is only offered while stopped.
        boardSpinner.setEnabled(!running);

        stateValue.setText(running ? "Running" : "Idle");
        stateValue.setForeground(running ? Theme.ACCENT : Theme.TEXT_MUTED);
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
