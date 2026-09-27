package com.conway.ui;

import com.conway.model.Generation;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.Serial;
import java.util.Arrays;
import java.util.Objects;

/**
 * Renders the board.
 *
 * <p>Painting writes cell colors straight into the {@code int[]} backing a single
 * {@link BufferedImage} and then performs one scaled blit. The previous implementation
 * issued one {@code fillRect} per cell &mdash; 40k of them for a 201x201 board &mdash;
 * and called {@code getInsets()} inside the loop, allocating an {@code Insets} object
 * per cell on every frame.
 *
 * <p>The backing image is rendered at the device scale factor (2x on a Retina display),
 * so the blit is 1:1 with physical pixels and cells stay crisp instead of being
 * upscaled and blurred. Component coordinates remain logical; only the image is denser.
 * Headless environments (tests, build agents) have no display, so the scale falls back
 * to 1.0 and the image is exactly the logical size.
 *
 * <p>The component is confined to the Swing event dispatch thread. It never reads
 * simulation state directly; the frame pushes an immutable {@link Generation}
 * snapshot in, and repaints only when the snapshot actually changed.
 */
// Swing components inherit Serializable; this application never serializes them.
@SuppressWarnings("serial")
public final class BoardView extends JPanel {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Cells narrower than this are drawn solid. A separator costs one pixel per axis, so
     * below this size it would consume more of the cell than the cell's own color and
     * the board reads as a gray mesh rather than as a population. The threshold is in
     * logical pixels; the separator itself is scaled with the device.
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
    /** Cell edge in physical pixels: {@code cellSize} scaled to the display. */
    private final int scaledCellSize;
    /** Separator width in physical pixels: one logical pixel scaled to the display. */
    private final int scaledSeparator;
    private final CellGeometry geometry;
    private final BufferedImage image;
    private final int[] pixels;

    private Generation displayed;

    /**
     * The generation the backing image currently shows, which is not always {@link #displayed}:
     * a repaint triggered by exposure or a resize leaves the image already correct. Holding it
     * lets a new generation be applied as a difference rather than a full redraw.
     */
    private Generation rendered;

