package com.lyrenne.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyrenne.desktop.lyrics.LyricLine
import com.lyrenne.desktop.lyrics.LyricWord
import com.lyrenne.desktop.lyrics.LyricsCandidate
import com.lyrenne.desktop.lyrics.LyricsManager
import com.lyrenne.desktop.lyrics.LyricsOffsets
import com.lyrenne.desktop.lyrics.LyricsParser
import com.lyrenne.desktop.lyrics.Romanizer
import com.lyrenne.desktop.media.suppressMediaKeys
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.knownDurationMs
import com.lyrenne.desktop.settings.LyricsPosition
import com.lyrenne.desktop.settings.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToLong

/**
 * How long a lyrics fetch waits for the track's length before going without.
 *
 * LrcLib and KuGou match on duration, and a lookup sent without one falls back to the loosest
 * match or finds nothing at all. Songs restored from the queue, and anything whose metadata had
 * no length, only learn theirs once VLC has parsed the stream, which is usually well under this.
 */
private const val DURATION_WAIT_MS = 4_000L

@Composable
fun LyricsPanel(
    player: DesktopPlayer,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playerState by player.state.collectAsState()
    val lyricsState by LyricsManager.state.collectAsState()
    // The song the search dialog was opened for. Held rather than read from the player, so a
    // track change while the dialog is open cannot file the pick under the next song.
    var searchFor by remember { mutableStateOf<com.lyrenne.desktop.playback.SongInfo?>(null) }
    // Same reason: the editor saves to the song it was opened for, with the text it opened with.
    var editFor by remember { mutableStateOf<Pair<com.lyrenne.desktop.playback.SongInfo, String>?>(null) }
    val prefs by PreferencesManager.preferences.collectAsState()

    // Fetch lyrics when song changes
    LaunchedEffect(playerState.currentSong?.id) {
        val song = playerState.currentSong ?: return@LaunchedEffect
        // The live duration is only trusted once it belongs to this song. Waiting on it, rather
        // than reading whatever is in the state right now, is what stops the lookup going out
        // with "-1" for every track restored from the queue or picked from the library.
        val durationMs = withTimeoutOrNull(DURATION_WAIT_MS) {
            player.state.first { it.currentSong?.id == song.id && it.duration > 0 }.duration
        } ?: song.knownDurationMs()
        LyricsManager.fetchLyrics(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            durationSec = (durationMs / 1000).toInt(),
            album = song.album
        )
    }

    val currentSong = playerState.currentSong
    // Lyrics from the previous track must not show while this one's are being looked up.
    val stateIsForThisSong = currentSong != null && lyricsState.songId == currentSong.id

    // Per song, read once when the song changes and written on every nudge.
    var offsetMs by remember(currentSong?.id) {
        mutableLongStateOf(currentSong?.let { LyricsOffsets.get(it.id) } ?: 0L)
    }
    fun nudgeOffset(newOffset: Long) {
        val song = currentSong ?: return
        offsetMs = newOffset.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        LyricsOffsets.set(song.id, offsetMs)
    }

    // Every line's text, background vocals included, in the order shown.
    val shownTexts = remember(lyricsState.lines, lyricsState.lyrics) {
        lyricsState.lines?.flatMap { line -> listOf(line.text) + line.background.map { it.text } }
            ?: lyricsState.lyrics?.lines()
            ?: emptyList()
    }
    val hasForeignText = remember(shownTexts) { shownTexts.any(Romanizer::needsRomanization) }
    // Worked out off the UI thread: the first Japanese song loads a dictionary.
    val romanized by produceState<Map<String, String>>(emptyMap(), shownTexts, prefs.lyricsRomanize, hasForeignText) {
        value = emptyMap()
        if (prefs.lyricsRomanize && hasForeignText) {
            value = withContext(Dispatchers.Default) { Romanizer.romanize(shownTexts) }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it }
    ) {
        Surface(
            modifier = modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Lyrics",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (stateIsForThisSong) {
                        lyricsState.source?.let { source ->
                            Text(
                                // The provider's name alone ("LrcLib") explained nothing.
                                when {
                                    source == LyricsManager.EDITED_SOURCE -> "edited by you"
                                    lyricsState.isManual -> "via $source (chosen)"
                                    else -> "via $source"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(end = 4.dp).widthIn(max = 140.dp)
                            )
                        }
                    }
                    if (stateIsForThisSong && lyricsState.lines != null) {
                        var timingOpen by remember { mutableStateOf(false) }
                        Box {
                            Tip("Lyrics timing") {
                                IconButton(onClick = { timingOpen = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Default.AvTimer,
                                        "Lyrics timing",
                                        // Tinted while moved, so a forgotten offset is noticeable.
                                        tint = if (offsetMs != 0L) MaterialTheme.colorScheme.primary
                                        else LocalContentColor.current,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            LyricsTimingMenu(
                                expanded = timingOpen,
                                offsetMs = offsetMs,
                                onChange = ::nudgeOffset,
                                onDismiss = { timingOpen = false },
                            )
                        }
                    }
                    Tip("Search for lyrics") {
                        IconButton(
                            onClick = { searchFor = currentSong },
                            enabled = currentSong != null,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Search, "Search for lyrics", modifier = Modifier.size(20.dp))
                        }
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        Tip("More") {
                            IconButton(
                                onClick = { menuOpen = true },
                                enabled = currentSong != null,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.MoreVert, "More", modifier = Modifier.size(20.dp))
                            }
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            val haveLyrics = stateIsForThisSong && lyricsState.lyrics != null
                            DropdownMenuItem(
                                text = { Text(if (haveLyrics) "Edit lyrics" else "Write lyrics") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = {
                                    menuOpen = false
                                    currentSong?.let { editFor = it to (if (haveLyrics) lyricsState.lyrics!! else "") }
                                }
                            )
                            if (stateIsForThisSong && hasForeignText) {
                                DropdownMenuItem(
                                    text = { Text("Show romanization") },
                                    leadingIcon = { Icon(Icons.Default.Translate, null) },
                                    trailingIcon = {
                                        Checkbox(checked = prefs.lyricsRomanize, onCheckedChange = null)
                                    },
                                    onClick = { PreferencesManager.setLyricsRomanize(!prefs.lyricsRomanize) }
                                )
                            }
                            if (stateIsForThisSong && lyricsState.isManual && currentSong != null) {
                                DropdownMenuItem(
                                    text = { Text("Go back to automatic lyrics") },
                                    leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                                    onClick = {
                                        menuOpen = false
                                        LyricsManager.resetToAutomatic(
                                            songId = currentSong.id,
                                            title = currentSong.title,
                                            artist = currentSong.artist,
                                            durationSec = ((playerState.duration.takeIf { it > 0 }
                                                ?: currentSong.knownDurationMs()) / 1000).toInt(),
                                            album = currentSong.album
                                        )
                                    }
                                )
                            }
                        }
                    }
                    Tip("Close") {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Close", modifier = Modifier.size(20.dp))
                        }
                    }
                }

                HorizontalDivider()

                // Content
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    val textAlign = when (prefs.lyricsPosition) {
                        LyricsPosition.LEFT -> TextAlign.Start
                        LyricsPosition.CENTER -> TextAlign.Center
                        LyricsPosition.RIGHT -> TextAlign.End
                    }
                    when {
                        currentSong == null -> {
                            Text(
                                "Nothing playing",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        !stateIsForThisSong || lyricsState.isLoading -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                        lyricsState.error != null -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.align(Alignment.Center)
                            ) {
                                Text(
                                    lyricsState.error!!,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick = { searchFor = currentSong }) {
                                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Search for lyrics")
                                }
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = { editFor = currentSong to "" }) {
                                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Write your own")
                                }
                            }
                        }
                        lyricsState.lines != null -> {
                            SyncedLyricsView(
                                lines = lyricsState.lines!!,
                                // Positive offset shows lyrics sooner, so the lyrics run ahead of
                                // the song by that much, and a click seeks back by it.
                                positionMs = playerState.position + offsetMs,
                                isPlaying = playerState.isPlaying,
                                speed = prefs.playbackSpeed,
                                textSize = prefs.lyricsTextSize,
                                textAlign = textAlign,
                                romanized = romanized,
                                onSeek = if (prefs.lyricsClickToSeek) {
                                    { timestampMs -> player.seekTo((timestampMs - offsetMs).coerceAtLeast(0)) }
                                } else null
                            )
                        }
                        lyricsState.lyrics != null -> {
                            PlainLyricsView(
                                lyrics = lyricsState.lyrics!!,
                                textSize = prefs.lyricsTextSize,
                                textAlign = textAlign,
                                romanized = romanized,
                            )
                        }
                        else -> {
                            Text(
                                "No lyrics available",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }

    searchFor?.let { song ->
        LyricsSearchDialog(
            songId = song.id,
            initialTitle = song.title,
            initialArtist = song.artist,
            onDismiss = { searchFor = null },
            onPick = { candidate ->
                LyricsManager.choose(song.id, candidate)
                searchFor = null
            }
        )
    }

    editFor?.let { (song, text) ->
        LyricsEditDialog(
            initialText = text,
            onDismiss = { editFor = null },
            onSave = { edited ->
                LyricsManager.saveEdited(song.id, edited)
                editFor = null
            }
        )
    }
}

/** How far the timing control lets lyrics move, either way. Further is a wrong match, not a lag. */
private const val MAX_OFFSET_MS = 60_000L

/**
 * Moves this song's lyrics earlier or later, for lyrics that are right but run ahead of or behind
 * the singer. Worded as earlier and later rather than as a signed number: which way "+" goes is
 * a convention nobody can guess.
 */
@Composable
private fun LyricsTimingMenu(
    expanded: Boolean,
    offsetMs: Long,
    onChange: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentOffset by rememberUpdatedState(offsetMs)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(260.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Lyrics timing", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                describeOffset(offsetMs),
                style = MaterialTheme.typography.headlineSmall,
                color = if (offsetMs == 0L) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                // A wheel notch is a tenth of a second, the same as the small buttons; up is earlier.
                modifier = Modifier.wheelStep(
                    value = { currentOffset.toFloat() },
                    step = 100f,
                    range = -MAX_OFFSET_MS.toFloat()..MAX_OFFSET_MS.toFloat(),
                ) { onChange((it / 100).roundToLong() * 100) }
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Earlier", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                OutlinedButton(onClick = { onChange(offsetMs + 100) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("0.1 s") }
                OutlinedButton(onClick = { onChange(offsetMs + 500) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("0.5 s") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Later", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                OutlinedButton(onClick = { onChange(offsetMs - 100) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("0.1 s") }
                OutlinedButton(onClick = { onChange(offsetMs - 500) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("0.5 s") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Lyrics behind the singer? Show them earlier. Saved for this song.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { onChange(0) }, enabled = offsetMs != 0L) { Text("Reset") }
        }
    }
}

internal fun describeOffset(offsetMs: Long): String {
    if (offsetMs == 0L) return "On time"
    val seconds = "%.1f".format(java.util.Locale.ROOT, kotlin.math.abs(offsetMs) / 1000.0)
    return if (offsetMs > 0) "$seconds s earlier" else "$seconds s later"
}

/**
 * The raw lyrics, editable. Takes LRC as well as plain text, so timestamps can be fixed line by
 * line, and a whole `.lrc` file can be loaded in. Saved lyrics are kept for this song like a pick
 * from the search, and "Go back to automatic lyrics" undoes them.
 */
@Composable
private fun LyricsEditDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    val synced = remember(text) { LyricsParser.parse(text) != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialText.isEmpty()) "Write lyrics" else "Edit lyrics") },
        text = {
            Column(modifier = Modifier.width(520.dp)) {
                Text(
                    "Plain text, or LRC with a time on each line, like [01:23.45]A line. Fixing one wrong time is usually enough; the timing button moves them all.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().height(360.dp).suppressMediaKeys()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        NativeFileDialog.open("Open lyrics file", "lrc")?.let { file ->
                            runCatching { file.readText() }.onSuccess { text = it }
                        }
                    }) { Text("Open .lrc file") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (text.isBlank()) "" else if (synced) "Synced" else "Plain text",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank() && text != initialText) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Index of the line being sung at [positionMs], or -1 before the first one starts. */
internal fun currentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var idx = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= positionMs) idx = i else break
    }
    return idx
}

