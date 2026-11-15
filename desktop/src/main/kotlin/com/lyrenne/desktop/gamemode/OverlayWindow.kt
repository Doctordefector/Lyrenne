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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
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
    val fromEdge: (Int) -> Int = { h -> if (corner.top) -h / 3 else h / 3 }
    val enter = slideInVertically(tween(240)) { fromEdge(it) } + fadeIn(tween(200))
    val exit = slideOutVertically(tween(EXIT_MS)) { fromEdge(it) } + fadeOut(tween(EXIT_MS))
    val accent = rememberOverlayAccent(song?.thumbnailUrl)

    Box(modifier.padding(8.dp), contentAlignment = if (corner.top) Alignment.TopCenter else Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = horizontal,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val flash: @Composable () -> Unit = {
                AnimatedVisibility(
                    view.flash != null,
                    enter = fadeIn(tween(120)) + slideInVertically(tween(160)) { fromEdge(it) },
                    exit = fadeOut(tween(EXIT_MS))
                ) {
                    var last by remember { mutableStateOf(view.flash) }
                    if (view.flash != null) last = view.flash
                    last?.let { FlashChip(it, accent) }
                }
            }
            // The card and the lyrics are one panel: same width, one surface, so nothing floats loose.
            val panel: @Composable () -> Unit = {
                AnimatedVisibility(view.card || (view.lyrics && song != null), enter = enter, exit = exit) {
                    NowPlayingPanel(player, song, showCard = view.card, showLyrics = view.lyrics, accent = accent)
                }
            }
            if (corner.top) { panel(); flash() } else { flash(); panel() }
        }
    }
}

/** Every overlay surface is this wide, so the panel and the chip line up on the chosen edge. */
private val PanelWidth = 360.dp
private val PanelShape = RoundedCornerShape(18.dp)
private val PanelBase = Color(0xFF0D0D10)
private val TextStrong = Color(0xFFF7F5F2)
private val TextSoft = Color(0xB3F7F5F2) // 70%
private val TextFaint = Color(0x80F7F5F2) // 50%
private val FallbackAccent = Color(0xFFE9C489) // the bronze, lifted for a dark surface

/**
 * The cover's colour, made bright and saturated enough to read on the dark panel over any game.
 * Falls back to Lyrenne's bronze when the cover has no usable colour.
 */
@Composable
private fun rememberOverlayAccent(url: String?): Color {
    val tint by rememberArtTint(url)
    if (tint.alpha < 0.05f) return FallbackAccent
    val hsv = FloatArray(3)
    java.awt.Color.RGBtoHSB((tint.red * 255).toInt(), (tint.green * 255).toInt(), (tint.blue * 255).toInt(), hsv)
    if (hsv[1] < 0.12f) return FallbackAccent // greys read as no colour
    val rgb = java.awt.Color.HSBtoRGB(hsv[0], hsv[1].coerceIn(0.45f, 0.75f), 0.97f)
    return Color(rgb).copy(alpha = 1f)
}

/** Dark glass with a soft glow of the cover's colour from the left, and a hairline edge. */
private fun Modifier.glass(accent: Color): Modifier = this
    .clip(PanelShape)
    .background(PanelBase.copy(alpha = 0.90f))
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(0f, size.height * 0.3f),
                radius = size.width * 0.75f
            )
        )
    }
    .border(1.dp, Color.White.copy(alpha = 0.10f), PanelShape)

@Composable
private fun NowPlayingPanel(player: DesktopPlayer, song: SongInfo?, showCard: Boolean, showLyrics: Boolean, accent: Color) {
    val progress by remember(player) {
        player.state.map { s -> if (s.duration > 0) (s.position.toFloat() / s.duration).coerceIn(0f, 1f) else 0f }
            .map { (it * 300).toInt() / 300f }
            .distinctUntilChanged()
    }.collectAsState(0f)
    val isPlaying by remember(player) { player.state.map { it.isPlaying }.distinctUntilChanged() }.collectAsState(false)

    Box(Modifier.width(PanelWidth).glass(accent)) {
        Column(Modifier.padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
            if (showCard) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (song?.thumbnailUrl != null) {
                            AsyncImage(song.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song?.title ?: "Nothing playing",
                            color = TextStrong, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            song?.artist ?: "Play something in Lyrenne",
                            color = TextSoft, fontSize = 13.sp, lineHeight = 17.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (song != null) {
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            if (isPlaying) Icons.Default.GraphicEq else Icons.Default.Pause, null,
                            tint = if (isPlaying) accent else TextFaint, modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else if (song != null) {
                // Lyrics on their own still say what is playing, in one quiet line.
                Text(
                    song.title + "  ·  " + song.artist,
                    color = TextFaint, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (showLyrics && song != null) {
                Spacer(Modifier.height(if (showCard) 12.dp else 6.dp))
                LyricsLines(player, song, accent)
            }
        }
        if (song != null) {
            // Progress as a hairline along the bottom edge, inside the rounded corners.
            Box(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 6.dp).fillMaxWidth().height(3.dp)) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f)))
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(RoundedCornerShape(50)).background(accent))
            }
        }
    }
}

/**
 * The line being sung, lit word by word in the cover's colour when the lyrics carry word timing,
 * and the next line dimmed under it. Uses the lyrics panel's helpers, with the song's offset applied.
 */
@Composable
private fun LyricsLines(player: DesktopPlayer, song: SongInfo, accent: Color) {
    val lyrics by LyricsManager.state.collectAsState()
    val lines: List<LyricLine>? = lyrics.lines?.takeIf { lyrics.songId == song.id }
    if (lines == null) {
        Text(
            if (lyrics.isLoading && lyrics.songId == song.id) "Finding lyrics..." else "No synced lyrics for this song",
            color = TextFaint, fontSize = 13.sp, lineHeight = 17.sp
        )
        return
    }
    val position by remember(player) { player.state.map { it.position }.distinctUntilChanged() }.collectAsState(0L)
    val isPlaying by remember(player) { player.state.map { it.isPlaying }.distinctUntilChanged() }.collectAsState(false)
    val offset = remember(song.id, lyrics) { LyricsOffsets.get(song.id) }
    val speed = PreferencesManager.preferences.collectAsState().value.playbackSpeed
    val smooth by rememberSmoothPosition(position + offset, isPlaying, speed)
    val visibleLines = remember(lines) { lines.filter { it.text.isNotBlank() } }
    val index = currentLineIndex(visibleLines, smooth)
    val current = visibleLines.getOrNull(index)
    val next = visibleLines.getOrNull(index + 1)

    val text = current?.text ?: "♪"
    val words = current?.words
    if (current != null && words != null) {
        Text(
            highlightWords(text, words, smooth, accent, TextStrong.copy(alpha = 0.75f)),
            fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    } else {
        Text(
            text, color = TextStrong,
            fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
    if (next != null) {
        Spacer(Modifier.height(3.dp))
        Text(
            next.text, color = TextFaint,
            fontSize = 14.sp, lineHeight = 18.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** What a hotkey just did: an accent dot with the icon, the words, and the level for volume. */
@Composable
private fun FlashChip(flash: OverlayFlash, accent: Color) {
    val volume = PreferencesManager.preferences.collectAsState().value.volume
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(PanelBase.copy(alpha = 0.92f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
            .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(accent), contentAlignment = Alignment.Center) {
            Icon(flash.kind.icon(), null, tint = PanelBase, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(flash.text, color = TextStrong, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (flash.kind == OverlayFlash.Kind.VOLUME) {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(72.dp).height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(volume.coerceIn(0f, 1f)).background(accent))
            }
        }
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
