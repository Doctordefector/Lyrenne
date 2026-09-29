package com.lyrenne.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import coil3.compose.AsyncImage
import com.lyrenne.desktop.playback.DesktopPlayer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The window is a little larger than the disc, so its shadow and the hover buttons fit. */
const val FLOATING_PLAYER_WINDOW_DP = 164
private const val DISC_DP = 132

/**
 * The floating mini player: the song's artwork on a disc, previous and next either side of
 * play/pause, and a ring round the edge for progress (requested in issue #11).
 *
 * Lives in its own undecorated, always-on-top window that Main.kt shows while the main window
 * is minimized or hidden in the tray. The disc is the drag handle; double-click it to bring
 * Lyrenne back. Hovering reveals a close button, which hides it until the main window is next
 * restored, and an open button for anyone who does not think to double-click.
 */
@Composable
fun WindowScope.FloatingPlayer(
    player: DesktopPlayer,
    onOpenMain: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val song by remember(player) { player.state.map { it.currentSong }.distinctUntilChanged() }
        .collectAsState(player.state.value.currentSong)
    val isPlaying by remember(player) { player.state.map { it.isPlaying }.distinctUntilChanged() }
        .collectAsState(player.state.value.isPlaying)
    // Whole-percent steps: enough for a ring this size, and it keeps the window from recomposing
    // on every position tick for a change nobody could see.
    val progress by remember(player) {
        player.state.map { s ->
            if (s.duration > 0) ((s.position * 100) / s.duration).coerceIn(0, 100).toInt() else 0
        }.distinctUntilChanged()
    }.collectAsState(0)

    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val scrim by animateFloatAsState(if (hovered) 0.55f else 0.3f)
    val ringColor = MaterialTheme.colorScheme.primary
    val trackColor = Color.White.copy(alpha = 0.18f)

    Box(
        modifier = Modifier.fillMaxSize().hoverable(hoverSource),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.size(DISC_DP.dp).dragWindowAndDoubleClick(window, onOpenMain)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .drawWithContent {
                        drawContent()
                        val stroke = 4.dp.toPx()
                        val inset = stroke / 2
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                        drawArc(
                            ringColor, -90f, 360f * progress / 100f, false, Offset(inset, inset), arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Round)
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                val art = song?.thumbnailUrl
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = song?.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { scope.launch { player.playPrevious() } },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White)
                    }
                    FilledIconButton(
                        onClick = { player.togglePlayPause() },
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { player.playNext() } },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.SkipNext, "Next", tint = Color.White)
                    }
                }
            }
        }

        // Corner buttons sit in the window's margin, outside the disc, so they never cover the
        // artwork or steal a drag.
        AnimatedVisibility(
            visible = hovered,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            SmallRoundButton(onClick = onOpenMain) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open Lyrenne", modifier = Modifier.size(14.dp))
            }
        }
        AnimatedVisibility(
            visible = hovered,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            SmallRoundButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Hide the floating player", modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * Drags the window from any press the buttons did not take, and reports two presses in quick
 * succession as a double-click.
 *
 * Both jobs have to live in one handler. Compose's own WindowDraggableArea only starts on an
 * unconsumed press, and a double-click detector consumes every press it sees, so with the two
 * stacked the disc could be double-clicked and never dragged. Here the press is observed after
 * the children have had it, and a press the transport buttons consumed is left to them.
 *
 * The move itself tracks the pointer through AWT rather than Compose, as WindowDraggableArea
 * does: once the window starts moving under the cursor, positions relative to it are useless.
 */
private fun Modifier.dragWindowAndDoubleClick(window: java.awt.Window, onDoubleClick: () -> Unit): Modifier =
    pointerInput(window) {
        var lastPress = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.isConsumed) return@awaitEachGesture
            val now = down.uptimeMillis
            if (now - lastPress < DOUBLE_CLICK_MS) {
                lastPress = 0L
                onDoubleClick()
                return@awaitEachGesture
            }
            lastPress = now

            val pointerStart = java.awt.MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
            val windowStart = window.location
            do {
                val event = awaitPointerEvent()
                java.awt.MouseInfo.getPointerInfo()?.location?.let { p ->
                    window.setLocation(windowStart.x + p.x - pointerStart.x, windowStart.y + p.y - pointerStart.y)
                }
            } while (event.changes.any { it.pressed })
        }
    }

private const val DOUBLE_CLICK_MS = 400L

@Composable
private fun SmallRoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
        modifier = Modifier.padding(4.dp).size(26.dp)
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * Where to open the floating player: where it was last left if that spot is still on a screen,
 * otherwise the bottom-right corner of the primary screen's usable area, clear of the taskbar.
 *
 * The on-screen check is what stops an unplugged second monitor from stranding the player
 * somewhere it can never be dragged back from. Units are AWT user space, which is what Compose
 * dp map to for window geometry.
 */
fun floatingPlayerLocation(savedX: Int?, savedY: Int?): java.awt.Point {
    val size = FLOATING_PLAYER_WINDOW_DP
    val env = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
    if (savedX != null && savedY != null) {
        val centre = java.awt.Point(savedX + size / 2, savedY + size / 2)
        val visible = env.screenDevices.any { it.defaultConfiguration.bounds.contains(centre) }
        if (visible) return java.awt.Point(savedX, savedY)
    }
    val usable = env.maximumWindowBounds
    val margin = 24
    return java.awt.Point(usable.x + usable.width - size - margin, usable.y + usable.height - size - margin)
}
