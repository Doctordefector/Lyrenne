package com.metrolist.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
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
import com.metrolist.music.desktop.db.DatabaseHelper
import com.metrolist.music.desktop.db.Playlist
import com.metrolist.music.desktop.download.DownloadManager
import com.metrolist.music.desktop.media.MediaKeyHandler
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
    var showSongMenu by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showSleepMenu by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    val playlists by DatabaseHelper.getAllPlaylists().collectAsState(initial = emptyList())
    val sleepTimer by player.sleepTimer.collectAsState()

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

                // Song info + context menu
                Box(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable { showSongMenu = true }
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
                    DropdownMenu(
                        expanded = showSongMenu,
                        onDismissRequest = { showSongMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Play Next") },
                            onClick = {
                                player.addToQueueNext(song)
                                showSongMenu = false
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Queue") },
                            onClick = {
                                player.addToQueue(song)
                                showSongMenu = false
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }
                        )
                        if (playlists.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Add to Playlist") },
                                onClick = {
                                    showSongMenu = false
                                    showPlaylistPicker = true
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Start Radio") },
                            onClick = {
                                showSongMenu = false
                                scope.launch { player.startRadio(song) }
                            },
                            leadingIcon = { Icon(Icons.Default.Radio, null) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Download") },
                            onClick = {
                                DownloadManager.queueDownload(song)
                                showSongMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Download, null) }
                        )
                    }
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

                // Playback speed button
                Box {
                    val prefsForSpeed by PreferencesManager.preferences.collectAsState()
                    IconButton(
                        onClick = { showSpeedMenu = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = "Playback speed",
                            tint = if (prefsForSpeed.playbackSpeed != 1f)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showSpeedMenu,
                        onDismissRequest = { showSpeedMenu = false }
                    ) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (speed == 1f) "Normal" else "${speed}x",
                                        color = if (prefsForSpeed.playbackSpeed == speed)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    player.setPlaybackSpeed(speed)
                                    showSpeedMenu = false
                                }
                            )
                        }
                    }
                }

                // Sleep timer button
                Box {
                    IconButton(
                        onClick = { showSleepMenu = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Bedtime,
                            contentDescription = "Sleep timer",
                            tint = if (sleepTimer != null)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showSleepMenu,
                        onDismissRequest = { showSleepMenu = false }
                    ) {
                        if (sleepTimer != null) {
                            val label = sleepTimer?.endsAtMillis?.let { endsAt ->
                                val minLeft = ((endsAt - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                                "Cancel timer ($minLeft min left)"
                            } ?: "Cancel (end of track)"
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    player.cancelSleepTimer()
                                    showSleepMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Close, null) }
                            )
                            HorizontalDivider()
                        }
                        listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
                            DropdownMenuItem(
                                text = { Text("$minutes minutes") },
                                onClick = {
                                    player.startSleepTimer(minutes)
                                    showSleepMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("End of track") },
                            onClick = {
                                player.setSleepEndOfTrack()
                                showSleepMenu = false
                            }
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Volume slider (persisted) with proper mute state
                val prefs by PreferencesManager.preferences.collectAsState()
                val volume = prefs.volume
                val isMuted = prefs.isMuted
                IconButton(
                    onClick = {
                        MediaKeyHandler.toggleMute(player)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (isMuted || volume == 0f) Icons.AutoMirrored.Filled.VolumeOff
                        else if (volume > 0.5f) Icons.AutoMirrored.Filled.VolumeUp
                        else Icons.AutoMirrored.Filled.VolumeDown,
                        contentDescription = "Volume",
                        modifier = Modifier.size(20.dp)
                    )
                }
                Slider(
                    value = volume,
                    onValueChange = {
                        // If user drags slider, unmute
                        if (isMuted) PreferencesManager.setMuted(false)
                        PreferencesManager.setVolume(it)
                        player.setVolume(it)
                    },
                    modifier = Modifier.width(100.dp)
                )
            }
        }
    }

    // Playlist picker dialog
    if (showPlaylistPicker) {
        PlaylistPickerDialog(
            song = song,
            playlists = playlists,
            onDismiss = { showPlaylistPicker = false }
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