/**
 * The playback position, advanced every frame between the player's own updates.
 *
 * The player reports position about five times a second, which is fine for choosing a line and
 * visibly steppy for lighting up words one at a time. Between reports this extrapolates from the
 * last one at the current speed, and snaps back to each new report as it arrives, so it cannot
 * drift. It is capped at one report interval ahead so a stalled stream does not run away.
 */
@Composable
private fun rememberSmoothPosition(reportedMs: Long, isPlaying: Boolean, speed: Float): State<Long> {
    val smooth = remember { mutableLongStateOf(reportedMs) }
    LaunchedEffect(reportedMs, isPlaying, speed) {
        smooth.longValue = reportedMs
        if (!isPlaying) return@LaunchedEffect
        val anchor = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val elapsedMs = ((now - anchor) / 1_000_000.0 * speed).toLong()
                smooth.longValue = reportedMs + elapsedMs.coerceIn(0L, 1_000L)
            }
        }
    }
    return smooth
}

@Composable
private fun SyncedLyricsView(
    lines: List<LyricLine>,
    positionMs: Long,
    isPlaying: Boolean,
    speed: Float,
    textSize: Float = 16f,
    textAlign: TextAlign = TextAlign.Start,
    romanized: Map<String, String> = emptyMap(),
    onSeek: ((Long) -> Unit)? = null
) {
    val currentIndex = remember(positionMs, lines) { currentLineIndex(lines, positionMs) }

    val listState = rememberLazyListState()
    // Dimmed once, by colour only. A layer alpha on top used to multiply with these, leaving past
    // lines at 0.18 (1.5:1). 0.65 is the floor that keeps 4.5:1 on the light page (5.1:1).
    val accentColor = MaterialTheme.colorScheme.primary
    val currentColor = MaterialTheme.colorScheme.onSurface
    val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    val upcomingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)

    // Smooth auto-scroll: keep the current line a little below the top. Item 0 is the spacer.
    LaunchedEffect(currentIndex) {
        if (currentIndex > 0) {
            listState.animateScrollToItem(
                index = currentIndex.coerceAtLeast(0),
                scrollOffset = 0
            )
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Top padding so first lines appear centered
        item { Spacer(Modifier.height(60.dp)) }

        items(lines.size) { index ->
            val line = lines[index]
            val isCurrent = index == currentIndex
            val isPast = index < currentIndex

            if (line.text.isNotBlank()) {
                val onClick = onSeek?.let { seek -> { seek(line.timeMs) } }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                ) {
                    LyricsLine(
                        line = line,
                        isCurrent = isCurrent,
                        isPast = isPast,
                        positionMs = positionMs,
                        isPlaying = isPlaying,
                        speed = speed,
                        accentColor = accentColor,
                        currentColor = currentColor,
                        dimColor = dimColor,
                        upcomingColor = upcomingColor,
                        baseSize = textSize,
                        textAlign = textAlign,
                        romanized = romanized[line.text],
                    )
                    line.background.forEach { bg ->
                        LyricsLine(
                            line = bg,
                            isCurrent = isCurrent,
                            isPast = isPast,
                            positionMs = positionMs,
                            isPlaying = isPlaying,
                            speed = speed,
                            accentColor = accentColor,
                            currentColor = currentColor,
                            dimColor = dimColor,
                            upcomingColor = upcomingColor,
                            baseSize = (textSize - 3f).coerceAtLeast(10f),
                            textAlign = textAlign,
                            isBackground = true,
                            romanized = romanized[bg.text],
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(20.dp))
            }
        }
        // Bottom padding for scroll space
        item { Spacer(Modifier.height(300.dp)) }
    }
}

@Composable
private fun LyricsLine(
    line: LyricLine,
    isCurrent: Boolean,
    isPast: Boolean,
    positionMs: Long,
    isPlaying: Boolean,
    speed: Float,
    accentColor: Color,
    currentColor: Color,
    dimColor: Color,
    upcomingColor: Color,
    baseSize: Float = 16f,
    textAlign: TextAlign = TextAlign.Start,
    isBackground: Boolean = false,
    romanized: String? = null,
) {
    // Animate scale: current line pops up to 1.05x like Android Metrolist
    val scale by animateFloatAsState(
        targetValue = if (isCurrent) 1.05f else 1f,
        animationSpec = tween(durationMillis = 400)
    )


    val words = line.words
    // Word timing lights the current line up word by word. Every other line, and any line
    // without word timing, is coloured as a whole exactly as before.
    val wordByWord = isCurrent && words != null

    val targetColor = when {
        isCurrent -> currentColor
        isPast -> dimColor
        else -> upcomingColor
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 400)
    )

    // Animate font weight via fontSize (Compose doesn't animate FontWeight directly)
    val fontSize by animateFloatAsState(
        targetValue = if (isCurrent) baseSize + 4f else baseSize,
        animationSpec = tween(durationMillis = 300)
    )

    val lineModifier = Modifier
        .fillMaxWidth()
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                when (textAlign) {
                    TextAlign.Center -> 0.5f
                    TextAlign.End -> 1f
                    else -> 0f
                },
                0.5f
            )
        }
        .padding(vertical = if (isBackground) 2.dp else 6.dp)

    if (wordByWord) {
        val smooth by rememberSmoothPosition(positionMs, isPlaying, speed)
        Text(
            text = highlightWords(line.text, words!!, smooth, accentColor, upcomingColor),
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            fontStyle = if (isBackground) FontStyle.Italic else FontStyle.Normal,
            lineHeight = (fontSize + 6).sp,
            textAlign = textAlign,
            modifier = lineModifier
        )
    } else {
        Text(
            text = line.text,
            fontSize = fontSize.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (isBackground) FontStyle.Italic else FontStyle.Normal,
            color = animatedColor,
            lineHeight = (fontSize + 6).sp,
            textAlign = textAlign,
            modifier = lineModifier
        )
    }
    // The reading sits under its line in the line's own colour, smaller and never bold, so the
    // script the song is sung in stays the thing being highlighted.
    if (romanized != null) {
        Text(
            text = romanized,
            fontSize = (baseSize - 2f).coerceAtLeast(10f).sp,
            fontStyle = FontStyle.Italic,
            color = animatedColor,
            lineHeight = (baseSize + 2f).sp,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        )
    }
}

