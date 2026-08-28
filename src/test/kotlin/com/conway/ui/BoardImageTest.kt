package com.conway.ui

import com.conway.model.Generation
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression tests for the Skia interop that puts the renderer's pixel buffer on screen.
 *
 * [org.jetbrains.skia.Bitmap.installPixels] copies the JVM byte array into native memory
 * at call time instead of aliasing it. A buffer installed once, at construction, shows
 * only the all-zero pixels that were there then, so the board rendered as a black square
 * no matter what the renderer wrote. These tests pin the contract that any change made
 * to [BoardImage.bytes] becomes visible through the next [BoardImage.imageBitmap] call.
 */
class BoardImageTest {

    @Test
    @DisplayName("Pixels written after construction are visible on the next imageBitmap call")
    fun mutationsAfterConstructionAreVisible() {
        val image = BoardImage(2, 2)
        val installed = requireNotNull(image.bitmap.readPixels())
        assertArrayEquals(ByteArray(image.bytes.size), installed,
            "A fresh board image should start empty")

        // Simulate the render loop: the renderer mutates the shared buffer, then the
        // canvas asks for the image to draw this frame.
        image.bytes.fill(0x80.toByte())
        image.imageBitmap()

        val readBack = requireNotNull(image.bitmap.readPixels())
        assertNotEquals(installed.toList(), readBack.toList(),
            "The bitmap still shows the construction-time copy")
        assertArrayEquals(image.bytes, readBack,
            "Mutations to the byte buffer must be uploaded to Skia")
    }

    @Test
    @DisplayName("The full render pipeline produces the theme colours in Skia storage")
    fun renderedBoardReachesSkiaStorage() {
        val renderer = BoardRenderer(2, 2, 4)
        val image = BoardImage(renderer.pixelWidth, renderer.pixelHeight)

        // (0, 0) alive, (1, 1) died here, everything else never populated.
        val alive = BooleanArray(4)
        val everAlive = BooleanArray(4)
        alive[0] = true
        everAlive[0] = true
        everAlive[3] = true
        val generation = Generation.adopt(2, 2, alive, everAlive, 0L)

        renderer.render(generation)
            .toRgbaPremulBytes(renderer.pixelWidth, renderer.pixelHeight, image.bytes)
        image.imageBitmap()

        val readBack = requireNotNull(image.bitmap.readPixels())
        val stride = renderer.pixelWidth * 4
        assertArrayEquals(rgba(Theme.BOARD_ALIVE), pixelAt(readBack, 0, 0, stride),
            "Alive cell (0, 0), first pixel")
        assertArrayEquals(rgba(Theme.BOARD_EMPTY), pixelAt(readBack, 4, 0, stride),
            "Never-populated cell (0, 1), first pixel")
        assertArrayEquals(rgba(Theme.BOARD_TRAIL), pixelAt(readBack, 4, 4, stride),
            "Died-here cell (1, 1), first pixel")
    }

    /** First four bytes of the pixel at (x, y) as stored in the bitmap. */
    private fun pixelAt(pixels: ByteArray, x: Int, y: Int, stride: Int): ByteArray {
        val offset = y * stride + x * 4
        return pixels.sliceArray(offset until offset + 4)
    }

    /** The RGBA byte order [toRgbaPremulBytes] writes for an opaque packed-ARGB colour. */
    private fun rgba(argb: Int): ByteArray = byteArrayOf(
        (argb shr 16 and 0xFF).toByte(),
        (argb shr 8 and 0xFF).toByte(),
        (argb and 0xFF).toByte(),
        255.toByte(),
    )
}
