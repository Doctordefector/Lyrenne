package com.lyrenne.desktop.ui.stage

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lyrenne.desktop.lyrics.LyricLine
import com.lyrenne.desktop.lyrics.LyricsManager
import com.lyrenne.desktop.lyrics.LyricsOffsets
import com.lyrenne.desktop.lyrics.Romanizer
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.ui.components.Tip
import com.lyrenne.desktop.ui.components.currentLineIndex
import com.lyrenne.desktop.ui.components.formatTime
import com.lyrenne.desktop.ui.components.select
import com.lyrenne.desktop.ui.components.squareCropFriendly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The time Stage animates by, and the playback position smoothed between the player's reports.
 *
 * One frame loop drives everything that moves on Stage (background, word fill, progress bar),
 * and only while the song is playing and the window is not minimised. Both values are read in
 * draw lambdas, never in composition, so a frame redraws and recomposes nothing. Paused, the loop
 * is not running at all: Stage then costs what any still window costs, which is nothing.
 */
@Stable
internal class StageClock {
    /** Seconds of animation time; stands still while paused. */
    val time = mutableFloatStateOf(0f)
    /** Playback position in ms, advanced every frame between the player's ~200 ms reports. */
    val position = mutableLongStateOf(0L)
}

@Composable
internal fun rememberStageClock(player: DesktopPlayer, running: Boolean, speed: Float): StageClock {
    val clock = remember { StageClock() }
    LaunchedEffect(player, running, speed) {
        var anchorPos = player.state.value.position
        var anchorNanos = System.nanoTime()
        clock.position.longValue = anchorPos
        launch {
            player.state.map { it.position }.distinctUntilChanged().collect { pos ->
                anchorPos = pos
                anchorNanos = System.nanoTime()
                if (!running) clock.position.longValue = pos
            }
        }
        if (!running) return@LaunchedEffect
        var lastFrame = -1L
        while (true) {
            withFrameNanos { frame ->
                if (lastFrame > 0) {
                    // A long gap (window dragged, machine busy) is not a jump in the animation.
                    val dt = ((frame - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
                    clock.time.floatValue += dt
                }
                lastFrame = frame
                val ahead = ((System.nanoTime() - anchorNanos) / 1_000_000.0 * speed).toLong()
                clock.position.longValue = anchorPos + ahead.coerceIn(0L, 1_000L)
            }
        }
    }
    return clock
}

/**
 * Stage: the whole window given to what is playing, for a party, a second screen or a TV.
 *
 * The parts that move all read [StageClock] in draw, so the cost of a frame is drawing, not
 * composing. Lines change by recomposition, once a line. The controls fade away when the mouse
 * has been still for a few seconds while playing, and the pointer goes with them.
 */
@Composable
fun StageScreen(player: DesktopPlayer, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val windowState = LocalMainWindowState.current
    val song by player.select { it.currentSong }
    val isPlaying by player.select { it.isPlaying }
    val duration by player.select { it.duration }
    val upNext by player.select { s -> s.queue.getOrNull(s.currentIndex + 1) }
    val prefs by PreferencesManager.preferences.collectAsState()
    val minimized = windowState?.isMinimized == true
    val clock = rememberStageClock(player, running = isPlaying && !minimized, speed = prefs.playbackSpeed)

    val current = song
    val lyricsState by LyricsManager.state.collectAsState()
    val lines = lyricsState.lines?.takeIf { current != null && lyricsState.songId == current.id && it.any { l -> l.text.isNotBlank() } }
    val offsetMs = remember(current?.id) { current?.let { LyricsOffsets.get(it.id) } ?: 0L }
    val romanized = rememberRomanized(lines, prefs.lyricsRomanize)
    val scope = rememberCoroutineScope()

    StageView(
        song = current, isPlaying = isPlaying, duration = duration, upNext = upNext,
        lines = lines, offsetMs = offsetMs, romanized = romanized, clock = clock,
        windowState = windowState, onClose = onClose,
        actions = StageActions(
            playPause = player::togglePlayPause,
            previous = { scope.launch { player.playPrevious() } },
            next = { scope.launch { player.playNext() } },
            seek = player::seekTo,
        ),
        modifier = modifier,
    )
}

internal class StageActions(val playPause: () -> Unit, val previous: () -> Unit, val next: () -> Unit, val seek: (Long) -> Unit)

/** Stage itself, from plain values, so it can be rendered without a player (StageSnapshotTest). */
@Composable
internal fun StageView(
    song: SongInfo?,
    isPlaying: Boolean,
    duration: Long,
    upNext: SongInfo?,
    lines: List<LyricLine>?,
    offsetMs: Long,
    romanized: Map<String, String>,
    clock: StageClock,
    windowState: androidx.compose.ui.window.WindowState?,
    onClose: () -> Unit,
    actions: StageActions,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors by rememberStageColors(song?.thumbnailUrl, dark)

    // Full screen follows the request, and is undone when Stage goes away by any route.
    val keeper = remember(windowState) { FullscreenKeeper(windowState) }
    LaunchedEffect(StageMode.fullscreenRequested) { keeper.apply(StageMode.fullscreenRequested) }
    DisposableEffect(keeper) { onDispose { keeper.apply(false) } }
    val isFullscreen = StageMode.fullscreenRequested && windowState != null

    // Controls: shown on any pointer movement, hidden after a still spell while playing.
    // The last movement is a plain field, not state: a moving mouse must not recompose Stage.
    val lastActivity = remember { longArrayOf(System.nanoTime()) }
    var controlsVisible by remember { mutableStateOf(true) }
    val controlsHover = remember { MutableInteractionSource() }
    val hoveringControls by controlsHover.collectIsHoveredAsState()
    LaunchedEffect(controlsVisible, isPlaying, hoveringControls) {
        if (!controlsVisible || !isPlaying || hoveringControls) return@LaunchedEffect
        while (true) {
            val idleMs = (System.nanoTime() - lastActivity[0]) / 1_000_000
            if (idleMs >= CONTROLS_IDLE_MS) { controlsVisible = false; break }
            delay(CONTROLS_IDLE_MS - idleMs)
        }
    }
    val controlsAlpha by animateFloatAsState(if (controlsVisible) 1f else 0f, tween(if (controlsVisible) 200 else 700), label = "controls")

    Box(
        modifier
            .fillMaxSize()
            .background(colors.base)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastActivity[0] = System.nanoTime()
                        if (!controlsVisible) controlsVisible = true
                    }
                }
            }
            .pointerHoverIcon(if (controlsVisible) PointerIcon.Default else BlankPointer)
    ) {
        StageBackground(clock, { colors })

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val boxW = maxWidth
            val boxH = maxHeight
            val wide = boxW >= 860.dp && boxW > boxH * 1.15f
            val current = song

            when {
                current == null -> NothingPlaying(colors)
                lines != null && wide -> Row(
                    Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(0.42f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val art = min(boxW.value * 0.42f * 0.8f, boxH.value * 0.58f).dp
                        StageArt(current, art, clock, colors)
                        Spacer(Modifier.height(28.dp))
                        SongTitle(current, colors, Alignment.CenterHorizontally, titleSize = (art.value / 13f).coerceIn(20f, 34f).sp)
                    }
                    Spacer(Modifier.width(56.dp))
                    StageLyrics(
                        lines, clock, offsetMs, romanized, colors,
                        textAlign = TextAlign.Start,
                        size = lyricSize(boxW * 0.5f, boxH),
                        modifier = Modifier.weight(0.58f).fillMaxHeight()
                    )
                }
                lines != null -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 24.dp)) {
                        StageArt(current, 76.dp, clock, colors)
                        Spacer(Modifier.width(16.dp))
                        SongTitle(current, colors, Alignment.Start, titleSize = 20.sp)
                    }
                    StageLyrics(
                        lines, clock, offsetMs, romanized, colors,
                        textAlign = TextAlign.Center,
                        size = lyricSize(boxW, boxH - 140.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
                else -> NoLyrics(current, upNext, clock, colors, boxW, boxH)
            }
        }

        // ---- Controls
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = controlsAlpha }) {
            if (controlsAlpha > 0.01f) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(16.dp).hoverable(controlsHover),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (windowState != null) {
                        StageIconButton(
                            if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            if (isFullscreen) "Exit full screen (F11)" else "Full screen (F11)",
                            colors, onClick = StageMode::toggleFullscreen
                        )
                    }
                    StageIconButton(Icons.Default.Close, "Leave Stage (Esc)", colors, onClick = onClose)
                }
                if (song != null) {
                    StageTransport(
                        // Without lyrics the page shows up next itself.
                        actions, clock, duration, isPlaying, upNext.takeIf { lines != null }, colors,
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().hoverable(controlsHover)
                    )
                }
            }
        }
    }
}

