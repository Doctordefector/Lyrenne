package com.lyrenne.desktop.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.db.Playlist
import com.lyrenne.desktop.download.DownloadManager
import com.lyrenne.desktop.media.MediaKeyHandler
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.RepeatMode
import com.lyrenne.desktop.playback.SleepTimerState
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.sync.YouTubeWrites
import com.lyrenne.desktop.ui.theme.LyrenneTokens
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
    var showMoreMenu by remember { mutableStateOf(false) }
    val playlists by DatabaseHelper.getAllPlaylists().collectAsState(initial = emptyList())
    val sleepTimer by player.sleepTimer.collectAsState()
    val prefs by PreferencesManager.preferences.collectAsState()

    if (state.currentSong == null) {
        return // Don't show if nothing is playing
    }

    val song = state.currentSong!!

    // Liked state comes from the database rather than the queue, so it stays right when the
    // same song is liked from a library list, or unliked by a sync from YouTube.
    val dbSong by remember(song.id) { DatabaseHelper.getSongById(song.id) }
        .collectAsState(initial = null)
    val isLiked = dbSong?.liked == 1L

    fun toggleLike() {
        val nowLiked = !isLiked
        // The playing song is not necessarily in the library yet, so there would be no row to update.
        DatabaseHelper.insertSong(
            id = song.id,
            title = song.title,
            duration = song.duration,
            thumbnailUrl = song.thumbnailUrl,
            albumName = song.album
        )
        DatabaseHelper.updateSongLiked(song.id, nowLiked)
        YouTubeWrites.likeSong(song.id, nowLiked)
        if (nowLiked && dbSong?.isDownloaded != 1L &&
            PreferencesManager.preferences.value.autoDownloadOnLike
        ) {
            DownloadManager.queueDownload(song)
        }
    }

    Surface(
        modifier = modifier.height(LyrenneTokens.playerHeight),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp
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
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            )

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val responsiveLayout = miniPlayerResponsiveLayout(maxWidth)
                val showSecondaryControls = responsiveLayout.showSecondaryControls
                val showExtendedControls = responsiveLayout.showExtendedControls

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Album art
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = "Album art",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(LyrenneTokens.artworkRadius)),
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

                    // Like: the one library action people look for on the player itself.
                    PlayerIconButton(
                        onClick = ::toggleLike,
                        icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isLiked) "Remove from liked songs" else "Add to liked songs",
                        selected = isLiked
                    )

                    if (showExtendedControls) {
                        Spacer(Modifier.width(8.dp))

                        // Time display
                        Text(
                            text = "${formatTime(state.position)} / ${formatTime(state.duration)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.width(12.dp))
                    }

                    // Shuffle button
                    if (showSecondaryControls) {
                        PlayerIconButton(
                            onClick = player::toggleShuffle,
                            icon = Icons.Default.Shuffle,
                            contentDescription = if (state.shuffleEnabled) "Disable shuffle" else "Enable shuffle",
                            selected = state.shuffleEnabled
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
                    if (showSecondaryControls) {
                        PlayerIconButton(
                            onClick = player::toggleRepeat,
                            icon = when (state.repeatMode) {
                                RepeatMode.ONE -> Icons.Default.RepeatOne
                                else -> Icons.Default.Repeat
                            },
                            contentDescription = when (state.repeatMode) {
                                RepeatMode.OFF -> "Enable repeat"
                                RepeatMode.ALL -> "Repeat all enabled"
                                RepeatMode.ONE -> "Repeat one enabled"
                            },
                            selected = state.repeatMode != RepeatMode.OFF
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // Queue button
                    IconButton(
                        onClick = onQueueClick,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Queue",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Lyrics button
                    if (showSecondaryControls) {
                        PlayerIconButton(
                            onClick = onLyricsClick,
                            icon = Icons.Default.MusicNote,
                            contentDescription = if (lyricsActive) "Close lyrics" else "Open lyrics",
                            selected = lyricsActive
                        )
                    }

                    // Playback speed button
                    if (showExtendedControls) {
                        Box {
                            PlayerIconButton(
                                onClick = { showSpeedMenu = true },
                                icon = Icons.Default.Speed,
                                contentDescription = "Playback speed: ${playbackSpeedLabel(prefs.playbackSpeed)}",
                                selected = prefs.playbackSpeed != 1f
                            )
                            PlaybackSpeedMenu(
                                expanded = showSpeedMenu,
                                currentSpeed = prefs.playbackSpeed,
                                onDismissRequest = { showSpeedMenu = false },
                                onSpeedSelected = { speed ->
                                    player.setPlaybackSpeed(speed)
                                    showSpeedMenu = false
                                }
                            )
                        }
                    }

                    // Sleep timer button
                    if (showExtendedControls) {
                        Box {
                            PlayerIconButton(
                                onClick = { showSleepMenu = true },
                                icon = Icons.Default.Bedtime,
                                contentDescription = if (sleepTimer != null) "Sleep timer active" else "Sleep timer",
                                selected = sleepTimer != null
                            )
                            SleepTimerMenu(
                                expanded = showSleepMenu,
                                sleepTimer = sleepTimer,
                                onDismissRequest = { showSleepMenu = false },
                                onCancel = {
                                    player.cancelSleepTimer()
                                    showSleepMenu = false
                                },
                                onMinutesSelected = { minutes ->
                                    player.startSleepTimer(minutes)
                                    showSleepMenu = false
                                },
                                onEndOfTrack = {
                                    player.setSleepEndOfTrack()
                                    showSleepMenu = false
                                }
                            )
                        }
                    }

                    // Keep actions removed by the responsive layout available from one overflow.
                    if (!showExtendedControls) {
                        Box {
                            IconButton(
                                onClick = { showMoreMenu = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.MoreVert, "More playback controls")
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                if (!showSecondaryControls) {
                                    DropdownMenuItem(
                                        text = { Text(if (state.shuffleEnabled) "Disable shuffle" else "Enable shuffle") },
                                        onClick = {
                                            player.toggleShuffle()
                                            showMoreMenu = false
                                        },
                                        leadingIcon = { Icon(Icons.Default.Shuffle, null) },
                                        trailingIcon = {
                                            if (state.shuffleEnabled) Icon(Icons.Default.Check, null)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                when (state.repeatMode) {
                                                    RepeatMode.OFF -> "Enable repeat"
                                                    RepeatMode.ALL -> "Repeat all"
                                                    RepeatMode.ONE -> "Repeat one"
                                                }
                                            )
                                        },
                                        onClick = {
                                            player.toggleRepeat()
                                            showMoreMenu = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (state.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne
                                                else Icons.Default.Repeat,
                                                null
                                            )
                                        },
                                        trailingIcon = {
                                            if (state.repeatMode != RepeatMode.OFF) Icon(Icons.Default.Check, null)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (lyricsActive) "Close lyrics" else "Open lyrics") },
                                        onClick = {
                                            onLyricsClick()
                                            showMoreMenu = false
                                        },
                                        leadingIcon = { Icon(Icons.Default.MusicNote, null) },
                                        trailingIcon = {
                                            if (lyricsActive) Icon(Icons.Default.Check, null)
                                        }
                                    )
                                    HorizontalDivider()
                                }

                                DropdownMenuItem(
                                    text = { Text("Playback speed") },
                                    onClick = {
                                        showMoreMenu = false
                                        showSpeedMenu = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Speed, null) },
                                    trailingIcon = { Text(playbackSpeedLabel(prefs.playbackSpeed)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sleep timer") },
                                    onClick = {
                                        showMoreMenu = false
                                        showSleepMenu = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Bedtime, null) },
                                    trailingIcon = {
                                        if (sleepTimer != null) Icon(Icons.Default.Check, null)
                                    }
                                )
                            }
                            PlaybackSpeedMenu(
                                expanded = showSpeedMenu,
                                currentSpeed = prefs.playbackSpeed,
                                onDismissRequest = { showSpeedMenu = false },
                                onSpeedSelected = { speed ->
                                    player.setPlaybackSpeed(speed)
                                    showSpeedMenu = false
                                }
                            )
                            SleepTimerMenu(
                                expanded = showSleepMenu,
                                sleepTimer = sleepTimer,
                                onDismissRequest = { showSleepMenu = false },
                                onCancel = {
                                    player.cancelSleepTimer()
                                    showSleepMenu = false
                                },
                                onMinutesSelected = { minutes ->
                                    player.startSleepTimer(minutes)
                                    showSleepMenu = false
                                },
                                onEndOfTrack = {
                                    player.setSleepEndOfTrack()
                                    showSleepMenu = false
                                }
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Volume slider (persisted) with proper mute state
                    val volume = prefs.volume
                    val isMuted = prefs.isMuted
                    IconButton(
                        onClick = {
                            MediaKeyHandler.toggleMute(player)
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            if (isMuted || volume == 0f) Icons.AutoMirrored.Filled.VolumeOff
                            else if (volume > 0.5f) Icons.AutoMirrored.Filled.VolumeUp
                            else Icons.AutoMirrored.Filled.VolumeDown,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
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
                        modifier = Modifier.width(responsiveLayout.volumeSliderWidth)
                    )
                }
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

@Composable
private fun PlayerIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    selected: Boolean = false
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun PlaybackSpeedMenu(
    expanded: Boolean,
    currentSpeed: Float,
    onDismissRequest: () -> Unit,
    onSpeedSelected: (Float) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
            DropdownMenuItem(
                text = { Text(playbackSpeedLabel(speed)) },
                onClick = { onSpeedSelected(speed) },
                trailingIcon = {
                    if (currentSpeed == speed) Icon(Icons.Default.Check, null)
                }
            )
        }
    }
}

@Composable
private fun SleepTimerMenu(
    expanded: Boolean,
    sleepTimer: SleepTimerState?,
    onDismissRequest: () -> Unit,
    onCancel: () -> Unit,
    onMinutesSelected: (Int) -> Unit,
    onEndOfTrack: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        if (sleepTimer != null) {
            val label = sleepTimer.endsAtMillis?.let { endsAt ->
                val minLeft = ((endsAt - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                "Cancel timer ($minLeft min left)"
            } ?: "Cancel (end of track)"
            DropdownMenuItem(
                text = { Text(label) },
                onClick = onCancel,
                leadingIcon = { Icon(Icons.Default.Close, null) }
            )
            HorizontalDivider()
        }
        listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
            DropdownMenuItem(
                text = { Text("$minutes minutes") },
                onClick = { onMinutesSelected(minutes) }
            )
        }
        DropdownMenuItem(
            text = { Text("End of track") },
            onClick = onEndOfTrack
        )
    }
}

private fun playbackSpeedLabel(speed: Float): String =
    if (speed == 1f) "Normal" else "${speed}x"

internal data class MiniPlayerResponsiveLayout(
    val showSecondaryControls: Boolean,
    val showExtendedControls: Boolean,
    val volumeSliderWidth: androidx.compose.ui.unit.Dp
)

internal fun miniPlayerResponsiveLayout(width: androidx.compose.ui.unit.Dp): MiniPlayerResponsiveLayout {
    val showSecondaryControls = width >= 840.dp
    val showExtendedControls = width >= 1040.dp
    return MiniPlayerResponsiveLayout(
        showSecondaryControls = showSecondaryControls,
        showExtendedControls = showExtendedControls,
        volumeSliderWidth = when {
            showExtendedControls -> 100.dp
            showSecondaryControls -> 80.dp
            else -> 64.dp
        }
    )
}

/**
 * `m:ss`, or `h:mm:ss` once the track runs past an hour.
 *
 * Without the hours branch a 90 minute mix rendered as "90:00" and a long set as "150:00".
 * Never wrong, just unreadable, and hour-plus uploads are common on YouTube Music.
 */
private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
