package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Hover tooltip for an icon-only control: the action, and its shortcut if it has one. Wrap every IconButton in one. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Tip(text: String, content: @Composable () -> Unit) {
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    text,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        },
        delayMillis = 500,
        tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(
            offset = androidx.compose.ui.unit.DpOffset(0.dp, (-32).dp)
        ),
        content = content
    )
}
