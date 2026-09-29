package com.conway.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.conway.model.Generation;

class BoardViewTest {

    private static final int ROWS = 10;
    private static final int COLUMNS = 10;
    private static final int CELL_SIZE = 4;

    private final List<int[]> presses = new ArrayList<>();
    private final BoardView view =
            new BoardView(ROWS, COLUMNS, CELL_SIZE, (row, col) -> presses.add(new int[] {row, col}));

    @Test
    @DisplayName("Pointer entry, exit, click and release are all handled without throwing")
    void nonPressMouseEventsAreHarmless() {
        view.setSize(40, 40);

        // The previous listener implemented every MouseListener method and threw
        // UnsupportedOperationException from the three it did not need, so simply
        // moving the pointer over the board raised an exception on the EDT.
        assertDoesNotThrow(() -> {
            dispatch(MouseEvent.MOUSE_ENTERED, 5, 5);
            dispatch(MouseEvent.MOUSE_EXITED, 5, 5);
            dispatch(MouseEvent.MOUSE_CLICKED, 5, 5);
            dispatch(MouseEvent.MOUSE_RELEASED, 5, 5);
        });
        assertEquals(0, presses.size(), "Only a press should report a cell");
    }

    @Test
    @DisplayName("A press reports the cell under the pointer")
    void pressReportsCorrectCell() {
        view.setSize(40, 40); // 4px per cell

        dispatch(MouseEvent.MOUSE_PRESSED, 10, 22);

        assertEquals(1, presses.size());
        assertEquals(5, presses.get(0)[0], "row");
        assertEquals(2, presses.get(0)[1], "column");
    }

    @Test
    @DisplayName("The mapping still holds when the component is scaled away from its natural size")
    void pressMapsCorrectlyWhenScaled() {
        view.setSize(200, 200); // 20px per cell

        dispatch(MouseEvent.MOUSE_PRESSED, 0, 0);
        dispatch(MouseEvent.MOUSE_PRESSED, 199, 199);

        assertEquals(2, presses.size());
        assertEquals(0, presses.get(0)[0]);
        assertEquals(0, presses.get(0)[1]);
        assertEquals(9, presses.get(1)[0]);
        assertEquals(9, presses.get(1)[1]);
    }

    @Test
    @DisplayName("Presses outside the board are ignored")
    void pressOutsideBoardIsIgnored() {
        view.setSize(40, 40);

        dispatch(MouseEvent.MOUSE_PRESSED, -1, 5);
        dispatch(MouseEvent.MOUSE_PRESSED, 5, -1);
        dispatch(MouseEvent.MOUSE_PRESSED, 40, 5);
        dispatch(MouseEvent.MOUSE_PRESSED, 5, 40);

        assertEquals(0, presses.size());
    }

    @Test
    @DisplayName("Alive, died-here and never-populated cells each render in their theme color")
    void rendersCellStates() {
        boolean[] alive = new boolean[ROWS * COLUMNS];
        boolean[] everAlive = new boolean[ROWS * COLUMNS];
        alive[0] = true;                        // (0, 0) is alive
        everAlive[0] = true;
        everAlive[1 * COLUMNS + 1] = true;      // (1, 1) died here
        Generation board = Generation.adopt(COLUMNS, ROWS, alive, everAlive, 0L);

        view.setSize(COLUMNS * CELL_SIZE, ROWS * CELL_SIZE);
        view.setGeneration(board);

        BufferedImage target =
                new BufferedImage(COLUMNS * CELL_SIZE, ROWS * CELL_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            view.paint(g);
        } finally {
            g.dispose();
        }

        // Assert against the theme rather than repeating hex literals, so the palette has
        // exactly one definition and this test cannot drift away from what is rendered.
        assertEquals(Theme.BOARD_ALIVE, rgb(target, 0, 0), "alive cell");
        assertEquals(Theme.BOARD_TRAIL, rgb(target, 1, 1), "cell that has died");
        assertEquals(Theme.BOARD_EMPTY, rgb(target, 5, 5), "never populated cell");

        // The separator occupies the trailing edge of each cell, so it sits at the last
        // pixel of the first cell rather than at the origin.
        assertEquals(Theme.BOARD_GRID,
                target.getRGB(CELL_SIZE - 1, CELL_SIZE - 1) & 0xFFFFFF, "cell separator");
    }

