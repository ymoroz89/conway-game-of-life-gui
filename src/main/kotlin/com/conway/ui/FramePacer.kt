package com.conway.ui

/**
 * Rate limiter for the render loop.
 *
 * The loop is driven by the display's frame clock, which ticks at whatever the monitor
 * refreshes at. This decides which of those ticks actually pull a new snapshot, so the
 * repaint rate is a property of the configuration rather than of the hardware the
 * application happens to be running on -- the same role `javax.swing.Timer` played for the
 * Swing build, and what makes render cost comparable between runs.
 *
 * Not thread-safe, and does not need to be: the render loop calls it from the frame
 * callback only.
 *
 * @param targetFps repaints per second to allow, as an upper bound. A frame clock slower
 *                  than this simply delivers fewer.
 */
internal class FramePacer(targetFps: Int) {

    init {
        require(targetFps > 0) { "targetFps must be positive but was $targetFps" }
    }

    private val minIntervalNanos = NANOS_PER_SECOND / targetFps

    private var lastRenderedNanos = 0L

    /**
     * False until the first frame. The frame clock's time base is arbitrary, so there is no
     * "zero" to compare the first tick against; it is always accepted, and the interval is
     * measured from there.
     */
    private var started = false

    /**
     * Whether the frame at [frameNanos] should be rendered. Accepting a frame starts the
     * next interval from it, so a clock that ticks slower than the target neither
     * accumulates a backlog nor drifts.
     */
    fun shouldRender(frameNanos: Long): Boolean {
        if (started && frameNanos - lastRenderedNanos < minIntervalNanos) {
            return false
        }
        started = true
        lastRenderedNanos = frameNanos
        return true
    }

    companion object {
        internal const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
