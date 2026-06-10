package com.metrolist.music.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.desktop.lyrics.LyricsManager
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.settings.LyricsPosition
import com.metrolist.music.desktop.settings.PreferencesManager

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
                    // Show source if available
                    lyricsState.source?.let { source ->
                        Text(
                            source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
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
                            val prefs by PreferencesManager.preferences.collectAsState()
                            SyncedLyricsView(
                                sentences = lyricsState.syncedSentences!!,
                                positionMs = playerState.position,
                                textSize = prefs.lyricsTextSize,
                                textAlign = when (prefs.lyricsPosition) {
                                    LyricsPosition.LEFT -> TextAlign.Start
                                    LyricsPosition.CENTER -> TextAlign.Center
                                    LyricsPosition.RIGHT -> TextAlign.End
                                },
                                onSeek = if (prefs.lyricsClickToSeek) {
                                    { timestampMs -> player.seekTo(timestampMs) }
                                } else null
                            )
                        }
                        lyricsState.lyrics != null -> {
                            val prefs by PreferencesManager.preferences.collectAsState()
                            PlainLyricsView(
                                lyrics = lyricsState.lyrics!!,
                                textSize = prefs.lyricsTextSize,
                                textAlign = when (prefs.lyricsPosition) {
                                    LyricsPosition.LEFT -> TextAlign.Start
                                    LyricsPosition.CENTER -> TextAlign.Center
                                    LyricsPosition.RIGHT -> TextAlign.End
                                }
                            )
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
    positionMs: Long,
    textSize: Float = 16f,
    textAlign: TextAlign = TextAlign.Start,
    onSeek: ((Long) -> Unit)? = null
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
    val accentColor = MaterialTheme.colorScheme.primary
    val dimColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val upcomingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    // Smooth auto-scroll — center the current line with offset
    LaunchedEffect(currentIndex) {
        if (currentIndex > 0) {
            listState.animateScrollToItem(
                index = (currentIndex - 1).coerceAtLeast(0),
                scrollOffset = 0
            )
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Top padding so first lines appear centered
        item { Spacer(Modifier.height(60.dp)) }

        items(sortedEntries.size) { index ->
            val entry = sortedEntries[index]
            val isCurrent = index == currentIndex
            val isPast = index < currentIndex

            if (entry.value.isNotBlank()) {
                LyricsLine(
                    text = entry.value,
                    isCurrent = isCurrent,
                    isPast = isPast,
                    accentColor = accentColor,
                    dimColor = dimColor,
                    upcomingColor = upcomingColor,
                    baseSize = textSize,
                    textAlign = textAlign,
                    onClick = onSeek?.let { seek -> { seek(entry.key) } }
                )
            } else {
                Spacer(Modifier.height(20.dp))
            }
        }
        // Bottom padding for scroll space
        item { Spacer(Modifier.height(300.dp)) }
    }
}

@Composable
private fun LyricsLine(
    text: String,
    isCurrent: Boolean,
    isPast: Boolean,
    accentColor: androidx.compose.ui.graphics.Color,
    dimColor: androidx.compose.ui.graphics.Color,
    upcomingColor: androidx.compose.ui.graphics.Color,
    baseSize: Float = 16f,
    textAlign: TextAlign = TextAlign.Start,
    onClick: (() -> Unit)? = null
) {
    // Animate scale: current line pops up to 1.05x like Android Metrolist
    val scale by animateFloatAsState(
        targetValue = if (isCurrent) 1.05f else 1f,
        animationSpec = tween(durationMillis = 400)
    )

    // Animate opacity: current = full, past = dimmed, upcoming = slightly dimmed
    val alpha by animateFloatAsState(
        targetValue = when {
            isCurrent -> 1f
            isPast -> 0.45f
            else -> 0.7f
        },
        animationSpec = tween(durationMillis = 400)
    )

    // Animate color transition
    val targetColor = when {
        isCurrent -> accentColor
        isPast -> dimColor
        else -> upcomingColor
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 400)
    )

    // Animate font weight via fontSize (Compose doesn't animate FontWeight directly)
    val fontSize by animateFloatAsState(
        targetValue = if (isCurrent) baseSize + 4f else baseSize,
        animationSpec = tween(durationMillis = 300)
    )

    Text(
        text = text,
        fontSize = fontSize.sp,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        color = animatedColor,
        lineHeight = (fontSize + 6).sp,
        textAlign = textAlign,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp)
    )
}

@Composable
private fun PlainLyricsView(
    lyrics: String,
    textSize: Float = 16f,
    textAlign: TextAlign = TextAlign.Start
) {
    LazyColumn {
        item {
            Text(
                text = lyrics,
                fontSize = textSize.sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = (textSize + 10).sp,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item { Spacer(Modifier.height(200.dp)) }
    }
}
