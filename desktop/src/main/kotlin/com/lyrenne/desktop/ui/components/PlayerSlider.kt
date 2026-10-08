package com.lyrenne.desktop.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

private val SLOT = 14.dp

/**
 * The player's seek and volume bars: a 4 dp track and a small round thumb that grows while
 * hovered or dragged.
 *
 * The inactive track is `outline`, not a surface tone. Surface containers sit at about 1.4:1
 * against the player bar, which read as two dots with nothing between them; `outline` measures
 * 6:1 in both schemes, over the 3:1 WCAG 1.4.11 asks of non-text UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val thumbSize by animateDpAsState(if (hovered || dragged || pressed) 14.dp else 10.dp)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outline
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.outline,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.hoverable(interaction),
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interaction,
        // The visible thumb is drawn by the track, in the same Canvas as the line, so the two
        // share one centre line by construction. Material's own Track ignored the 4 dp height
        // and placed its thumb off the track's centre, so the circle rode above the line.
        // The real thumb slot stays, invisible, because Slider insets the track by its width.
        thumb = { Spacer(Modifier.size(SLOT)) },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
            Canvas(Modifier.fillMaxWidth().height(SLOT)) {
                val y = size.height / 2
                val x = size.width * fraction
                val stroke = 4.dp.toPx()
                drawLine(inactive, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                drawLine(active, Offset(0f, y), Offset(x, y), stroke, StrokeCap.Round)
                drawCircle(active, thumbSize.toPx() / 2, Offset(x, y))
            }
        },
    )
}