/**
 * Colours [text] word by word: sung words in [sung], the word being sung blended in by how far
 * through it the position is, the rest in [unsung].
 *
 * Words are located in the line by searching forward from the previous one, not by position, so
 * the display text keeps its own spacing and punctuation. A word that cannot be found is skipped
 * rather than shifting every later word onto the wrong characters.
 */
internal fun highlightWords(
    text: String,
    words: List<LyricWord>,
    positionMs: Long,
    sung: Color,
    unsung: Color,
): AnnotatedString = buildAnnotatedString {
    append(text)
    addStyle(SpanStyle(color = unsung), 0, text.length)
    var cursor = 0
    for (word in words) {
        val needle = word.text.trim()
        if (needle.isEmpty()) continue
        val start = text.indexOf(needle, cursor)
        if (start < 0) continue
        val end = start + needle.length
        cursor = end
        val color = when {
            positionMs >= word.endMs -> sung
            positionMs < word.startMs -> continue
            else -> {
                val span = (word.endMs - word.startMs).coerceAtLeast(1)
                lerp(unsung, sung, ((positionMs - word.startMs).toFloat() / span).coerceIn(0f, 1f))
            }
        }
        addStyle(SpanStyle(color = color), start, end)
    }
}

@Composable
private fun PlainLyricsView(
    lyrics: String,
    textSize: Float = 16f,
    textAlign: TextAlign = TextAlign.Start,
    romanized: Map<String, String> = emptyMap(),
) {
    LazyColumn {
        if (romanized.isNotEmpty()) {
            // Line by line only when there is a reading to put under some of them.
            items(lyrics.lines()) { line -> Column {
                Text(
                    text = line,
                    fontSize = textSize.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = (textSize + 10).sp,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth()
                )
                romanized[line]?.let { reading ->
                    Text(
                        text = reading,
                        fontSize = (textSize - 2f).coerceAtLeast(10f).sp,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = (textSize + 4).sp,
                        textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                }
            } }
        } else item {
            Text(
                text = lyrics,
                fontSize = textSize.sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = (textSize + 10).sp,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item { Spacer(Modifier.height(200.dp)) }
    }
}

/**
 * Lets the user look lyrics up by hand, from every provider at once, and pick one.
 *
 * Title and artist start from the playing song and are editable, because the usual reason to be
 * here is that the song's own metadata ("Song (Official Video)", a featured artist in the title)
 * is what defeated the automatic lookup.
 */
@Composable
private fun LyricsSearchDialog(
    songId: String,
    initialTitle: String,
    initialArtist: String,
    onDismiss: () -> Unit,
    onPick: (LyricsCandidate) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<LyricsCandidate>?>(null) }
    val scope = rememberCoroutineScope()

    fun runSearch() {
        if (title.isBlank() || searching) return
        searching = true
        scope.launch {
            results = try {
                LyricsManager.search(songId, title.trim(), artist.trim())
            } finally {
                searching = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search for lyrics") },
        text = {
            Column(modifier = Modifier.width(460.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().suppressMediaKeys()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().suppressMediaKeys()
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = ::runSearch, enabled = title.isNotBlank() && !searching) {
                        Text("Search")
                    }
                    if (searching) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Asking every provider",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                val found = results
                when {
                    found == null -> {}
                    found.isEmpty() && !searching -> Text(
                        "Nothing found. Try a shorter title, or drop anything in brackets.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(found) { candidate ->
                            ListItem(
                                headlineContent = {
                                    Text(candidate.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    val kind = when {
                                        candidate.hasWordTiming -> "Word by word"
                                        candidate.isSynced -> "Synced"
                                        else -> "Plain text"
                                    }
                                    Text("${candidate.source} · $kind")
                                },
                                modifier = Modifier.clickable { onPick(candidate) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    // Search straight away with the song's own metadata; most of the time that is enough.
    LaunchedEffect(Unit) { runSearch() }
}
