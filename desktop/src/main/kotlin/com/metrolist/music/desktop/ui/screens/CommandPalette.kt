package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import coil3.compose.AsyncImage
import com.metrolist.music.desktop.db.DatabaseHelper
import com.metrolist.music.desktop.media.MediaKeyHandler
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class PaletteAction(val label: String, val icon: ImageVector, val category: String) {
    // Navigation
    data object GoHome : PaletteAction("Go to Home", Icons.Default.Home, "Navigate")
    data object GoSearch : PaletteAction("Go to Search", Icons.Default.Search, "Navigate")
    data object GoLibrary : PaletteAction("Go to Library", Icons.Default.LibraryMusic, "Navigate")
    data object GoSettings : PaletteAction("Go to Settings", Icons.Default.Settings, "Navigate")
    data object GoEqualizer : PaletteAction("Open Equalizer", Icons.Default.Equalizer, "Navigate")
    data object GoStats : PaletteAction("View Listening Stats", Icons.Default.BarChart, "Navigate")
    data object GoQueue : PaletteAction("Open Queue", Icons.Default.QueueMusic, "Navigate")
    data object GoLyrics : PaletteAction("Toggle Lyrics", Icons.Default.Lyrics, "Navigate")

    // Playback
    data object PlayPause : PaletteAction("Play / Pause", Icons.Default.PlayArrow, "Playback")
    data object NextTrack : PaletteAction("Next Track", Icons.Default.SkipNext, "Playback")
    data object PrevTrack : PaletteAction("Previous Track", Icons.Default.SkipPrevious, "Playback")
    data object ToggleShuffle : PaletteAction("Toggle Shuffle", Icons.Default.Shuffle, "Playback")
    data object ToggleRepeat : PaletteAction("Toggle Repeat", Icons.Default.Repeat, "Playback")

    // Songs from library
    data class PlaySong(val song: SongInfo) : PaletteAction("Play: ${song.title}", Icons.Default.MusicNote, "Songs")
}

@Composable
fun CommandPalette(
    visible: Boolean,
    player: DesktopPlayer,
    onDismiss: () -> Unit,
    onNavigate: (PaletteAction) -> Unit
) {
    if (!visible) return

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PaletteAction>>(emptyList()) }
    var selectedIndex by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val allActions = remember {
        listOf(
            PaletteAction.GoHome,
            PaletteAction.GoSearch,
            PaletteAction.GoLibrary,
            PaletteAction.GoSettings,
            PaletteAction.GoEqualizer,
            PaletteAction.GoStats,
            PaletteAction.GoQueue,
            PaletteAction.GoLyrics,
            PaletteAction.PlayPause,
            PaletteAction.NextTrack,
            PaletteAction.PrevTrack,
            PaletteAction.ToggleShuffle,
            PaletteAction.ToggleRepeat,
        )
    }

    // Search library songs when query is long enough
    LaunchedEffect(query) {
        if (query.length >= 2) {
            val songResults = withContext(Dispatchers.IO) {
                try {
                    DatabaseHelper.searchSongsWithArtists(query).take(8).map { row ->
                        PaletteAction.PlaySong(
                            SongInfo(
                                id = row.id,
                                title = row.title,
                                artist = row.artistNames ?: "Unknown",
                                thumbnailUrl = row.thumbnailUrl,
                                durationMs = (row.duration * 1000).toLong(),
                                album = row.albumName,
                                duration = row.duration.toInt()
                            )
                        )
                    }
                } catch (_: Exception) { emptyList() }
            }

            val filteredActions = if (query.isBlank()) {
                allActions
            } else {
                allActions.filter { it.label.contains(query, ignoreCase = true) }
            }
            results = filteredActions + songResults
            selectedIndex = 0
        } else {
            results = if (query.isBlank()) allActions else allActions.filter { it.label.contains(query, ignoreCase = true) }
            selectedIndex = 0
        }
    }

    // Track text field focus for MediaKeyHandler
    DisposableEffect(Unit) {
        MediaKeyHandler.focusedTextFieldCount.incrementAndGet()
        onDispose { MediaKeyHandler.focusedTextFieldCount.decrementAndGet() }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Full-screen dimmed backdrop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopCenter
    ) {
        // Palette card
        Card(
            modifier = Modifier
                .padding(top = 80.dp)
                .width(550.dp)
                .clickable(enabled = false, onClick = {}) // prevent backdrop click
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.Escape -> { onDismiss(); true }
                            Key.DirectionDown -> {
                                selectedIndex = (selectedIndex + 1).coerceAtMost(results.size - 1)
                                true
                            }
                            Key.DirectionUp -> {
                                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter -> {
                                if (selectedIndex in results.indices) {
                                    executeAction(results[selectedIndex], player, onNavigate, onDismiss)
                                }
                                true
                            }
                            else -> false
                        }
                    } else false
                },
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column {
                // Search field
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Type a command or search songs...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .focusRequester(focusRequester),
                    leadingIcon = { Icon(Icons.Default.Search, "Search") }
                )

                HorizontalDivider()

                // Results list
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    itemsIndexed(results) { index, action ->
                        val isSelected = index == selectedIndex
                        ListItem(
                            headlineContent = {
                                Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(action.category, style = MaterialTheme.typography.bodySmall)
                            },
                            leadingContent = {
                                if (action is PaletteAction.PlaySong && action.song.thumbnailUrl != null) {
                                    AsyncImage(
                                        model = action.song.thumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(action.icon, contentDescription = null)
                                }
                            },
                            modifier = Modifier
                                .clickable {
                                    executeAction(action, player, onNavigate, onDismiss)
                                }
                                .then(
                                    if (isSelected) Modifier.background(
                                        MaterialTheme.colorScheme.surfaceVariant
                                    ) else Modifier
                                )
                        )
                    }

                    if (results.isEmpty()) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No results",
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

private fun executeAction(
    action: PaletteAction,
    player: DesktopPlayer,
    onNavigate: (PaletteAction) -> Unit,
    onDismiss: () -> Unit
) {
    when (action) {
        is PaletteAction.PlayPause -> player.togglePlayPause()
        is PaletteAction.NextTrack -> { /* handled via onNavigate */ }
        is PaletteAction.PrevTrack -> { /* handled via onNavigate */ }
        is PaletteAction.ToggleShuffle -> player.toggleShuffle()
        is PaletteAction.ToggleRepeat -> player.toggleRepeat()
        else -> {}
    }
    onNavigate(action)
    onDismiss()
}
