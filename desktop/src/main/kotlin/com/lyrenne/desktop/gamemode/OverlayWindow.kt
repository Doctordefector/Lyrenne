package com.lyrenne.desktop.gamemode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import coil3.compose.AsyncImage
import com.lyrenne.desktop.lyrics.LyricLine
import com.lyrenne.desktop.lyrics.LyricsManager
import com.lyrenne.desktop.lyrics.LyricsOffsets
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.settings.ThemeMode
import com.lyrenne.desktop.ui.components.currentLineIndex
import com.lyrenne.desktop.ui.components.highlightWords
import com.lyrenne.desktop.ui.components.rememberSmoothPosition
import com.lyrenne.desktop.ui.theme.LocalLyrenneExtras
import com.lyrenne.desktop.ui.theme.LyrenneTheme
import com.lyrenne.desktop.ui.theme.rememberArtTint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** How long the window stays up after its content goes, so the exit animation is seen. */
private const val EXIT_MS = 260
private const val STARTUP_DELAY_MS = 3_000L

/**
 * The in-game overlay's window. Call once from the application scope.
 *
 * It exists while Game Mode could need it (overlay on, or hotkeys on, since hotkeys flash a
 * confirmation in it) and is otherwise hidden: shown only while it has something to draw. A
 * visible window over a borderless game, even an empty one, makes Windows compose the game
 * through the desktop instead of flipping it straight to the screen, which can cost frames.
 */
@Composable
fun GameOverlayWindow(player: DesktopPlayer, quitting: Boolean) {
    if (!GameMode.overlaySupported) return
    val prefs by PreferencesManager.preferences.collectAsState()
    val gm = prefs.gameMode
    val wanted = gm.overlayMode != OverlayMode.OFF || gm.hotkeysEnabled
    // Not during startup: nothing can be shown in the first seconds, and the main window comes first.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(STARTUP_DELAY_MS); started = true }
    if (!wanted || !started) return

    val view by GameMode.view.collectAsState()
    // Shown with its content, hidden a moment after it, so the slide out is not cut off.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(view.visible) {
        if (view.visible) shown = true else { delay(EXIT_MS.toLong()); shown = false }
    }

    val widthDp = gm.overlaySize.windowWidthDp()
    val heightDp = gm.overlaySize.windowHeightDp()

    Window(
        visible = shown && !quitting,
        create = {
            ComposeWindow().apply {
                // All before the first show; AWT refuses most of them afterwards. UTILITY keeps
                // it off the taskbar, and not focusable plus no auto focus means showing it never
                // activates it. The Win32 styles below make that hold for clicks too.
                type = java.awt.Window.Type.UTILITY
                isUndecorated = true
                isTransparent = true
                isAlwaysOnTop = true
                isResizable = false
                focusableWindowState = false
                isAutoRequestFocus = false
                title = "Lyrenne overlay"
                setSize(widthDp, heightDp)
                // Creates the native window without showing it, so the click-through and
                // no-activate styles are in place before it is ever on screen.
                addNotify()
                OverlayWin32.apply(this)
            }
        },
        dispose = { it.dispose() }
    ) {
        // Size and position follow the settings, and the monitor follows the game while shown.
        LaunchedEffect(shown, gm.overlayCorner, gm.overlaySize, gm.overlayMonitor) {
            if (!shown) return@LaunchedEffect
            while (true) {
                val screen = OverlayScreens.target(gm.overlayMonitor)
                val at = overlayLocation(screen, gm.overlayCorner, widthDp, heightDp)
                if (window.width != widthDp || window.height != heightDp) window.setSize(widthDp, heightDp)
                if (window.location != at) window.location = at
                // AWT rewrites the extended style for some changes (translucency, always on top),
                // so it is checked again rather than trusted to have stuck.
                OverlayWin32.apply(window)
                if (gm.overlayMonitor != GameModePrefs.MONITOR_FOLLOW) break
                delay(1_500)
            }
        }

        LyrenneTheme(themeMode = ThemeMode.DARK) {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density * gm.overlaySize.scale, base.fontScale)
            ) {
                OverlayContent(
                    player = player,
                    view = view,
                    corner = gm.overlayCorner,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = gm.overlayOpacity }
                )
            }
        }
    }
}

@Composable
internal fun OverlayContent(player: DesktopPlayer, view: OverlayView, corner: OverlayCorner, modifier: Modifier = Modifier) {
    val song by remember(player) { player.state.map { it.currentSong }.distinctUntilChanged() }
        .collectAsState(player.state.value.currentSong)
    val horizontal = when (corner.horizontal) {
        -1 -> Alignment.Start
        0 -> Alignment.CenterHorizontally
        else -> Alignment.End
    }
    val fromEdge: (Int) -> Int = { h -> if (corner.top) -h else h }
    val enter = slideInVertically(tween(260)) { fromEdge(it) } + fadeIn(tween(200))
    val exit = slideOutVertically(tween(EXIT_MS)) { fromEdge(it) } + fadeOut(tween(EXIT_MS))

    Box(modifier.padding(6.dp), contentAlignment = if (corner.top) Alignment.TopCenter else Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = horizontal,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Lyrics sit nearest the screen edge, the card next to them, the flash furthest in.
            val flash: @Composable () -> Unit = {
                AnimatedVisibility(view.flash != null, enter = fadeIn(tween(120)), exit = fadeOut(tween(EXIT_MS))) {
                    var last by remember { mutableStateOf(view.flash) }
                    if (view.flash != null) last = view.flash
                    last?.let { FlashPill(it) }
                }
            }
            val card: @Composable () -> Unit = {
                AnimatedVisibility(view.card, enter = enter, exit = exit) { SongCard(player, song) }
            }
            val lyrics: @Composable () -> Unit = {
                AnimatedVisibility(view.lyrics && song != null, enter = enter, exit = exit) {
                    song?.let { LyricsStrip(player, it) }
                }
            }
            if (corner.top) { card(); lyrics(); flash() } else { flash(); card(); lyrics() }
        }
    }
}

