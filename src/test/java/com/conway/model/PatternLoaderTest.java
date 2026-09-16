package com.conway.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    @DisplayName("The bundled gun is parsed once and handed out as the same instance")
    void bundledGunIsCachedAndEqualToAFreshLoad() {
        Pattern first = PatternLoader.bundledGliderGun();
        Pattern second = PatternLoader.bundledGliderGun();

        // The point of the LazyConstant: every caller gets the one parsed instance rather
        // than a fresh read of the resource.
        assertSame(first, second);

        // And it is the same pattern a direct load produces -- caching must not change what
        // the bundled gun is.
        Pattern fresh = PatternLoader.fromResource(PatternLoader.GOSPER_GLIDER_GUN);
        assertNotSame(fresh, first);
        assertEquals(fresh.name(), first.name());
        assertEquals(fresh.width(), first.width());
        assertEquals(fresh.height(), first.height());
        assertEquals(fresh.population(), first.population());
    }
}
