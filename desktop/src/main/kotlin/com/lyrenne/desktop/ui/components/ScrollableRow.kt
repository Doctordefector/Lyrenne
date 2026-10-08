package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * LazyRow with a visible draggable scrollbar underneath, and ‹ › buttons at the ends while the
 * pointer is over it. Mouse users without a horizontal wheel otherwise had nothing telling them
 * the half-cut last card meant "more this way", and nothing to press if they guessed.
 */
@Composable
fun ScrollableRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(12.dp),
    content: LazyListScope.() -> Unit
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    BoxWithConstraints(modifier.fillMaxWidth().hoverable(hover)) {
        // One viewport per click, less a card's worth so the last visible card stays in view.
        val page = with(LocalDensity.current) { (maxWidth - 64.dp).toPx().coerceAtLeast(100f) }
        LazyRow(
            state = state,
            horizontalArrangement = horizontalArrangement,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        ) {
            content()
        }
        // Default LocalScrollbarStyle in Compose Desktop is near-invisible;
        // explicit colors so the thumb is actually click/drag-able.
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(10.dp),
            style = ScrollbarStyle(
                minimalHeight = 8.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 300,
                unhoverColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                hoverColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
            )
        )
        if (hovered && state.canScrollBackward) {
            ShelfArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Scroll left", Modifier.align(Alignment.CenterStart)) {
                scope.launch { state.animateScrollBy(-page) }
            }
        }
        if (hovered && state.canScrollForward) {
            ShelfArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Scroll right", Modifier.align(Alignment.CenterEnd)) {
                scope.launch { state.animateScrollBy(page) }
            }
        }
    }
}

@Composable
private fun ShelfArrow(icon: ImageVector, description: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.padding(horizontal = 4.dp).size(40.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f)
        )
    ) {
        Icon(icon, contentDescription = description)
    }
}
