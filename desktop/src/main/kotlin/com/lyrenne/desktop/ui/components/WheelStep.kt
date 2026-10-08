package com.lyrenne.desktop.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/**
 * The mouse wheel over a slider steps its value, the way YouTube and Spotify do it (issue #13).
 * Wheel up raises it, one [step] per notch; a touchpad's fractional deltas move it proportionally.
 *
 * [value] is read when the wheel moves rather than captured at composition, because several notches
 * can arrive before the next frame and each must step from where the last one left off. The event
 * is consumed so the list a slider sits in (Settings, the equalizer) does not scroll as well.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.wheelStep(
    value: () -> Float,
    step: Float,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    onChange: (Float) -> Unit
): Modifier = if (!enabled) this else onPointerEvent(PointerEventType.Scroll) { event ->
    val change = event.changes.firstOrNull() ?: return@onPointerEvent
    val delta = change.scrollDelta.y
    if (delta == 0f) return@onPointerEvent
    change.consume()
    val next = (value() - delta * step).coerceIn(range)
    if (next != value()) onChange(next)
}