private const val CONTROLS_IDLE_MS = 2_800L

/** An invisible pointer, so a still mouse does not sit in the middle of the lyrics on a TV. */
private val BlankPointer: PointerIcon by lazy {
    try {
        val img = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(img, java.awt.Point(0, 0), "blank"))
    } catch (_: Throwable) {
        // Headless (tests) or a toolkit without custom cursors: keep the normal one.
        PointerIcon.Default
    }
}

/** Current line size: big, but a long line still fits in two or three lines. */
private fun lyricSize(width: Dp, height: Dp): TextUnit =
    min(width.value / 15f, height.value / 8.5f).coerceIn(26f, 68f).sp

@Composable
private fun rememberRomanized(lines: List<LyricLine>?, enabled: Boolean): Map<String, String> {
    val texts = remember(lines) { lines?.map { it.text }.orEmpty() }
    val needed = remember(texts) { texts.any(Romanizer::needsRomanization) }
    val result by produceState(emptyMap<String, String>(), texts, enabled, needed) {
        value = emptyMap()
        if (enabled && needed) value = withContext(Dispatchers.Default) { Romanizer.romanize(texts) }
    }
    return result
}

/**
 * Slow, overlapping glows in the cover's colours, drifting on Lissajous paths. Radial gradients
 * only: no blur, no bitmap, so a frame is four gradient fills whatever the window size.
 */
