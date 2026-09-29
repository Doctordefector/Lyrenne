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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
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
import com.lyrenne.desktop.media.suppressMediaKeys
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.knownDurationMs
import com.lyrenne.desktop.settings.LyricsPosition
import com.lyrenne.desktop.settings.PreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it }
    ) {
        Surface(
            modifier = modifier.width(350.dp).fillMaxHeight(),
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
                                if (lyricsState.isManual) "$source (chosen)" else source,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(end = 4.dp).widthIn(max = 140.dp)
                            )
                        }
                    }
                    if (stateIsForThisSong && lyricsState.isManual && currentSong != null) {
                        IconButton(
                            onClick = {
                                LyricsManager.resetToAutomatic(
                                    songId = currentSong.id,
                                    title = currentSong.title,
                                    artist = currentSong.artist,
                                    durationSec = ((playerState.duration.takeIf { it > 0 }
                                        ?: currentSong.knownDurationMs()) / 1000).toInt(),
                                    album = currentSong.album
                                )
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.RestartAlt, "Go back to automatic lyrics", modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(
                        onClick = { searchFor = currentSong },
                        enabled = currentSong != null,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Search, "Search for lyrics", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Close", modifier = Modifier.size(20.dp))
                    }
                }

                HorizontalDivider()

                // Content
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    val prefs by PreferencesManager.preferences.collectAsState()
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
                            }
                        }
                        lyricsState.lines != null -> {
                            SyncedLyricsView(
                                lines = lyricsState.lines!!,
                                positionMs = playerState.position,
                                isPlaying = playerState.isPlaying,
                                speed = prefs.playbackSpeed,
                                textSize = prefs.lyricsTextSize,
                                textAlign = textAlign,
                                onSeek = if (prefs.lyricsClickToSeek) {
                                    { timestampMs -> player.seekTo(timestampMs) }
                                } else null
                            )
                        }
                        lyricsState.lyrics != null -> {
                            PlainLyricsView(
                                lyrics = lyricsState.lyrics!!,
                                textSize = prefs.lyricsTextSize,
                                textAlign = textAlign
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
    onSeek: ((Long) -> Unit)? = null
) {
    val currentIndex = remember(positionMs, lines) { currentLineIndex(lines, positionMs) }

    val listState = rememberLazyListState()
    val accentColor = MaterialTheme.colorScheme.primary
    val dimColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val upcomingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

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
                        dimColor = dimColor,
                        upcomingColor = upcomingColor,
                        baseSize = textSize,
                        textAlign = textAlign,
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
                            dimColor = dimColor,
                            upcomingColor = upcomingColor,
                            baseSize = (textSize - 3f).coerceAtLeast(10f),
                            textAlign = textAlign,
                            isBackground = true,
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
    dimColor: Color,
    upcomingColor: Color,
    baseSize: Float = 16f,
    textAlign: TextAlign = TextAlign.Start,
    isBackground: Boolean = false,
) {
    // Animate scale: current line pops up to 1.05x like Android Metrolist
    val scale by animateFloatAsState(
        targetValue = if (isCurrent) 1.05f else 1f,
        animationSpec = tween(durationMillis = 400)
    )

    // Animate opacity: current = full, past = dimmed, upcoming = slightly dimmed
    val alpha by animateFloatAsState(
        targetValue = when {
            isCurrent -> if (isBackground) 0.85f else 1f
            isPast -> 0.45f
            else -> 0.7f
        },
        animationSpec = tween(durationMillis = 400)
    )

    val words = line.words
    // Word timing lights the current line up word by word. Every other line, and any line
    // without word timing, is coloured as a whole exactly as before.
    val wordByWord = isCurrent && words != null

    val targetColor = when {
        isCurrent -> accentColor
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
            this.alpha = alpha
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
    textAlign: TextAlign = TextAlign.Start
) {
    LazyColumn {
        item {
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
