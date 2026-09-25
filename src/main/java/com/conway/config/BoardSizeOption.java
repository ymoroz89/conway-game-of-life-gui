package com.conway.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A board size offered in the user interface.
 *
 * <p>{@code cells} is the simulated grid edge in cells; {@code label} is what the control
 * displays. The two differ: the presets simulate 201, 401 and 801 cells but are shown as
 * 200, 400 and 800, because the round number is the useful thing to read and the extra
 * cell is an implementation detail of the grid.
 *
 * <p>{@link #toString()} returns the label so the value can be handed straight to a
 * {@code SpinnerListModel} and render correctly without a custom renderer.
 */
public record BoardSizeOption(int cells, String label) {

    /** Grid edge paired with the number displayed for it. */
    private static final int[][] PRESETS = {
        {201, 200},
        {401, 400},
        {801, 800},
    };

    public BoardSizeOption {
        if (cells <= 0) {
            throw new IllegalArgumentException("cells must be positive but was " + cells);
        }
        Objects.requireNonNull(label, "label");
    }

    /**
     * The sizes the control offers, in ascending order.
     *
     * <p>Always contains {@code configuredCells}. A grid set through
     * {@code -Dconway.gridSize} that is not one of the presets is added rather than
     * silently replaced by one, so the property keeps working; because there is no round
     * number to show for it, it is labeled with its actual size.
     *
     * @throws IllegalArgumentException if {@code configuredCells} is not positive
     */
    public static List<BoardSizeOption> options(int configuredCells) {
        if (configuredCells <= 0) {
            throw new IllegalArgumentException(
                    "configuredCells must be positive but was " + configuredCells);
        }

        List<BoardSizeOption> options = new ArrayList<>(PRESETS.length + 1);
        boolean configuredIsPreset = false;
        for (int[] preset : PRESETS) {
            options.add(new BoardSizeOption(preset[0], String.valueOf(preset[1])));
            configuredIsPreset |= preset[0] == configuredCells;
        }

        if (!configuredIsPreset) {
            options.add(new BoardSizeOption(configuredCells, String.valueOf(configuredCells)));
            options.sort(Comparator.comparingInt(BoardSizeOption::cells));
        }
        return List.copyOf(options);
    }

    /**
     * The option for {@code cells}.
     *
     * @throws IllegalArgumentException if no option has that size
     */
    public static BoardSizeOption find(List<BoardSizeOption> options, int cells) {
        return options.stream()
                .filter(option -> option.cells() == cells)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No option for " + cells + " cells"));
    }

    @Override
    public String toString() {
        return label;
    }
}