@Composable
private fun StageBackground(clock: StageClock, colors: () -> StageColors) {
    Canvas(Modifier.fillMaxSize()) {
        val c = colors()
        val t = clock.time.floatValue
        val w = size.width; val h = size.height
        val reach = max(w, h)
        val alpha = if (c.dark) BLOB_ALPHA_DARK else BLOB_ALPHA_LIGHT
        BLOBS.forEachIndexed { i, b ->
            val color = c.blobs[i % c.blobs.size]
            val cx = w * (0.5f + b.ax * sin(t * b.sx + b.px))
            val cy = h * (0.5f + b.ay * cos(t * b.sy + b.py))
            val r = reach * (b.r + 0.05f * sin(t * 0.21f + i))
            drawCircle(
                Brush.radialGradient(
                    0f to color.copy(alpha = alpha),
                    0.55f to color.copy(alpha = alpha * 0.45f),
                    1f to Color.Transparent,
                    center = Offset(cx, cy), radius = r
                ),
                radius = r, center = Offset(cx, cy)
            )
        }
        // Edges settle back toward the page, so the text area is never the brightest part.
        drawRect(
            Brush.radialGradient(
                0.5f to Color.Transparent, 1f to c.base.copy(alpha = 0.6f),
                center = Offset(w / 2, h / 2), radius = reach * 0.75f
            )
        )
    }
}

