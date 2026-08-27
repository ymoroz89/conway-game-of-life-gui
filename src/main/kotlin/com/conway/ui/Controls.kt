package com.conway.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A small tracked-capitals section caption. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text.uppercase(),
        modifier = modifier,
        style = TextStyle(
            color = Theme.textMuted,
            fontSize = 10.sp,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
}

/** A value rendered in a monospaced face so digits do not jitter as they update. */
@Composable
fun ValueText(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier = modifier,
        style = TextStyle(color = Theme.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
    )
}

/** Visual weight of a [FlatButton], encoding how prominent the action is. */
enum class ButtonVariant { PRIMARY, SECONDARY, DANGER }

/**
 * A flat, rounded control hand-painted with the theme. No Material dependency: the shape
 * and colours are drawn here so every platform looks identical.
 */
@Composable
fun FlatButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    variant: ButtonVariant = ButtonVariant.SECONDARY,
    modifier: Modifier = Modifier,
) {
    val background = when {
        !enabled -> Theme.disabled
        variant == ButtonVariant.PRIMARY -> Theme.accent
        variant == ButtonVariant.DANGER -> Theme.danger
        else -> Theme.surfaceRaised
    }
    val foreground = when {
        !enabled -> Theme.textDisabled
        variant == ButtonVariant.PRIMARY -> Theme.onAccent
        else -> Theme.text
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = TextStyle(color = foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** A selectable chip, used for the board-size presets. */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        !enabled -> Theme.disabled
        selected -> Theme.accent
        else -> Theme.surfaceRaised
    }
    val foreground = when {
        !enabled -> Theme.textDisabled
        selected -> Theme.onAccent
        else -> Theme.text
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = foreground,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
        )
    }
}

/** A minus/value/plus stepper, used for the partition count. */
@Composable
fun Stepper(
    value: Int,
    enabled: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        FlatButton("−", enabled, onDecrement, modifier = Modifier.width(40.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            ValueText(value.toString())
        }
        FlatButton("+", enabled, onIncrement, modifier = Modifier.width(40.dp))
    }
}

/** A caption above its value, as used in the status bar. */
@Composable
fun StatusSegment(caption: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Caption(caption)
        ValueText(value)
    }
}

/** A full-width flat button for the sidebar list. */
@Composable
fun SidebarButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.SECONDARY,
) {
    FlatButton(text, enabled, onClick, variant, modifier = modifier.fillMaxWidth())
}