    @Test
    @DisplayName("The board keeps its aspect ratio and centers itself in a non-square component")
    void boardIsLetterboxedRatherThanStretched() {
        // Maximizing the window used to stretch a square board across the full width,
        // making cells rectangular and skewing the diagonals the simulation produces.
        view.setSize(400, 200);

        java.awt.Rectangle viewport = view.viewport();

        assertEquals(200, viewport.width, "width should be limited by the shorter axis");
        assertEquals(200, viewport.height, "board must stay square");
        assertEquals(100, viewport.x, "board should be centered horizontally");
        assertEquals(0, viewport.y);
    }

    @Test
    @DisplayName("Presses map through the letterboxed board, not the component bounds")
    void pressMapsThroughLetterbox() {
        view.setSize(400, 200); // board drawn as 200x200 at x=100, so 20px per cell

        dispatch(MouseEvent.MOUSE_PRESSED, 100, 0);   // first cell of the board
        dispatch(MouseEvent.MOUSE_PRESSED, 299, 199); // last cell of the board

        assertEquals(2, presses.size());
        assertEquals(0, presses.get(0)[0], "row");
        assertEquals(0, presses.get(0)[1], "column");
        assertEquals(9, presses.get(1)[0], "row");
        assertEquals(9, presses.get(1)[1], "column");
    }

    @Test
    @DisplayName("Presses in the letterbox margin report no cell")
    void pressInMarginIsIgnored() {
        view.setSize(400, 200); // 100px of margin either side of the board

        dispatch(MouseEvent.MOUSE_PRESSED, 50, 100);  // left margin
        dispatch(MouseEvent.MOUSE_PRESSED, 350, 100); // right margin

        assertEquals(0, presses.size());
    }

    @Test
    @DisplayName("A zero-sized component yields an empty viewport instead of failing")
    void zeroSizedComponentIsSafe() {
        view.setSize(0, 0);

        java.awt.Rectangle viewport = view.viewport();

        assertEquals(0, viewport.width);
        assertEquals(0, viewport.height);
    }

    @Test
    @DisplayName("Rendering a sequence incrementally matches rendering the last board from scratch")
    void incrementalRenderMatchesFullRender() {
        // The renderer updates the image as a difference against the previous generation
        // instead of redrawing every cell. The whole optimization rests on the two paths
        // producing identical pixels, so that is asserted directly.
        Generation first = generation(cells(0), cells(0));                 // (0,0) alive
        Generation second = generation(cells(11), cells(0, 11));           // (0,0) died, (1,1) alive
        Generation third = generation(cells(22), cells(11, 22));           // (1,1) died, (2,2) alive,
                                                                           // (0,0) reverted to empty

        BufferedImage incremental = renderSequence(first, second, third);
        BufferedImage fromScratch = renderSequence(third);

        assertImagesEqual(fromScratch, incremental);
    }

    @Test
    @DisplayName("Incremental and full rendering agree across a long random sequence")
    void incrementalRenderMatchesFullRenderOverManySteps() {
        Random random = new Random(20260731L);
        BoardView incremental = newView();
        incremental.setSize(COLUMNS * CELL_SIZE, ROWS * CELL_SIZE);

        for (int step = 0; step < 25; step++) {
            boolean[] alive = new boolean[ROWS * COLUMNS];
            boolean[] everAlive = new boolean[ROWS * COLUMNS];
            for (int i = 0; i < alive.length; i++) {
                alive[i] = random.nextBoolean();
                // Independent of alive, so trail cells and impossible-looking combinations
                // are both exercised.
                everAlive[i] = alive[i] || random.nextBoolean();
            }
            Generation board = Generation.adopt(COLUMNS, ROWS, alive, everAlive, step);

            incremental.setGeneration(board);
            BufferedImage stepped = paint(incremental);
            BufferedImage fresh = renderSequence(board);

            assertImagesEqual(fresh, stepped, "step " + step);
        }
    }

    @Test
    @DisplayName("Cell separators survive incremental updates")
    void separatorsAreNotOverwrittenByIncrementalUpdates() {
        // The full-redraw path clears the buffer to the separator color; the incremental
        // path must not paint over it when it rewrites a cell.
        BoardView view = newView();
        view.setSize(COLUMNS * CELL_SIZE, ROWS * CELL_SIZE);

        view.setGeneration(generation(cells(0), cells(0)));
        paint(view);
        view.setGeneration(generation(cells(0, 1), cells(0, 1)));
        BufferedImage image = paint(view);

        assertEquals(Theme.BOARD_GRID, image.getRGB(CELL_SIZE - 1, CELL_SIZE - 1) & 0xFFFFFF,
                "separator after an incremental update");
    }

