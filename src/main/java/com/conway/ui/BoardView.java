package com.conway.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Objects;

import javax.swing.JPanel;

import com.conway.model.Generation;

/**
 * Renders the board.
 *
 * <p>Painting writes cell colours straight into the {@code int[]} backing a single
 * {@link BufferedImage} and then performs one scaled blit. The previous implementation
 * issued one {@code fillRect} per cell &mdash; 40k of them for a 201x201 board &mdash;
 * and called {@code getInsets()} inside the loop, allocating an {@code Insets} object
 * per cell on every frame.
 *
 * <p>The component is confined to the Swing event dispatch thread. It never reads
 * simulation state directly; the frame pushes an immutable {@link Generation}
 * snapshot in, and repaints only when the snapshot actually changed.
 */
@SuppressWarnings("serial") // Swing components inherit Serializable; this application never serialises them.
public final class BoardView extends JPanel {

    /**
     * Cells narrower than this are drawn solid. A separator costs one pixel per axis, so
     * below this size it would consume more of the cell than the cell's own colour and
     * the board reads as a grey mesh rather than as a population.
     */
    private static final int MIN_CELL_SIZE_FOR_GRID = 3;

    /** Notified when the user presses a cell. */
    @FunctionalInterface
    public interface CellPressListener {
        void cellPressed(int row, int col);
    }

    private final int rows;
    private final int columns;
    private final int cellSize;
    private final CellGeometry geometry;
    private final BufferedImage image;
    private final int[] pixels;

    private Generation displayed;

    public BoardView(int rows, int columns, int cellSize, CellPressListener listener) {
        if (cellSize < 1) {
            throw new IllegalArgumentException("cellSize must be at least 1 but was " + cellSize);
        }
        Objects.requireNonNull(listener, "listener");
        this.rows = rows;
        this.columns = columns;
        this.cellSize = cellSize;
        this.geometry = new CellGeometry(rows, columns);

        this.image = new BufferedImage(columns * cellSize, rows * cellSize, BufferedImage.TYPE_INT_RGB);
        this.pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
        setMinimumSize(new Dimension(columns, rows));
        setOpaque(true);
        setBackground(new Color(Theme.BOARD_EMPTY));

        // MouseAdapter, so unused callbacks are genuinely absent. Overriding every
        // MouseListener method and throwing from the ones that are not needed meant
        // an exception on every pointer entry, exit and click.
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = geometry.rowAt(e.getY(), getHeight());
                int col = geometry.columnAt(e.getX(), getWidth());
                if (row != CellGeometry.NO_CELL && col != CellGeometry.NO_CELL) {
                    listener.cellPressed(row, col);
                }
            }
        });
    }

    /**
     * Shows {@code generation}, repainting only if it differs from what is already on
     * screen. Snapshots are immutable and a new instance is published per change, so
     * reference identity is a sufficient and exact change test.
     *
     * <p>Must be called on the event dispatch thread.
     */
    public void setGeneration(Generation generation) {
        if (generation != displayed) {
            displayed = generation;
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Generation generation = displayed;
        if (generation == null) {
            return;
        }
        renderInto(generation);

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(image, 0, 0, getWidth(), getHeight(), null);
        } finally {
            g2.dispose();
        }
    }

    private void renderInto(Generation generation) {
        int boardRows = Math.min(rows, generation.height());
        int boardColumns = Math.min(columns, generation.width());
        boolean[] alive = generation.aliveCells();
        boolean[] everAlive = generation.everAliveCells();
        int stride = image.getWidth();

        // One pixel of separator along the trailing edges only. The previous inset of one
        // pixel on every side left a 4px cell with a 2x2 interior, so three quarters of
        // the board was grid line.
        int gap = cellSize >= MIN_CELL_SIZE_FOR_GRID ? 1 : 0;
        int inner = cellSize - gap;

        // Covers the separators and any area not written below, which matters when the
        // board is smaller than the view.
        Arrays.fill(pixels, Theme.BOARD_GRID);

        for (int row = 0; row < boardRows; row++) {
            int cellRowBase = row * generation.width();
            int y0 = row * cellSize;
            for (int col = 0; col < boardColumns; col++) {
                int rgb = colorOf(alive, everAlive, cellRowBase + col);
                int x0 = col * cellSize;
                for (int y = 0; y < inner; y++) {
                    int offset = (y0 + y) * stride + x0;
                    Arrays.fill(pixels, offset, offset + inner, rgb);
                }
            }
        }
    }

    private static int colorOf(boolean[] alive, boolean[] everAlive, int index) {
        if (alive[index]) {
            return Theme.BOARD_ALIVE;
        }
        return everAlive[index] ? Theme.BOARD_TRAIL : Theme.BOARD_EMPTY;
    }
}
