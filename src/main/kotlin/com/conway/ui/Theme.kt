package com.conway.ui

import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for the application's colours.
 *
 * Board colours are held as packed ARGB `Int`s because [BoardRenderer] unpacks them
 * straight into the RGBA pixel buffer behind the board image; the Compose [Color] forms
 * used by the surrounding chrome are derived from the same values, so the board and the
 * window can never drift apart.
 */
object Theme {
    // Packed ARGB (opaque) values for the pixel renderer.
    /** A live cell. The brightest thing on screen. */
    const val BOARD_ALIVE: Int = 0xFF5BE9B9.toInt()
    /** A cell that has died. Recedes, but still traces where the simulation has been. */
    const val BOARD_TRAIL: Int = 0xFF7A3B5E.toInt()
    /** Never populated. */
    const val BOARD_EMPTY: Int = 0xFF11141A.toInt()
    /** Separator between cells. Barely distinct from empty, so it reads as texture. */
    const val BOARD_GRID: Int = 0xFF1A1E26.toInt()

    val background = Color(0xFF16191F)
    val surface = Color(0xFF1E222A)
    val surfaceRaised = Color(0xFF272C36)
    val border = Color(0xFF2C323C)
    val text = Color(0xFFE4E8F0)
    val textMuted = Color(0xFF8A94A6)
    val accent = Color(BOARD_ALIVE)
    val onAccent = Color(0xFF0B2A22)
    val danger = Color(0xFFE05561)

    /** Fill for a disabled control. Deliberately lighter than [surface]. */
    val disabled = Color(0xFF262B34)
    val textDisabled = Color(0xFF5F6978)
}