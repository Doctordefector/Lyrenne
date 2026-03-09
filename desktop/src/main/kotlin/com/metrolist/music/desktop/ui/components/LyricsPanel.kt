package com.metrolist.music.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.desktop.lyrics.LyricsManager
import com.metrolist.music.desktop.lyrics.LyricsState
import com.metrolist.music.desktop.playback.DesktopPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun LyricsPanel(
    player: DesktopPlayer,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playerState by player.state.collectAsState()
    val lyricsState by LyricsManager.state.collectAsState()

    // Fetch lyrics when song changes
    LaunchedEffect(playerState.currentSong?.id) {
        val song = playerState.currentSong ?: return@LaunchedEffect
        val durationSec = if (playerState.duration > 0) (playerState.duration / 1000).toInt() else song.duration
        LyricsManager.fetchLyrics(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            durationSec = durationSec,
            album = song.album
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it }
    ) {
        Surface(
            modifier = modifier.width(350.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Lyrics",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Close", modifier = Modifier.size(20.dp))
                    }
                }

                HorizontalDivider()

                // Content
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    when {
                        lyricsState.isLoading -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                        lyricsState.error != null -> {
                            Text(
                                lyricsState.error!!,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        lyricsState.syncedSentences != null -> {
                            SyncedLyricsView(
                                sentences = lyricsState.syncedSentences!!,
                                positionMs = playerState.position
                            )
                        }
                        lyricsState.lyrics != null -> {
                            PlainLyricsView(lyrics = lyricsState.lyrics!!)
                        }
                        else -> {
                            Text(
                                "No lyrics available",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncedLyricsView(
    sentences: Map<Long, String>,
    positionMs: Long
) {
    val sortedEntries = remember(sentences) {
        sentences.entries.sortedBy { it.key }
    }

    // Find current line index
    val currentIndex = remember(positionMs, sortedEntries) {
        var idx = 0
        for (i in sortedEntries.indices) {
            if (sortedEntries[i].key <= positionMs) {
                idx = i
            } else {
                break
            }
        }
        idx
    }

    val listState = rememberLazyListState()

    // Auto-scroll to current line
    LaunchedEffect(currentIndex) {
        if (currentIndex > 0) {
            listState.animateScrollToItem(
                index = (currentIndex - 1).coerceAtLeast(0)
            )
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sortedEntries.size) { index ->
            val entry = sortedEntries[index]
            val isCurrent = index == currentIndex
            val isPast = index < currentIndex

            if (entry.value.isNotBlank()) {
                Text(
                    text = entry.value,
                    fontSize = if (isCurrent) 18.sp else 16.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isCurrent -> MaterialTheme.colorScheme.primary
                        isPast -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    },
                    lineHeight = 24.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }
        // Padding at bottom for scroll space
        item { Spacer(Modifier.height(200.dp)) }
    }
}

@Composable
private fun PlainLyricsView(lyrics: String) {
    LazyColumn {
        item {
            Text(
                text = lyrics,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 26.sp
            )
        }
        item { Spacer(Modifier.height(200.dp)) }
    }
}
