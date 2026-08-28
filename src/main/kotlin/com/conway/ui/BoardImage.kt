package com.conway.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * An off-screen Skia bitmap holding the rendered board, shared between the pixel
 * renderer and the Compose [ImageBitmap] the board canvas draws.
 *
 * Skia's [Bitmap.installPixels] copies the JVM array into native memory at call time
 * rather than aliasing it, so later mutation of [bytes] is invisible until the array is
 * installed again. [imageBitmap] therefore re-uploads the buffer on every call, which
 * is once per rendered frame. The upload is a single native copy of
 * `pixelWidth * pixelHeight * 4` bytes, the same order of work the Swing renderer paid
 * for its `BufferedImage` blit, and far cheaper than repainting cell rectangles.
 */
class BoardImage(val pixelWidth: Int, val pixelHeight: Int) {

    private val rowBytes = pixelWidth * 4
    val bytes = ByteArray(pixelWidth * pixelHeight * 4)

    private val info = ImageInfo(
        pixelWidth, pixelHeight,
        ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB,
    )

    /** Internal so tests can read pixels back out of the Skia storage. */
    internal val bitmap = Bitmap()

    init {
        require(pixelWidth > 0 && pixelHeight > 0)
        upload()
    }

    /**
     * A Compose image showing the current contents of [bytes].
     *
     * Every call re-installs the pixels first. Without this, the bitmap keeps the copy
     * Skia made of the (then all-zero) buffer at construction and the board renders as
     * a black square no matter what the renderer writes.
     */
    fun imageBitmap(): ImageBitmap {
        upload()
        return bitmap.asComposeImageBitmap()
    }

    private fun upload() {
        check(bitmap.installPixels(info, bytes, rowBytes)) {
            "Failed to install board pixel storage ${pixelWidth}x$pixelHeight"
        }
    }
}