    @Test
    @DisplayName("A board of different dimensions falls back to a full redraw")
    void differentDimensionsTriggerFullRedraw() {
        BoardView view = newView();
        view.setSize(COLUMNS * CELL_SIZE, ROWS * CELL_SIZE);

        view.setGeneration(generation(cells(0), cells(0)));
        paint(view);

        // Smaller than the view: the area the board no longer covers must be repainted
        // rather than left showing the previous board.
        Generation smaller = Generation.empty(COLUMNS / 2, ROWS / 2).withCell(0, 0, true);
        view.setGeneration(smaller);
        BufferedImage image = paint(view);

        assertEquals(Theme.BOARD_ALIVE, rgb(image, 0, 0), "the smaller board still draws");
        assertEquals(Theme.BOARD_GRID, rgb(image, ROWS - 1, COLUMNS - 1),
                "area outside the smaller board is cleared, not stale");
    }

    @Test
    @DisplayName("Painting before a generation arrives does not fail")
    void paintingWithoutAGenerationIsSafe() {
        view.setSize(40, 40);
        BufferedImage target = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            assertDoesNotThrow(() -> view.paint(g));
        } finally {
            g.dispose();
        }
    }

    @Test
    @DisplayName("The preferred size covers every cell")
    void preferredSizeMatchesTheGrid() {
        assertEquals(COLUMNS * CELL_SIZE, view.getPreferredSize().width);
        assertEquals(ROWS * CELL_SIZE, view.getPreferredSize().height);
        assertTrue(view.isOpaque());
    }

    private BoardView newView() {
        return new BoardView(ROWS, COLUMNS, CELL_SIZE, (row, col) -> { });
    }

    /** Flat cell indices, as {@code row * COLUMNS + col} is the board's own indexing. */
    private static int[] cells(int... indices) {
        return indices;
    }

    private static Generation generation(int[] aliveIndices, int[] everAliveIndices) {
        boolean[] alive = new boolean[ROWS * COLUMNS];
        boolean[] everAlive = new boolean[ROWS * COLUMNS];
        for (int index : aliveIndices) {
            alive[index] = true;
        }
        for (int index : everAliveIndices) {
            everAlive[index] = true;
        }
        return Generation.adopt(COLUMNS, ROWS, alive, everAlive, 0L);
    }

    /** Paints each generation in turn into one view, returning the final image. */
    private BufferedImage renderSequence(Generation... generations) {
        BoardView view = newView();
        view.setSize(COLUMNS * CELL_SIZE, ROWS * CELL_SIZE);
        BufferedImage image = null;
        for (Generation generation : generations) {
            view.setGeneration(generation);
            image = paint(view);
        }
        return image;
    }

    private static BufferedImage paint(BoardView view) {
        BufferedImage target = new BufferedImage(
                view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            view.paint(g);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static void assertImagesEqual(BufferedImage expected, BufferedImage actual) {
        assertImagesEqual(expected, actual, "");
    }

    private static void assertImagesEqual(BufferedImage expected, BufferedImage actual, String context) {
        assertEquals(expected.getWidth(), actual.getWidth(), "width " + context);
        assertEquals(expected.getHeight(), actual.getHeight(), "height " + context);
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if (expected.getRGB(x, y) != actual.getRGB(x, y)) {
                    fail(String.format(
                            "Pixel (%d, %d) differs %s: expected %06X but was %06X",
                            x, y, context,
                            expected.getRGB(x, y) & 0xFFFFFF, actual.getRGB(x, y) & 0xFFFFFF));
                }
            }
        }
    }

    private void dispatch(int id, int x, int y) {
        view.dispatchEvent(new MouseEvent(
                view, id, System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1));
    }

    /** Reads the center pixel of a cell, clear of the separator. */
    private static int rgb(BufferedImage image, int row, int col) {
        return image.getRGB(col * CELL_SIZE + CELL_SIZE / 2, row * CELL_SIZE + CELL_SIZE / 2) & 0xFFFFFF;
    }
}
