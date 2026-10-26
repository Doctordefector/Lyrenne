package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.settings.PreferencesManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Window size for the tray popup, in dp.
 *
 * [TRAY_PANEL_HEIGHT] is a deliberate OVER-estimate, not the panel height. The card wraps
 * its own content and is pinned to the bottom of this window, with the leftover space above
 * it left fully transparent — so the visible panel is always exactly as tall as its content.
 * Sizing the window to the content instead meant every tweak re-tuned a magic number and
 * either clipped the Open/Quit row or left dead space under it.
 */
const val TRAY_PANEL_WIDTH = 272
const val TRAY_PANEL_HEIGHT = 260

/**
 * The tray icon's context menu, replacing AWT's `PopupMenu`.
 *
 * `TrayIcon` only accepts a `java.awt.PopupMenu`, which is a heavyweight native Win32 menu —
 * unthemeable, no icons, no custom fonts. So the tray icon is created without one and the
 * right-click is handled manually, letting this render as ordinary Compose instead.
 */
@Composable
fun TrayPanel(
    player: DesktopPlayer,
    onOpenWindow: () -> Unit,
    onQuit: () -> Unit,
    onDismiss: () -> Unit
) {
    // Only what the panel shows, each collected on its own. The full playback state carries a
    // position that changes five times a second, and collecting all of it redrew the whole panel
    // at that rate. The position itself is read by TraySeekBar alone.
    val currentSong by remember(player) { player.state.map { it.currentSong }.distinctUntilChanged() }
        .collectAsState(player.state.value.currentSong)
    val isPlaying by remember(player) { player.state.map { it.isPlaying }.distinctUntilChanged() }
        .collectAsState(player.state.value.isPlaying)
    val prefs by PreferencesManager.preferences.collectAsState()
    val scope = rememberCoroutineScope()

    // The card hugs its content and sits at the bottom; the gap above stays transparent.
    // Clicking that empty strip dismisses, the way clicking outside a menu does.
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
    Surface(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            val song = currentSong
            if (song == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing playing",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (song.thumbnailUrl != null) {
                        AsyncImage(
                            model = song.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.MusicNote, null, Modifier.size(20.dp)) }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            song.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                TraySeekBar(player)

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { scope.launch { player.playPrevious() } },
                        modifier = Modifier.size(32.dp)
                    ) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(20.dp)) }
                    Spacer(Modifier.width(10.dp))
                    FilledIconButton(
                        onClick = { player.togglePlayPause() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "Pause" else "Play",
                            Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    IconButton(
                        onClick = { scope.launch { player.playNext() } },
                        modifier = Modifier.size(32.dp)
                    ) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(20.dp)) }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        null,
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = prefs.volume,
                        onValueChange = {
                            if (prefs.isMuted) PreferencesManager.setMuted(false)
                            PreferencesManager.setVolume(it)
                            player.setVolume(it)
                        },
                        modifier = Modifier.weight(1f).height(16.dp)
                    )
                    // The leading icon and its spacer inset the volume bar by 22dp, putting its
                    // midpoint 11dp right of the axis the seek bar and transport buttons sit on,
                    // which reads as the buttons being off-centre. Mirror the inset on the right.
                    Spacer(Modifier.width(22.dp))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TrayAction(Icons.Default.Launch, "Open", Modifier.weight(1f)) {
                    onDismiss(); onOpenWindow()
                }
                TrayAction(
                    Icons.Default.PowerSettingsNew,
                    "Quit",
                    Modifier.weight(1f),
                    tint = MaterialTheme.colorScheme.error
                ) { onQuit() }
            }
        }
    }
    }
}

/**
 * The seek bar and its times, the only part of the panel that follows the playback position.
 * Kept to whole seconds, which is all the times show, so this redraws once a second instead of
 * on every position tick.
 */
@Composable
private fun TraySeekBar(player: DesktopPlayer) {
    val duration by remember(player) { player.state.map { it.duration }.distinctUntilChanged() }
        .collectAsState(player.state.value.duration)
    val seconds by remember(player) { player.state.map { it.position / 1000 }.distinctUntilChanged() }
        .collectAsState(player.state.value.position / 1000)
    val position = seconds * 1000

    // Duration is 0 until VLC reports it (restored queue, still loading), and dividing by a
    // coerced 1ms made the bar read as 100% full at 0:01 / 0:00.
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val known = duration > 0L
    Slider(
        value = scrubbing ?: if (known) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
        onValueChange = { if (known) scrubbing = it },
        onValueChangeFinished = {
            scrubbing?.let { player.seekTo((it * duration).toLong()) }
            scrubbing = null
        },
        enabled = known,
        modifier = Modifier.fillMaxWidth().height(16.dp)
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatMs(position), style = MaterialTheme.typography.labelSmall)
        Text(
            if (known) formatMs(duration) else "--:--",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun TrayAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/** `m:ss`, or `h:mm:ss` past an hour. See the note on MiniPlayer's formatTime. */
private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, (total % 3600) / 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}
