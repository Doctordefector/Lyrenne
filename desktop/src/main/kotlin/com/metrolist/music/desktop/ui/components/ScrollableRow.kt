package com.metrolist.music.desktop.ui.components

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

/** LazyRow with a visible draggable scrollbar underneath. */
@Composable
fun ScrollableRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(12.dp),
    content: LazyListScope.() -> Unit
) {
    val state = rememberLazyListState()
    Box(modifier.fillMaxWidth()) {
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
    }
}
