package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.RepeatMode
import com.metrolist.music.desktop.playback.SongInfo
import kotlinx.coroutines.launch

@Composable
fun QueueScreen(
    player: DesktopPlayer,
    onDismiss: () -> Unit
) {
    val state by player.state.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Drag-to-reorder state
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var targetIndex by remember { mutableStateOf<Int?>(null) }

    // Build the list of items that are NOT the current song (for "Up Next")
    val upNextItems = remember(state.queue, state.currentIndex) {
        state.queue.mapIndexedNotNull { index, song ->
            if (index != state.currentIndex) index to song else null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Queue",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(Modifier.width(8.dp))
                    Badge {
                        Text("${state.queue.size}")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Shuffle button
                    IconToggleButton(
                        checked = state.shuffleEnabled,
                        onCheckedChange = { player.toggleShuffle() }
                    ) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (state.shuffleEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Repeat button
                    IconButton(onClick = { player.toggleRepeat() }) {
                        Icon(
                            when (state.repeatMode) {
                                RepeatMode.ONE -> Icons.Default.RepeatOne
                                else -> Icons.Default.Repeat
                            },
                            contentDescription = "Repeat",
                            tint = if (state.repeatMode != RepeatMode.OFF)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Clear queue button
                    IconButton(
                        onClick = { player.clearQueue() },
                        enabled = state.queue.size > 1
                    ) {
                        Icon(Icons.Default.ClearAll, "Clear Queue")
                    }
                }
            }

            // Now Playing
            if (state.currentSong != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = state.currentSong?.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Now Playing",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                state.currentSong?.title ?: "",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                state.currentSong?.artist ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            // Up Next header
            if (upNextItems.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Up Next",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Long-press and drag to reorder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Queue list
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(
                    items = upNextItems,
                    key = { _, pair -> "${pair.second.id}_${pair.first}" }
                ) { displayIndex, (queueIndex, song) ->
                    val isDragged = draggedIndex == displayIndex
                    val isTarget = targetIndex == displayIndex

                    QueueItem(
                        song = song,
                        index = queueIndex,
                        isDragged = isDragged,
                        isDropTarget = isTarget,
                        dragOffsetY = if (isDragged) dragOffsetY else 0f,
                        onPlay = {
                            scope.launch {
                                player.playAtIndex(queueIndex)
                            }
                        },
                        onRemove = {
                            player.removeFromQueue(queueIndex)
                        },
                        onDragStart = {
                            draggedIndex = displayIndex
                            dragOffsetY = 0f
                        },
                        onDrag = { deltaY ->
                            dragOffsetY += deltaY
                            // Calculate target position based on drag offset
                            val itemHeight = 72 // approximate item height in dp
                            val draggedItems = (dragOffsetY / itemHeight).toInt()
                            val newTarget = (displayIndex + draggedItems).coerceIn(0, upNextItems.size - 1)
                            targetIndex = if (newTarget != displayIndex) newTarget else null
                        },
                        onDragEnd = {
                            val from = draggedIndex
                            val to = targetIndex
                            if (from != null && to != null && from != to) {
                                val fromQueueIndex = upNextItems[from].first
                                val toQueueIndex = upNextItems[to].first
                                player.moveInQueue(fromQueueIndex, toQueueIndex)
                            }
                            draggedIndex = null
                            dragOffsetY = 0f
                            targetIndex = null
                        }
                    )
                }

                if (upNextItems.isEmpty() && state.queue.size <= 1) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Queue is empty",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Add songs from search or library",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItem(
    song: SongInfo,
    index: Int,
    isDragged: Boolean,
    isDropTarget: Boolean,
    dragOffsetY: Float,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val elevation = if (isDragged) 8.dp else 0.dp
    val bgColor = when {
        isDragged -> MaterialTheme.colorScheme.surfaceContainerHigh
        isDropTarget -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surface
    }

    ListItem(
        headlineContent = {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                song.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Drag handle
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    modifier = Modifier
                        .size(24.dp)
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { onDragStart() },
                                onDrag = { change, offset ->
                                    change.consume()
                                    onDrag(offset.y)
                                },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() }
                            )
                        },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "More options")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Play Now") },
                        onClick = {
                            onPlay()
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from Queue") },
                        onClick = {
                            onRemove()
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null) }
                    )
                }
            }
        },
        modifier = Modifier
            .then(
                if (isDragged) {
                    Modifier
                        .graphicsLayer { translationY = dragOffsetY }
                        .shadow(elevation, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .background(bgColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onPlay),
        colors = ListItemDefaults.colors(containerColor = bgColor)
    )
}