    public BoardView(int rows, int columns, int cellSize, CellPressListener listener) {
        if (cellSize < 1) {
            throw new IllegalArgumentException("cellSize must be at least 1 but was " + cellSize);
        }
        Objects.requireNonNull(listener, "listener");
        this.rows = rows;
        this.columns = columns;
        this.cellSize = cellSize;
        this.geometry = new CellGeometry(rows, columns);

        double deviceScale = deviceScale(this);
        this.scaledCellSize = Math.max(1, (int) Math.round(cellSize * deviceScale));
        this.scaledSeparator = Math.max(1, (int) Math.round(deviceScale));

        this.image = new BufferedImage(
                columns * scaledCellSize, rows * scaledCellSize, BufferedImage.TYPE_INT_RGB);
        this.pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        // The preferred size stays in logical pixels: the component's coordinates are
        // logical, and only the backing image is denser.
        setPreferredSize(new Dimension(columns * cellSize, rows * cellSize));
        setMinimumSize(new Dimension(columns, rows));
        setOpaque(true);
        // The margin around a letterboxed board belongs to the window, not the board.
        setBackground(Theme.BACKGROUND);

        // MouseAdapter, so unused callbacks are genuinely absent. Overriding every
        // MouseListener method and throwing from the ones that are not needed meant an
        // exception on every pointer entry, exit and click.
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // Mapped through the drawn rectangle rather than the component bounds, so
                // a press lands on the cell under the pointer even when the component is
                // wider or taller than the board and the board is inset within it.
                Rectangle viewport = viewport();
                int row = geometry.rowAt(e.getY() - viewport.y, viewport.height);
                int col = geometry.columnAt(e.getX() - viewport.x, viewport.width);
                if (row != CellGeometry.NO_CELL && col != CellGeometry.NO_CELL) {
                    listener.cellPressed(row, col);
                }
            }
        });
    }

    /**
     * The device scale factor for the screen the board will be shown on, or 1.0 when
     * there is no display (headless tests, build agents).
     *
     * <p>Queried from the component's graphics configuration, which for a component not
     * yet added to a window resolves to the default screen. The window is created on the
     * event dispatch thread and shown on the primary display, so the default screen is
     * the right answer for this application.
     */
    private static double deviceScale(Component component) {
        try {
            GraphicsConfiguration gc = component.getGraphicsConfiguration();
            if (gc == null) {
                gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice().getDefaultConfiguration();
            }
            double scale = gc.getDefaultTransform().getScaleX();
            return scale > 0 ? scale : 1.0;
        } catch (HeadlessException e) {
            return 1.0;
        }
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

        Rectangle viewport = viewport();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(image, viewport.x, viewport.y, viewport.width, viewport.height, null);
            // Outline the board itself rather than the component, so the frame hugs the
            // board at any window size instead of floating out at the component edges.
            g2.setColor(Theme.BORDER);
            g2.drawRect(viewport.x, viewport.y, viewport.width - 1, viewport.height - 1);
        } finally {
            g2.dispose();
        }
    }

    /**
     * The rectangle the board is drawn into: the largest area with the board's own aspect
     * ratio that fits the component, centered, with any leftover space left as margin.
     *
     * <p>Stretching to the component bounds instead would make cells non-square whenever
     * the window is not square &mdash; maximising the window turned a 201x201 board into a
     * wide grid of rectangles and skewed the diagonals the simulation produces.
     *
     * <p>The image dimensions are physical pixels, but the viewport is in logical
     * coordinates: the blit maps the denser image into the logical rectangle, and the
     * display's own transform turns that into a 1:1 physical blit on a high-DPI screen.
     */
    Rectangle viewport() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return new Rectangle(0, 0, 0, 0);
        }
        double scale = Math.min(
                width / (double) image.getWidth(),
                height / (double) image.getHeight());
        int drawnWidth = Math.max(1, (int) Math.floor(image.getWidth() * scale));
        int drawnHeight = Math.max(1, (int) Math.floor(image.getHeight() * scale));
        return new Rectangle(
                (width - drawnWidth) / 2, (height - drawnHeight) / 2, drawnWidth, drawnHeight);
    }

    /**
     * Brings the backing image up to date with {@code generation}.
     *
     * <p>Three paths, cheapest first. If the image already shows this generation there is
     * nothing to do, which covers every repaint caused by exposure, a resize or a focus
     * change. If the previous generation is the same size, only the cells whose color
     * differs are rewritten. Otherwise, the whole image is redrawn.
     *
     * <p>Profiling showed this method rewriting all 40,401 cells and clearing the whole
     * pixel buffer on every paint, while the engine was evaluating around 2% of the board
     * per generation. That made the event dispatch thread cost as much as the entire
     * simulation control path.
     */
    private void renderInto(Generation generation) {
        if (rendered == generation) {
            return;
        }
        if (rendered == null
                || rendered.width() != generation.width()
                || rendered.height() != generation.height()) {
            renderAll(generation);
        } else {
            renderChanges(rendered, generation);
        }
        rendered = generation;
    }

    /** Redraws every cell, plus the separators and any area the board does not cover. */
    private void renderAll(Generation generation) {
        int boardRows = Math.min(rows, generation.height());
        int boardColumns = Math.min(columns, generation.width());
        boolean[] alive = generation.aliveCells();
        boolean[] everAlive = generation.everAliveCells();
        int stride = image.getWidth();
        int inner = innerCellSize();

        // Covers the separators and any area not written below, which matters when the
        // board is smaller than the view. Only the full path needs this: separators never
        // change, so an incremental update must not repaint over them.
        Arrays.fill(pixels, Theme.BOARD_GRID);

        for (int row = 0; row < boardRows; row++) {
            int cellRowBase = row * generation.width();
            for (int col = 0; col < boardColumns; col++) {
                writeCell(row, col, colorOf(alive, everAlive, cellRowBase + col), inner, stride);
            }
        }
    }

    /**
     * Rewrites only the cells whose color differs between the two generations.
     *
     * <p>The comparison still visits every cell, but it reads two booleans per cell instead
     * of writing {@code inner * inner} pixels, and it skips the full-buffer clear. Both
     * generations must have the same dimensions; the caller guarantees that.
     */
    private void renderChanges(Generation previous, Generation current) {
        int boardRows = Math.min(rows, current.height());
        int boardColumns = Math.min(columns, current.width());
        boolean[] previousAlive = previous.aliveCells();
        boolean[] previousEver = previous.everAliveCells();
        boolean[] alive = current.aliveCells();
        boolean[] everAlive = current.everAliveCells();
        int stride = image.getWidth();
        int inner = innerCellSize();
        int width = current.width();

        for (int row = 0; row < boardRows; row++) {
            int cellRowBase = row * width;
            for (int col = 0; col < boardColumns; col++) {
                int index = cellRowBase + col;
                int rgb = colorOf(alive, everAlive, index);
                if (rgb != colorOf(previousAlive, previousEver, index)) {
                    writeCell(row, col, rgb, inner, stride);
                }
            }
        }
    }

    private void writeCell(int row, int col, int rgb, int inner, int stride) {
        int y0 = row * scaledCellSize;
        int x0 = col * scaledCellSize;
        for (int y = 0; y < inner; y++) {
            int offset = (y0 + y) * stride + x0;
            Arrays.fill(pixels, offset, offset + inner, rgb);
        }
    }

    /**
     * Edge of the colored part of a cell, in physical pixels. One separator along the
     * trailing edges only; the previous inset of one pixel on every side left a 4px cell
     * with a 2x2 interior, so three quarters of the board was grid line.
     */
    private int innerCellSize() {
        int inner = scaledCellSize - (cellSize >= MIN_CELL_SIZE_FOR_GRID ? scaledSeparator : 0);
        return Math.max(1, inner);
    }

    private static int colorOf(boolean[] alive, boolean[] everAlive, int index) {
        if (alive[index]) {
            return Theme.BOARD_ALIVE;
        }
        return everAlive[index] ? Theme.BOARD_TRAIL : Theme.BOARD_EMPTY;
    }
}