private val OverlayShape = RoundedCornerShape(16.dp)

@Composable
private fun overlaySurface(): Modifier {
    val colors = MaterialTheme.colorScheme
    return Modifier
        .clip(OverlayShape)
        // Solid: the opacity setting is the one place see-through is decided. A translucent
        // surface under it as well let a busy game read straight through the text.
        .background(colors.surfaceContainerHigh)
        .border(0.5.dp, colors.outlineVariant, OverlayShape)
}

@Composable
private fun SongCard(player: DesktopPlayer, song: SongInfo?) {
    val tint by rememberArtTint(song?.thumbnailUrl)
    val wash = LocalLyrenneExtras.current.artWashAlpha
    val progress by remember(player) {
        player.state.map { s -> if (s.duration > 0) (s.position.toFloat() / s.duration).coerceIn(0f, 1f) else 0f }
            .map { (it * 200).toInt() / 200f }
            .distinctUntilChanged()
    }.collectAsState(0f)
    Row(
        overlaySurface()
            .widthIn(max = 400.dp)
            .drawBehind { if (tint.alpha > 0f) drawRect(tint.copy(alpha = tint.alpha * wash)) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            if (song?.thumbnailUrl != null) {
                AsyncImage(song.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.widthIn(max = 300.dp)) {
            Text(
                song?.title ?: "Nothing playing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                song?.artist ?: "Play something in Lyrenne and it shows up here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (song != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.width(180.dp).height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                    drawStopIndicator = {}
                )
            }
        }
        Spacer(Modifier.width(6.dp))
    }
}

/**
 * The line being sung, lit word by word when the lyrics carry word timing, and the next line
 * dimmed under it. Uses the lyrics panel's own helpers, with the song's timing offset applied.
 */
@Composable
private fun LyricsStrip(player: DesktopPlayer, song: SongInfo) {
    val lyrics by LyricsManager.state.collectAsState()
    val lines: List<LyricLine> = lyrics.lines?.takeIf { lyrics.songId == song.id } ?: return
    val position by remember(player) { player.state.map { it.position }.distinctUntilChanged() }.collectAsState(0L)
    val isPlaying by remember(player) { player.state.map { it.isPlaying }.distinctUntilChanged() }.collectAsState(false)
    val offset = remember(song.id, lyrics) { LyricsOffsets.get(song.id) }
    val speed = PreferencesManager.preferences.collectAsState().value.playbackSpeed
    val smooth by rememberSmoothPosition(position + offset, isPlaying, speed)
    val visibleLines = remember(lines) { lines.filter { it.text.isNotBlank() } }
    val index = currentLineIndex(visibleLines, smooth)
    val current = visibleLines.getOrNull(index)
    val next = visibleLines.getOrNull(index + 1)

    val colors = MaterialTheme.colorScheme
    Column(
        overlaySurface().widthIn(min = 220.dp, max = 408.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val words = current?.words
        val text = current?.text ?: "♪"
        if (current != null && words != null) {
            Text(
                highlightWords(text, words, smooth, colors.primary, colors.onSurface.copy(alpha = 0.9f)),
                fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                text, color = colors.onSurface,
                fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        if (next != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                next.text, color = colors.onSurfaceVariant,
                fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FlashPill(flash: OverlayFlash) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.primaryContainer)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(flash.kind.icon(), null, tint = colors.onPrimaryContainer, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(flash.text, color = colors.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
    }
}

private fun OverlayFlash.Kind.icon(): ImageVector = when (this) {
    OverlayFlash.Kind.PLAY -> Icons.Default.PlayArrow
    OverlayFlash.Kind.PAUSE -> Icons.Default.Pause
    OverlayFlash.Kind.NEXT -> Icons.Default.SkipNext
    OverlayFlash.Kind.PREVIOUS -> Icons.Default.SkipPrevious
    OverlayFlash.Kind.LIKED -> Icons.Default.Favorite
    OverlayFlash.Kind.UNLIKED -> Icons.Default.FavoriteBorder
    OverlayFlash.Kind.VOLUME -> Icons.AutoMirrored.Filled.VolumeUp
    OverlayFlash.Kind.SEEK_FORWARD -> Icons.Default.FastForward
    OverlayFlash.Kind.SEEK_BACK -> Icons.Default.FastRewind
    OverlayFlash.Kind.OVERLAY_ON -> Icons.Default.Visibility
    OverlayFlash.Kind.OVERLAY_OFF -> Icons.Default.VisibilityOff
    OverlayFlash.Kind.INFO -> Icons.Default.SportsEsports
}
