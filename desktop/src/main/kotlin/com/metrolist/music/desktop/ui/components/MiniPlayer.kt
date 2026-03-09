package com.metrolist.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.RepeatMode
import com.metrolist.music.desktop.settings.PreferencesManager
import kotlinx.coroutines.launch

@Composable
fun MiniPlayer(
    player: DesktopPlayer,
    modifier: Modifier = Modifier,
    onQueueClick: () -> Unit = {},
    onLyricsClick: () -> Unit = {},
    lyricsActive: Boolean = false
) {
    val state by player.state.collectAsState()
    val scope = rememberCoroutineScope()

    if (state.currentSong == null) {
        return // Don't show if nothing is playing
    }

    val song = state.currentSong!!

    Surface(
        modifier = modifier.height(76.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp
    ) {
        Column {
            // Seekable progress bar
            Slider(
                value = if (state.duration > 0) state.position.toFloat() / state.duration else 0f,
                onValueChange = { fraction ->
                    val seekPos = (fraction * state.duration).toLong()
                    player.seekTo(seekPos)
                },
                modifier = Modifier.fillMaxWidth().height(12.dp).padding(horizontal = 0.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album art
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = "Album art",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )

                Spacer(Modifier.width(12.dp))

                // Song info
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Time display
                Text(
                    text = "${formatTime(state.position)} / ${formatTime(state.duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.width(16.dp))

                // Shuffle button
                IconButton(
                    onClick = { player.toggleShuffle() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (state.shuffleEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Controls
                IconButton(
                    onClick = { scope.launch { player.playPrevious() } }
                ) {
                    Icon(Icons.Default.SkipPrevious, "Previous")
                }

                FilledIconButton(
                    onClick = { player.togglePlayPause() }
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play"
                    )
                }

                IconButton(
                    onClick = { scope.launch { player.playNext() } }
                ) {
                    Icon(Icons.Default.SkipNext, "Next")
                }

                // Repeat button
                IconButton(
                    onClick = { player.toggleRepeat() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        when (state.repeatMode) {
                            RepeatMode.ONE -> Icons.Default.RepeatOne
                            else -> Icons.Default.Repeat
                        },
                        contentDescription = "Repeat",
                        tint = if (state.repeatMode != RepeatMode.OFF)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Queue button
                IconButton(
                    onClick = onQueueClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Queue",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Lyrics button
                IconButton(
                    onClick = onLyricsClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = "Lyrics",
                        tint = if (lyricsActive)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Volume slider (persisted)
                val prefs by PreferencesManager.preferences.collectAsState()
                val volume = prefs.volume
                IconButton(
                    onClick = {
                        // Click to mute/unmute
                        val newVol = if (volume > 0f) 0f else 1f
                        PreferencesManager.setVolume(newVol)
                        player.setVolume(newVol)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (volume > 0.5f) Icons.AutoMirrored.Filled.VolumeUp
                        else if (volume > 0f) Icons.AutoMirrored.Filled.VolumeDown
                        else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = "Volume",
                        modifier = Modifier.size(20.dp)
                    )
                }
                Slider(
                    value = volume,
                    onValueChange = {
                        PreferencesManager.setVolume(it)
                        player.setVolume(it)
                    },
                    modifier = Modifier.width(100.dp)
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
