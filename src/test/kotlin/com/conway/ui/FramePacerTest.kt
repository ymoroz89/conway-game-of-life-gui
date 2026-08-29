package com.conway.ui

import com.conway.ui.FramePacer.Companion.NANOS_PER_SECOND
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The render loop's rate limiter, which is what makes `conway.targetFps` mean something
 * again on a frame clock the application does not control.
 */
class FramePacerTest {

    @Test
    @DisplayName("The first frame always renders, whatever the clock's time base")
    fun firstFrameAlwaysRenders() {
        // withFrameNanos hands out an arbitrary origin, so there is no zero to compare the
        // first tick against. Treating an uninitialised "last frame" as 0 would make a clock
        // that starts near zero skip its opening frames, and one that starts high render
        // immediately by luck.
        assertTrue(FramePacer(60).shouldRender(0L))
        assertTrue(FramePacer(60).shouldRender(Long.MAX_VALUE / 2))
    }

    @Test
    @DisplayName("Frames arriving faster than the target are dropped")
    fun fasterFramesAreDropped() {
        // A 120 Hz display against a 60 fps target: every other frame renders.
        val pacer = FramePacer(60)
        val displayInterval = NANOS_PER_SECOND / 120

        var rendered = 0
        for (frame in 0 until 120) {
            if (pacer.shouldRender(frame * displayInterval)) {
                rendered++
            }
        }

        assertEquals(60, rendered, "one second of a 120 Hz clock at a 60 fps target")
    }

    @Test
    @DisplayName("A clock slower than the target renders every frame without a backlog")
    fun slowerClockRendersEveryFrame() {
        // Intervals are measured from the frame actually rendered, not from an ideal
        // schedule, so a slow clock cannot build up credit and then burst.
        val pacer = FramePacer(240)
        val displayInterval = NANOS_PER_SECOND / 30

        for (frame in 0 until 30) {
            assertTrue(pacer.shouldRender(frame * displayInterval), "frame $frame")
        }
    }

    @Test
    @DisplayName("A frame one nanosecond short of the interval is held back")
    fun intervalBoundaryIsExact() {
        val pacer = FramePacer(60)
        val interval = NANOS_PER_SECOND / 60

        assertTrue(pacer.shouldRender(1_000L))
        assertFalse(pacer.shouldRender(1_000L + interval - 1), "one nanosecond early")
        assertTrue(pacer.shouldRender(1_000L + interval), "exactly on the interval")
    }

    @Test
    @DisplayName("A non-positive frame rate is rejected rather than dividing by zero")
    fun rejectsInvalidFrameRate() {
        assertThrows(IllegalArgumentException::class.java) { FramePacer(0) }
        assertThrows(IllegalArgumentException::class.java) { FramePacer(-1) }
    }
}
