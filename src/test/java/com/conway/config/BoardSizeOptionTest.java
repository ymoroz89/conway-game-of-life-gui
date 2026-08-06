package com.conway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoardSizeOptionTest {

    @ParameterizedTest(name = "a {0}-cell grid is shown as {1}")
    @CsvSource({
        "201, 200",
        "401, 400",
        "801, 800",
    })
    @DisplayName("Presets simulate an odd grid but display the round number")
    void presetsDisplayRoundNumbers(int cells, String label) {
        BoardSizeOption option = BoardSizeOption.find(BoardSizeOption.options(201), cells);

        assertEquals(cells, option.cells(), "the simulated grid");
        assertEquals(label, option.label(), "what the control shows");
        assertEquals(label, option.toString(), "the spinner renders toString()");
    }

    @Test
    @DisplayName("The three presets are offered in ascending order")
    void offersThePresetsInOrder() {
        List<BoardSizeOption> options = BoardSizeOption.options(201);

        assertEquals(3, options.size());
        assertEquals(List.of(201, 401, 801), options.stream().map(BoardSizeOption::cells).toList());
        assertEquals(List.of("200", "400", "800"), options.stream().map(BoardSizeOption::label).toList());
    }

    @Test
    @DisplayName("A grid size set through the system property is offered alongside the presets")
    void keepsAConfiguredSizeThatIsNotAPreset() {
        // -Dconway.gridSize=1601 must keep working; silently snapping it to a preset would
        // make the property look broken.
        List<BoardSizeOption> options = BoardSizeOption.options(1601);

        assertEquals(4, options.size());
        assertEquals(List.of(201, 401, 801, 1601),
                options.stream().map(BoardSizeOption::cells).toList());
        assertEquals("1601", BoardSizeOption.find(options, 1601).label(),
                "a size with no round equivalent is labelled with its actual value");
    }

    @Test
    @DisplayName("A configured size below the presets sorts to the front")
    void sortsASmallConfiguredSizeFirst() {
        List<BoardSizeOption> options = BoardSizeOption.options(51);

        assertEquals(51, options.get(0).cells());
        assertEquals(List.of(51, 201, 401, 801),
                options.stream().map(BoardSizeOption::cells).toList());
    }

    @Test
    @DisplayName("A configured size equal to a preset is not duplicated")
    void doesNotDuplicateAPreset() {
        List<BoardSizeOption> options = BoardSizeOption.options(401);

        assertEquals(3, options.size());
        assertEquals(1, options.stream().filter(o -> o.cells() == 401).count());
    }

    @Test
    @DisplayName("The returned list is immutable")
    void listIsImmutable() {
        List<BoardSizeOption> options = BoardSizeOption.options(201);

        assertThrows(UnsupportedOperationException.class,
                () -> options.add(new BoardSizeOption(9, "9")));
    }

    @Test
    @DisplayName("Every offered size can be looked up; unknown sizes are rejected")
    void findLocatesOfferedSizesOnly() {
        List<BoardSizeOption> options = BoardSizeOption.options(201);

        for (BoardSizeOption option : options) {
            assertTrue(BoardSizeOption.find(options, option.cells()) == option);
        }
        assertThrows(IllegalArgumentException.class, () -> BoardSizeOption.find(options, 12345));
    }

    @Test
    @DisplayName("Non-positive sizes are rejected")
    void rejectsInvalidSizes() {
        assertThrows(IllegalArgumentException.class, () -> BoardSizeOption.options(0));
        assertThrows(IllegalArgumentException.class, () -> BoardSizeOption.options(-1));
        assertThrows(IllegalArgumentException.class, () -> new BoardSizeOption(0, "0"));
        assertThrows(NullPointerException.class, () -> new BoardSizeOption(201, null));
    }
}
