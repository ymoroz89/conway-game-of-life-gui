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
 * The backing [ByteArray] is installed once via [org.jetbrains.skia.Bitmap.installPixels]
 * and then mutated in place by [BoardRenderer]'s byte conversion, so no per-frame pixel
 * copy happens. A fresh [ImageBitmap] wrapper is produced per paint because Skia images
 * alias the bitmap storage and must not outlive writes.
 */
class BoardImage(pixelWidth: Int, pixelHeight: Int) {

    private val rowBytes = pixelWidth * 4
    val bytes = ByteArray(pixelWidth * pixelHeight * 4)

    private val info = ImageInfo(
        pixelWidth, pixelHeight,
        ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB,
    )
    private val bitmap = Bitmap()

    init {
        require(pixelWidth > 0 && pixelHeight > 0)
        check(bitmap.installPixels(info, bytes, rowBytes)) {
            "Failed to install board pixel storage ${pixelWidth}x$pixelHeight"
        }
    }

    /** A Compose image aliasing the current contents of [bytes]. Each draw call re-reads
     *  the bitmap storage, so mutations made by the renderer between frames are visible. */
    fun imageBitmap(): ImageBitmap = bitmap.asComposeImageBitmap()
}