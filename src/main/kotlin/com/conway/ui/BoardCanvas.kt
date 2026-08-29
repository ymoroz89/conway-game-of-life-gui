package com.conway.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.conway.model.Generation
import kotlin.math.roundToInt

/**
 * Draws the board within the available space, letterboxed so cells remain square, and
 * maps taps onto cells for the engine's toggle command.
 *
 * Rendering mirrors the Swing-era approach: [BoardRenderer] diff-updates a pixel buffer,
 * which is blitted to the screen with nearest-neighbour filtering so cells stay crisp.
 * The buffer is rendered at the display's physical pixel density, so the blit is 1:1 with
 * the framebuffer rather than an upscale of a logical-size image. The renderer and its
 * backing image are rebuilt only when the board dimensions, the cell size or the display
 * density change, not on every frame.
 *
 * [generation] is a lambda, invoked in the draw phase. The board therefore invalidates
 * this composable's draw and nothing else: a new generation costs one repaint, not a
 * recomposition. That is also why the grid extents are passed separately -- sizing the
 * renderer from the board would mean reading it during composition, which is exactly the
 * subscription this signature avoids.
 */
@Composable
fun BoardCanvas(
    gridWidth: Int,
    gridHeight: Int,
    generation: () -> Generation,
    cellSize: Int,
    onCellPress: (row: Int, col: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Physical pixels per logical pixel: 2.0 on a Retina display, 1.0 elsewhere.
    val deviceScale = LocalDensity.current.density

    val renderer = remember(gridWidth, gridHeight, cellSize, deviceScale) {
        BoardRenderer(gridHeight, gridWidth, cellSize, deviceScale.toDouble())
    }
    val boardImage = remember(renderer) {
        BoardImage(renderer.pixelWidth, renderer.pixelHeight, renderer.bytes)
    }

    val pixelWidth = renderer.pixelWidth
    val pixelHeight = renderer.pixelHeight

    Canvas(
        modifier = modifier
            .background(Theme.background)
            .pointerInput(gridWidth, gridHeight) {
                detectTapGestures { position ->
                    val dest = letterbox(
                        size.width.toFloat(),
                        size.height.toFloat(),
                        pixelWidth,
                        pixelHeight,
                    ) ?: return@detectTapGestures
                    cellAt(dest, position.x, position.y, gridHeight, gridWidth)
                        ?.let { (row, col) -> onCellPress(row, col) }
                }
            },
    ) {
        // Diff-update the buffer in the draw phase, not during composition. The renderer
        // holds the previous generation as state and shares its buffer with the board
        // image; mutating them from a composition -- which Compose may skip, restart or
        // abandon -- would let the two drift apart. The renderer already writes Skia's
        // layout, so there is nothing to convert between here and the blit.
        //
        // Calling [generation] here, rather than capturing a Generation, is what subscribes
        // the draw to the render-loop state: a new board invalidates this draw and nothing
        // else. Capturing would instead tie redraws to the composable being recomposed.
        renderer.render(generation())

        letterbox(size.width, size.height, pixelWidth, pixelHeight)?.let { dest ->
            val image = boardImage.imageBitmap()
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(dest.left.roundToInt(), dest.top.roundToInt()),
                dstSize = IntSize(dest.width.roundToInt(), dest.height.roundToInt()),
                filterQuality = FilterQuality.None,
            )
        }
    }
}

/**
 * The largest rectangle with the board's aspect ratio that fits [destWidth]x[destHeight],
 * centred. Returns null for a degenerate destination so callers can skip the draw.
 *
 * Internal so the letterboxing and tap-mapping arithmetic can be unit tested headlessly.
 */
internal fun letterbox(destWidth: Float, destHeight: Float, pixelWidth: Int, pixelHeight: Int): Rect? {
    if (destWidth <= 0f || destHeight <= 0f || pixelWidth <= 0 || pixelHeight <= 0) return null
    val scale = minOf(destWidth / pixelWidth, destHeight / pixelHeight)
    val width = pixelWidth * scale
    val height = pixelHeight * scale
    return Rect(
        left = (destWidth - width) / 2f,
        top = (destHeight - height) / 2f,
        right = (destWidth + width) / 2f,
        bottom = (destHeight + height) / 2f,
    )
}

/** Maps a pointer position within [dest] onto a board cell, or null when outside it. */
internal fun cellAt(dest: Rect, x: Float, y: Float, rows: Int, columns: Int): Pair<Int, Int>? {
    if (x < dest.left || x >= dest.right || y < dest.top || y >= dest.bottom) return null
    val col = ((x - dest.left) / dest.width * columns).toInt().coerceIn(0, columns - 1)
    val row = ((y - dest.top) / dest.height * rows).toInt().coerceIn(0, rows - 1)
    return row to col
}