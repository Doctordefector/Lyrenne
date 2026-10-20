package com.lyrenne.desktop.ui.components

import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.RepeatMode
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.ui.theme.LocalLyrenneExtras
import com.lyrenne.desktop.ui.theme.LyrenneTokens
import com.lyrenne.desktop.ui.theme.rememberArtTint
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
const val TRAY_PANEL_WIDTH = 288
const val TRAY_PANEL_HEIGHT = 320

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
    val state by player.state.collectAsState()
    val prefs by PreferencesManager.preferences.collectAsState()
    val scope = rememberCoroutineScope()
    val song = state.currentSong
    val colors = MaterialTheme.colorScheme
    val artTint = rememberArtTint(song?.thumbnailUrl)
    val washAlpha = LocalLyrenneExtras.current.artWashAlpha

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
        shape = RoundedCornerShape(LyrenneTokens.dockRadius),
        color = colors.surface,
        border = BorderStroke(0.5.dp, colors.outlineVariant),
        shadowElevation = 8.dp
    ) {
        Column(
            // The cover's colour washes down from the top: the same tint the main window's player
            // dock carries, so the two read as one player. Draw phase only.
            Modifier
                .drawBehind {
                    val tint = artTint.value
                    if (tint.alpha > 0f) drawRect(
                        Brush.verticalGradient(
                            0f to tint.copy(alpha = tint.alpha * washAlpha),
                            1f to Color.Transparent
                        )
                    )
                }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (song == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing playing",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (song.thumbnailUrl != null) {
                        AsyncImage(
                            model = squareCropFriendly(song.thumbnailUrl),
                            contentDescription = null,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                                .background(colors.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.MusicNote, null, Modifier.size(22.dp)) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            song.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Seek bar. Duration is 0 until VLC reports it (restored queue, still loading),
                // and dividing by a coerced 1ms made the bar read as 100% full at 0:01 / 0:00.
                var scrubbing by remember { mutableStateOf<Float?>(null) }
                val duration = state.duration
                val known = duration > 0L
                PlayerSlider(
                    value = scrubbing
                        ?: if (known) (state.position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { if (known) scrubbing = it },
                    onValueChangeFinished = {
                        scrubbing?.let { player.seekTo((it * duration).toLong()) }
                        scrubbing = null
                    },
                    idleThumb = 0.dp,
                    modifier = Modifier.fillMaxWidth().height(16.dp)
                        .wheelStep(
                            value = { player.state.value.position.toFloat() },
                            step = 5_000f,
                            range = 0f..duration.toFloat(),
                            enabled = known
                        ) { player.seekTo(it.toLong()) }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        formatMs(state.position),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        // Same placeholder as the dock
                        if (known) formatMs(duration) else "–:––",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrayToggle(Icons.Default.Shuffle, "Shuffle", state.shuffleEnabled, player::toggleShuffle)
                    IconButton(
                        onClick = { scope.launch { player.playPrevious() } },
                        modifier = Modifier.size(36.dp)
                    ) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(22.dp)) }
                    FilledIconButton(
                        onClick = { player.togglePlayPause() },
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (state.isPlaying) "Pause" else "Play",
                            Modifier.size(24.dp)
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { player.playNext() } },
                        modifier = Modifier.size(36.dp)
                    ) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(22.dp)) }
                    TrayToggle(
                        if (state.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat",
                        state.repeatMode != RepeatMode.OFF,
                        player::toggleRepeat
                    )
                }

            }

            Spacer(Modifier.height(8.dp))

            // Volume is a short slider beside the icon, so it no longer looks like a second seek
            // bar; Open is the main action, Quit a quiet one at the end rather than a red twin.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (song != null) {
                    val onVolume: (Float) -> Unit = {
                        if (PreferencesManager.preferences.value.isMuted) PreferencesManager.setMuted(false)
                        PreferencesManager.setVolume(it)
                        player.setVolume(it)
                    }
                    Icon(
                        if (prefs.isMuted || prefs.volume == 0f) Icons.AutoMirrored.Filled.VolumeOff
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        "Volume",
                        Modifier.size(16.dp),
                        tint = colors.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    PlayerSlider(
                        value = prefs.volume,
                        // Dragging fires dozens of times a second: keep it in memory, write once on release.
                        onValueChange = {
                            if (PreferencesManager.preferences.value.isMuted) PreferencesManager.setMuted(false)
                            PreferencesManager.setVolume(it, persist = false)
                            player.setVolume(it)
                        },
                        onValueChangeFinished = { PreferencesManager.setVolume(PreferencesManager.preferences.value.volume) },
                        modifier = Modifier.width(96.dp).height(16.dp)
                            .wheelStep({ PreferencesManager.preferences.value.volume }, 0.05f, onChange = onVolume)
                    )
                }
                Spacer(Modifier.weight(1f))
                TrayAction(Icons.Default.Launch, "Open") { onDismiss(); onOpenWindow() }
                TextButton(
                    onClick = onQuit,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)
                ) { Text("Quit", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    }
}

/** Shuffle and repeat: a filled pill when on, as on the dock, so state is not tint alone. */
@Composable
private fun TrayToggle(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) =
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(icon, contentDescription = if (on) "$label on" else "$label off", modifier = Modifier.size(18.dp))
    }

@Composable
private fun TrayAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
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
