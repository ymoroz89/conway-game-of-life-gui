package com.conway.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PatternLoaderTest {

    @Test
    @DisplayName("The bundled Gosper glider gun loads with its canonical dimensions")
    void loadsGosperGliderGun() {
        Pattern gun = PatternLoader.fromResource(PatternLoader.GOSPER_GLIDER_GUN);

        assertEquals("gosper-glider-gun", gun.name());
        assertEquals(36, gun.width());
        assertEquals(9, gun.height());
        // The Gosper glider gun is defined by exactly 36 live cells.
        assertEquals(36, gun.population());
    }

    @Test
    @DisplayName("A missing resource fails loudly")
    void missingResourceIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PatternLoader.fromResource("/patterns/does-not-exist.cells"));
    }
}
