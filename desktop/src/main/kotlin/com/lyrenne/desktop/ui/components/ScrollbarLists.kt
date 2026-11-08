package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The scrollbar look shared by every list that has one. Compose Desktop's default style is close
 * to invisible, so the thumb gets explicit colours to be something people find and drag.
 */
@Composable
fun lyrenneScrollbarStyle(): ScrollbarStyle = ScrollbarStyle(
    minimalHeight = 32.dp,
    thickness = 8.dp,
    shape = RoundedCornerShape(4.dp),
    hoverDurationMillis = 300,
    unhoverColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    hoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
)

/** Room kept clear on the right so the scrollbar never sits on top of a row's ⋮ button. */
private val ScrollbarGutter = 12.dp

/**
 * A LazyColumn with a draggable scrollbar on its right edge (issue #18). With a library of a few
 * thousand songs the wheel alone takes a minute to reach the bottom; dragging the thumb is one move.
 */
@Composable
fun ScrollbarLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit
) {
    Box(modifier) {
        LazyColumn(
            state = state,
            verticalArrangement = verticalArrangement,
            modifier = Modifier.fillMaxSize().padding(end = ScrollbarGutter),
            content = content
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(ScrollbarGutter - 2.dp),
            style = lyrenneScrollbarStyle()
        )
    }
}

/** [ScrollbarLazyColumn] for a grid. */
@Composable
fun ScrollbarLazyGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyGridScope.() -> Unit
) {
    Box(modifier) {
        LazyVerticalGrid(
            columns = columns,
            state = state,
            horizontalArrangement = horizontalArrangement,
            verticalArrangement = verticalArrangement,
            modifier = Modifier.fillMaxSize().padding(end = ScrollbarGutter),
            content = content
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(ScrollbarGutter - 2.dp),
            style = lyrenneScrollbarStyle()
        )
    }
}