private class Blob(val ax: Float, val ay: Float, val sx: Float, val sy: Float, val px: Float, val py: Float, val r: Float)

private val BLOBS = listOf(
    Blob(0.32f, 0.28f, 0.050f, 0.043f, 0.0f, 1.3f, 0.62f),
    Blob(0.36f, 0.30f, 0.037f, 0.055f, 2.1f, 0.4f, 0.55f),
    Blob(0.28f, 0.34f, 0.061f, 0.032f, 4.0f, 2.6f, 0.50f),
    Blob(0.40f, 0.22f, 0.029f, 0.047f, 5.2f, 3.9f, 0.45f),
)

@Composable
private fun StageArt(song: SongInfo, size: Dp, clock: StageClock, colors: StageColors, record: Boolean = false) {
    val shape = if (record) CircleShape else RoundedCornerShape((size.value / 22f).coerceIn(6f, 18f).dp)
    Box(
        Modifier.size(size)
            // A slow breath while playing: about one percent, one cycle per eight seconds. As a
            // record it turns instead, once every half minute.
            .graphicsLayer {
                val s = 1f + 0.012f * sin(clock.time.floatValue * 0.8f)
                scaleX = s; scaleY = s
                if (record) rotationZ = (clock.time.floatValue * 12f) % 360f
                shadowElevation = (size.value / 14f).coerceAtMost(28f).dp.toPx()
                this.shape = shape
                clip = true
            }
            // Opaque, or the layer's shadow shows through while the cover loads.
            .background(StageColors.over(colors.blobs[0].copy(alpha = 0.6f), colors.base))
    ) {
        AsyncImage(
            model = squareCropFriendly(song.thumbnailUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (record) {
            // The label hole, so it reads as a record rather than a round crop.
            Box(Modifier.align(Alignment.Center).size(size * 0.16f).clip(CircleShape).background(colors.base.copy(alpha = 0.85f)))
            Box(Modifier.align(Alignment.Center).size(size * 0.04f).clip(CircleShape).background(colors.text.copy(alpha = 0.5f)))
        }
    }
}

@Composable
private fun SongTitle(song: SongInfo, colors: StageColors, align: Alignment.Horizontal, titleSize: TextUnit) {
    val textAlign = if (align == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start
    Column(horizontalAlignment = align) {
        Text(
            song.title, color = colors.text, fontSize = titleSize, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = textAlign,
            lineHeight = (titleSize.value * 1.2f).sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.album?.takeIf { it.isNotBlank() && it != song.title })
                .joinToString("  ·  "),
            color = colors.textDim, fontSize = (titleSize.value * 0.62f).coerceAtLeast(14f).sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign
        )
    }
}

/**
 * The line being sung, large, with the next one dimmed under it and the last one fading above.
 * The line changes slide up, once a line; the word fill inside a line is drawn, not composed.
 */
@Composable
private fun StageLyrics(
    lines: List<LyricLine>,
    clock: StageClock,
    offsetMs: Long,
    romanized: Map<String, String>,
    colors: StageColors,
    textAlign: TextAlign,
    size: TextUnit,
    modifier: Modifier,
) {
    val index by remember(lines, offsetMs) {
        derivedStateOf { currentLineIndex(lines, clock.position.longValue + offsetMs) }
    }
    val align = if (textAlign == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start
    AnimatedContent(
        targetState = index,
        transitionSpec = {
            val forward = targetState > initialState
            (slideInVertically(tween(520)) { if (forward) it / 5 else -it / 5 } + fadeIn(tween(420)))
                .togetherWith(slideOutVertically(tween(520)) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(300)))
        },
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
        label = "stageLine"
    ) { i ->
        val prev = lines.getOrNull(i - 1)?.takeIf { it.text.isNotBlank() }
        val line = lines.getOrNull(i)
        val next = nextSung(lines, i)
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = align
        ) {
            val small = (size.value * 0.5f).sp
            Text(
                prev?.text ?: "", color = colors.textFaint, fontSize = small, fontWeight = FontWeight.SemiBold,
                textAlign = textAlign, maxLines = 2, overflow = TextOverflow.Ellipsis,
                lineHeight = (small.value * 1.25f).sp, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height((size.value * 0.55f).dp))
            if (line == null || line.text.isBlank()) {
                // Before the first line and in instrumental gaps: a quiet pulse, not an empty hole.
                BreathingDots(clock, colors, size)
            } else {
                KaraokeLine(line, clock, offsetMs, colors, size, textAlign)
                romanized[line.text]?.let { reading ->
                    Text(
                        reading, color = colors.textDim, fontSize = (size.value * 0.48f).sp,
                        fontStyle = FontStyle.Italic, textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                }
                line.background.firstOrNull()?.let { bg ->
                    Text(
                        bg.text, color = colors.textDim, fontSize = (size.value * 0.5f).sp,
                        fontStyle = FontStyle.Italic, textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height((size.value * 0.6f).dp))
            val nextSize = (size.value * 0.62f).sp
            Text(
                next?.text ?: "", color = colors.textDim, fontSize = nextSize, fontWeight = FontWeight.Bold,
                textAlign = textAlign, maxLines = 3, overflow = TextOverflow.Ellipsis,
                lineHeight = (nextSize.value * 1.22f).sp, modifier = Modifier.fillMaxWidth()
            )
            romanized[next?.text]?.let { reading ->
                Text(
                    reading, color = colors.textFaint, fontSize = (size.value * 0.36f).sp,
                    fontStyle = FontStyle.Italic, textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
    }
}

private fun nextSung(lines: List<LyricLine>, i: Int): LyricLine? {
    for (j in (i + 1) until lines.size) if (lines[j].text.isNotBlank()) return lines[j]
    return null
}

/**
 * One lyric line with a karaoke fill: the line is laid out once and drawn dim, then drawn again
 * in full colour clipped to everything sung so far, with a soft leading edge inside the word
 * being sung. The clip moves every frame in draw only. Lines without word timing light up whole.
 */
@Composable
private fun KaraokeLine(
    line: LyricLine,
    clock: StageClock,
    offsetMs: Long,
    colors: StageColors,
    size: TextUnit,
    textAlign: TextAlign,
) {
    val words = line.words
    val spans = remember(line) { if (words == null) null else wordSpans(line.text, words) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val style = TextStyle(
        fontSize = size, fontWeight = FontWeight.ExtraBold, textAlign = textAlign,
        lineHeight = (size.value * 1.18f).sp,
    )
    if (spans.isNullOrEmpty()) {
        Text(line.text, style = style, color = colors.text, modifier = Modifier.fillMaxWidth())
        return
    }
    Text(
        line.text,
        style = style,
        color = colors.textDim.copy(alpha = colors.textDim.alpha * 0.62f),
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().drawWithContent {
            drawContent()
            val l = layout ?: return@drawWithContent
            val pos = clock.position.longValue + offsetMs
            val fill = fillPoint(spans, pos) ?: return@drawWithContent
            val (charStart, charEnd, fraction) = fill
            val rtl = l.getParagraphDirection(charStart) == ResolvedTextDirection.Rtl
            val lineNo = l.getLineForOffset(charStart)
            val a = l.getBoundingBox(charStart)
            val b = l.getBoundingBox((charEnd - 1).coerceAtLeast(charStart))
            val sameLine = l.getLineForOffset(charEnd - 1) == lineNo
            val left = if (rtl) b.left else a.left
            val right = if (sameLine) (if (rtl) a.right else b.right) else l.getLineRight(lineNo)
            val x = if (rtl) right - (right - left) * fraction else left + (right - left) * fraction
            val top = l.getLineTop(lineNo)
            val bottom = l.getLineBottom(lineNo)
            val edge = (size.toPx() * 0.35f)
            // Every line above the current one is fully sung.
            if (top > 0f) clipRect(0f, 0f, this.size.width, top) {
                drawText(l, color = colors.text)
            }
            clipRect(0f, top, this.size.width, bottom) {
                val brush = if (rtl) Brush.horizontalGradient(listOf(Color.Transparent, colors.text), startX = x - edge, endX = x + edge)
                else Brush.horizontalGradient(listOf(colors.text, Color.Transparent), startX = x - edge, endX = x + edge)
                drawText(l, brush = brush)
            }
        }
    )
}

/** Character ranges of [words] in [text], found left to right as LyricsPanel's highlight does. */
internal fun wordSpans(text: String, words: List<com.lyrenne.desktop.lyrics.LyricWord>): List<Triple<IntRange, Long, Long>> {
    val out = ArrayList<Triple<IntRange, Long, Long>>(words.size)
    var cursor = 0
    for (w in words) {
        val needle = w.text.trim()
        if (needle.isEmpty()) continue
        val start = text.indexOf(needle, cursor)
        if (start < 0) continue
        cursor = start + needle.length
        out += Triple(start until cursor, w.startMs, w.endMs)
    }
    return out
}

/**
 * Where the fill has reached at [positionMs]: the last word that has started, as its character
 * range and how far through it the singer is (0 to 1). Null before the first word.
 */
internal fun fillPoint(spans: List<Triple<IntRange, Long, Long>>, positionMs: Long): Triple<Int, Int, Float>? {
    var hit: Triple<IntRange, Long, Long>? = null
    for (s in spans) { if (s.second <= positionMs) hit = s else break }
    val (range, start, end) = hit ?: return null
    val f = if (end <= start) 1f else ((positionMs - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    return Triple(range.first, range.last + 1, f)
}

@Composable
private fun BreathingDots(clock: StageClock, colors: StageColors, size: TextUnit) {
    Canvas(Modifier.fillMaxWidth().height((size.value * 1.2f).dp)) {
        val t = clock.time.floatValue
        val r = size.toPx() * 0.12f
        val gap = r * 3.4f
        val y = this.size.height / 2
        val startX = this.size.width / 2 - gap
        for (k in 0..2) {
            val phase = sin(t * 2.4f - k * 0.7f) * 0.5f + 0.5f
            drawCircle(colors.text.copy(alpha = 0.35f + 0.55f * phase), radius = r * (0.8f + 0.35f * phase), center = Offset(startX + k * gap, y))
        }
    }
}

/**
 * No synced lyrics (none found, plain text only, or still looking): the cover, large, with soft
 * rings breathing out from it, the song, and what is up next.
 */
@Composable
private fun NoLyrics(song: SongInfo, upNext: SongInfo?, clock: StageClock, colors: StageColors, maxWidth: Dp, maxHeight: Dp) {
    // Room is kept for the title, up next and the controls along the bottom edge.
    val art = min(maxWidth.value * 0.42f, (maxHeight.value - 380f) / 1.2f).coerceAtLeast(120f).dp
    Column(
        Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(art * 1.2f)) {
            Canvas(Modifier.fillMaxSize()) {
                val t = clock.time.floatValue
                val base = art.toPx() / 2
                for (k in 0 until 4) {
                    // Each ring grows outward and fades, one after another.
                    val p = ((t * 0.18f + k / 4f) % 1f)
                    val radius = base * (1.03f + 0.17f * p)
                    drawCircle(
                        colors.text.copy(alpha = (1f - p) * if (colors.dark) 0.22f else 0.28f),
                        radius = radius, style = Stroke(width = (2f + 3f * (1f - p)).dp.toPx())
                    )
                }
            }
            StageArt(song, art, clock, colors, record = true)
        }
        Spacer(Modifier.height(20.dp))
        SongTitle(song, colors, Alignment.CenterHorizontally, titleSize = (art.value / 11f).coerceIn(22f, 40f).sp)
        if (upNext != null) {
            Spacer(Modifier.height(24.dp))
            UpNext(upNext, colors)
        }
    }
}

@Composable
private fun UpNext(song: SongInfo, colors: StageColors) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(colors.text.copy(alpha = 0.08f)).padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = squareCropFriendly(song.thumbnailUrl), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.text.copy(alpha = 0.1f))
        )
        Spacer(Modifier.width(10.dp))
        Text("Up next", color = colors.textDim, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(10.dp))
        Text(
            "${song.title}  ·  ${song.artist}", color = colors.text, style = MaterialTheme.typography.bodyLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.dp)
        )
    }
}

@Composable
private fun NothingPlaying(colors: StageColors) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(140.dp).clip(RoundedCornerShape(18.dp)).background(colors.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = colors.textDim, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Nothing playing yet", color = colors.text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Play something and it takes the stage.", color = colors.textDim, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StageIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, colors: StageColors, size: Dp = 44.dp, onClick: () -> Unit) {
    Tip(label) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(size),
            colors = IconButtonDefaults.iconButtonColors(containerColor = colors.text.copy(alpha = 0.1f), contentColor = colors.text)
        ) { Icon(icon, label, Modifier.size(size * 0.5f)) }
    }
}

/** Progress, time and transport, along the bottom edge. */
@Composable
private fun StageTransport(
    actions: StageActions,
    clock: StageClock,
    duration: Long,
    isPlaying: Boolean,
    upNext: SongInfo?,
    colors: StageColors,
    modifier: Modifier,
) {
    Column(
        modifier
            .background(Brush.verticalGradient(listOf(Color.Transparent, colors.base.copy(alpha = 0.7f))))
            .padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 22.dp)
    ) {
        // Drawn from the clock: moves every frame, recomposes nothing.
        Box(
            Modifier.fillMaxWidth().height(14.dp)
                .pointerInput(duration) {
                    detectTapOrDrag { x -> if (duration > 0) actions.seek((x / size.width * duration).toLong().coerceIn(0, duration)) }
                }
                .drawBehind {
                    val y = size.height / 2
                    val h = 4.dp.toPx()
                    val frac = if (duration > 0) (clock.position.longValue.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    drawRoundRect(colors.text.copy(alpha = 0.22f), topLeft = Offset(0f, y - h / 2), size = Size(size.width, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h))
                    drawRoundRect(colors.text, topLeft = Offset(0f, y - h / 2), size = Size(size.width * frac, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h))
                }
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth()) {
            ElapsedText(clock, colors, Modifier.align(Alignment.CenterStart))
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StageIconButton(Icons.Default.SkipPrevious, "Previous (Ctrl+Left)", colors, onClick = actions.previous)
                Tip(if (isPlaying) "Pause (Space)" else "Play (Space)") {
                    IconButton(
                        onClick = actions.playPause,
                        modifier = Modifier.size(60.dp),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = colors.text, contentColor = colors.base)
                    ) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play", Modifier.size(32.dp)) }
                }
                StageIconButton(Icons.Default.SkipNext, "Next (Ctrl+Right)", colors, onClick = actions.next)
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                if (upNext != null) {
                    Text(
                        "Next: ${upNext.title}", color = colors.textDim, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Text(if (duration > 0) formatTime(duration) else "", color = colors.textDim, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Recomposes once a second, not once a frame. */
@Composable
private fun ElapsedText(clock: StageClock, colors: StageColors, modifier: Modifier) {
    val seconds by remember { derivedStateOf { clock.position.longValue / 1000 } }
    Text(formatTime(seconds * 1000), color = colors.textDim, style = MaterialTheme.typography.labelLarge, modifier = modifier)
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapOrDrag(onX: (Float) -> Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            val c = e.changes.firstOrNull() ?: continue
            if (c.pressed) { onX(c.position.x); c.consume() }
        }
    }
}
