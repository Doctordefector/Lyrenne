package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
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
import com.lyrenne.desktop.ui.theme.LocalLyrenneExtras
import com.lyrenne.desktop.ui.theme.LyrenneTokens
import kotlinx.coroutines.launch

@Composable
fun MiniPlayer(
    player: DesktopPlayer,
    modifier: Modifier = Modifier,
    onQueueClick: () -> Unit = {},
    onLyricsClick: () -> Unit = {},
    lyricsActive: Boolean = false,
    queueActive: Boolean = false,
    artTint: State<Color> = remember { mutableStateOf(Color.Transparent) }
) {
    // Position is left out: it ticks five times a second, and only the seek bar and the time
    // readout draw it. They collect it themselves, so the rest of the bar stays still.
    val state by player.select { it.copy(position = 0L) }
    val scope = rememberCoroutineScope()
    var showSleepMenu by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    val sleepTimer by player.sleepTimer.collectAsState()
    val prefs by PreferencesManager.preferences.collectAsState()
    val songMenu = rememberMenuAnchor()

    if (state.currentSong == null) {
        return // Don't show if nothing is playing
    }

    val song = state.currentSong!!

    // Liked state comes from the database rather than the queue, so it stays right when the
    // same song is liked from a library list, or unliked by a sync from YouTube.
    val dbSong by remember(song.id) { DatabaseHelper.getSongById(song.id) }
        .collectAsState(initial = null)
    val isLiked = dbSong?.liked == 1L

    // A floating dock rather than an edge-to-edge bar: inset from the window, rounded, and washed
    // with the cover's colour. The wash is read in the draw phase, so a track change only redraws.
    val dockShape = RoundedCornerShape(LyrenneTokens.dockRadius)
    val dockColor = MaterialTheme.colorScheme.surfaceContainer
    val washAlpha = LocalLyrenneExtras.current.artWashAlpha
    BoxWithConstraints(
        modifier = modifier
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
            .height(LyrenneTokens.playerHeight)
            .clip(dockShape)
            .drawBehind {
                drawRect(dockColor)
                val tint = artTint.value
                if (tint.alpha > 0f) drawRect(tint.copy(alpha = tint.alpha * washAlpha))
            }
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, dockShape)
    ) {
        val layout = dockLayout(maxWidth)
        // Three zones, like every desktop player: the track on the left, transport and seek in a
        // fixed centre column, volume and extras on the right. The side zones share what is left
        // equally, so the play button sits at the dock's centre at every width. Track info used
        // to take all the slack, which pushed transport right and moved it on every resize.
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- Left: art, title/artist (menu on click or right-click), like
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = squareCropFriendly(song.thumbnailUrl),
                    contentDescription = "Album art",
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f).contextMenu(songMenu)) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .clickable { songMenu.open = true }.padding(vertical = 2.dp)
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
                    SongMenu(songMenu, song, player)
                }
                // Like: the one library action people look for on the player itself.
                PlayerIconButton(
                    onClick = { setLiked(song, !isLiked, dbSong?.isDownloaded == 1L) },
                    icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isLiked) "Remove from liked songs" else "Add to liked songs",
                    selected = isLiked
                )
            }

            Spacer(Modifier.width(12.dp))

            // ---- Centre: transport over the seek bar, times at both ends
            Column(Modifier.width(layout.centerWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PlayerIconButton(
                        onClick = player::toggleShuffle,
                        icon = Icons.Default.Shuffle,
                        contentDescription = if (state.shuffleEnabled) "Disable shuffle" else "Enable shuffle",
                        selected = state.shuffleEnabled,
                        shortcut = "Ctrl+S"
                    )
                    Tip("Previous (Ctrl+Left)") {
                        IconButton(onClick = { scope.launch { player.playPrevious() } }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.SkipPrevious, "Previous")
                        }
                    }
                    Tip(if (state.isPlaying) "Pause (Space)" else "Play (Space)") {
                        FilledIconButton(
                            onClick = { player.togglePlayPause() },
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape
                        ) {
                            Icon(
                                if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (state.isPlaying) "Pause" else "Play"
                            )
                        }
                    }
                    Tip("Next (Ctrl+Right)") {
                        IconButton(onClick = { scope.launch { player.playNext() } }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.SkipNext, "Next")
                        }
                    }
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
                        selected = state.repeatMode != RepeatMode.OFF,
                        shortcut = "Ctrl+R"
                    )
                }
                SeekRow(player, state.duration)
            }

            Spacer(Modifier.width(12.dp))

            // ---- Right: lyrics, queue, overflow, volume
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerIconButton(
                    onClick = onLyricsClick,
                    icon = Icons.Outlined.Lyrics,
                    contentDescription = if (lyricsActive) "Close lyrics" else "Open lyrics",
                    selected = lyricsActive,
                    shortcut = "Ctrl+L"
                )
                PlayerIconButton(
                    onClick = onQueueClick,
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = if (queueActive) "Close queue" else "Open queue",
                    selected = queueActive,
                    shortcut = "Ctrl+Q"
                )

                // Speed and the sleep timer are rare, so they live in the overflow at every width.
                Box {
                    Tip("Speed and sleep timer") {
                        IconButton(onClick = { showMoreMenu = true }, modifier = Modifier.size(40.dp)) {
                            // Tinted while a speed or sleep timer is set, so that state stays visible.
                            Icon(
                                Icons.Default.MoreVert, "Speed and sleep timer",
                                tint = if (sleepTimer != null || prefs.playbackSpeed != 1f)
                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Playback speed") },
                            onClick = { showMoreMenu = false; showSpeedMenu = true },
                            leadingIcon = { Icon(Icons.Default.Speed, null) },
                            trailingIcon = { Text(playbackSpeedLabel(prefs.playbackSpeed)) }
                        )
                        DropdownMenuItem(
                            text = { Text("Sleep timer") },
                            onClick = { showMoreMenu = false; showSleepMenu = true },
                            leadingIcon = { Icon(Icons.Outlined.Timer, null) },
                            trailingIcon = { if (sleepTimer != null) Icon(Icons.Default.Check, null) }
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

                // Volume slider (persisted) with proper mute state
                val volume = prefs.volume
                val isMuted = prefs.isMuted
                val onVolume: (Float) -> Unit = {
                    // Moving the slider unmutes
                    if (PreferencesManager.preferences.value.isMuted) PreferencesManager.setMuted(false)
                    PreferencesManager.setVolume(it)
                    player.setVolume(it)
                }
                Tip(if (isMuted) "Unmute (M)" else "Mute (M)") {
                    IconButton(
                        onClick = { MediaKeyHandler.toggleMute(player) },
                        // The wheel works on the icon too, which is all there is on a narrow dock.
                        modifier = Modifier.size(40.dp)
                            .wheelStep({ PreferencesManager.preferences.value.volume }, 0.05f, onChange = onVolume)
                    ) {
                        Icon(
                            if (isMuted || volume == 0f) Icons.AutoMirrored.Filled.VolumeOff
                            else if (volume > 0.5f) Icons.AutoMirrored.Filled.VolumeUp
                            else Icons.AutoMirrored.Filled.VolumeDown,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (layout.volumeSliderWidth > 0.dp) {
                    PlayerSlider(
                        value = volume,
                        // Dragging fires dozens of times a second: keep it in memory, write once on release.
                        onValueChange = {
                            if (PreferencesManager.preferences.value.isMuted) PreferencesManager.setMuted(false)
                            PreferencesManager.setVolume(it, persist = false)
                            player.setVolume(it)
                        },
                        onValueChangeFinished = { PreferencesManager.setVolume(PreferencesManager.preferences.value.volume) },
                        modifier = Modifier
                            .width(layout.volumeSliderWidth)
                            .wheelStep({ PreferencesManager.preferences.value.volume }, 0.05f, onChange = onVolume)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    shortcut: String? = null
) = Tip(if (shortcut != null) "$contentDescription ($shortcut)" else contentDescription) {
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

internal data class DockLayout(val centerWidth: Dp, val volumeSliderWidth: Dp)

/**
 * Sizes the dock's zones from its inner width. The centre column holds five 40 dp buttons and the
 * seek bar; each side zone gets half of what is left. The right zone needs 160 dp for lyrics,
 * queue, overflow and mute, and the volume slider only appears once there is room for a usable
 * one: below that the wheel over the mute icon still sets the volume.
 */
internal fun dockLayout(width: Dp): DockLayout {
    val inner = width - 24.dp
    val center = (inner * 0.42f).coerceIn(244.dp, 560.dp)
    val side = (inner - center - 24.dp) / 2
    val slider = (side - 160.dp).coerceAtMost(110.dp)
    return DockLayout(center, if (slider >= 56.dp) slider else 0.dp)
}

/**
 * `m:ss`, or `h:mm:ss` once the track runs past an hour.
 *
 * Without the hours branch a 90 minute mix rendered as "90:00" and a long set as "150:00".
 * Never wrong, just unreadable, and hour-plus uploads are common on YouTube Music.
 */
internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/** Hover tooltip for the icon-only player controls: the action, and its shortcut if it has one. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Tip(text: String, content: @Composable () -> Unit) {
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    text,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        },
        delayMillis = 500,
        tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(
            offset = androidx.compose.ui.unit.DpOffset(0.dp, (-32).dp)
        ),
        content = content
    )
}

/**
 * Music videos carry an i.ytimg.com frame, and `hqdefault` is 4:3 with the 16:9 picture
 * letterboxed inside, so a square centre crop was mostly black bar. `mqdefault` is the same frame
 * at true 16:9, whose centre square is picture. Album art (googleusercontent) is already square.
 */
internal fun squareCropFriendly(url: String?): String? {
    if (url == null || "ytimg.com" !in url) return url
    return url.substringBefore('?').replace(Regex("/(hq|sd|maxres)default"), "/mqdefault")
}

/**
 * The seek bar with elapsed and total time at its ends. Reads the position itself so a tick
 * recomposes only this. The thumb only shows on hover or drag: at 0:00 it sat in the corner.
 */
@Composable
private fun SeekRow(player: DesktopPlayer, duration: Long) {
    val position by player.select { it.position }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatTime(position),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp)
        )
        PlayerSlider(
            value = if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { fraction -> player.seekTo((fraction * duration).toLong()) },
            idleThumb = 0.dp,
            modifier = Modifier.weight(1f).height(16.dp).padding(horizontal = 8.dp)
                .wheelStep(
                    value = { player.state.value.position.toFloat() },
                    step = 5_000f,
                    range = 0f..duration.toFloat(),
                    enabled = duration > 0
                ) { player.seekTo(it.toLong()) }
        )
        // Not "0:00" before the length is known: that read as a zero-length track.
        Text(
            if (duration > 0) formatTime(duration) else "–:––",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp)
        )
    }
}
