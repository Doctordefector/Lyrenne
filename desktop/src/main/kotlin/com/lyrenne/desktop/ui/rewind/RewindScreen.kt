package com.lyrenne.desktop.ui.rewind

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.rewind.RewindCardKind
import com.lyrenne.desktop.rewind.RewindPeriod
import com.lyrenne.desktop.rewind.RewindReport
import com.lyrenne.desktop.rewind.RewindRepository
import com.lyrenne.desktop.rewind.RewindText
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.ui.components.Tip
import com.lyrenne.desktop.ui.stage.RewindMode
import com.lyrenne.desktop.ui.stage.StageColors
import com.lyrenne.desktop.ui.stage.StagePalette
import com.lyrenne.desktop.ui.theme.selectedChipColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Lyrenne Rewind: a listening recap for a period, as story cards stepped through with a click or
 * the arrow keys. Built from the local play history only (RewindRepository); nothing is sent
 * anywhere.
 */
@Composable
fun RewindScreen(player: DesktopPlayer, period: RewindPeriod, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val report by produceState<RewindReport?>(null, period) {
        value = null
        value = withContext(Dispatchers.IO) {
            try { RewindRepository.load(period) } catch (e: Exception) {
                Timber.w("Rewind failed to load: ${e.message}"); null
            }
        }
    }
    RewindView(period, report, onClose, play = { player.playQueue(it) }, modifier = modifier)
}

/** Rewind from a loaded report (null while loading), so it renders without a database in tests. */
@Composable
internal fun RewindView(
    period: RewindPeriod,
    r: RewindReport?,
    onClose: () -> Unit,
    play: suspend (List<com.lyrenne.desktop.playback.SongInfo>) -> Boolean,
    modifier: Modifier = Modifier,
    initialIndex: Int = 0,
    preloaded: Map<String, ImageBitmap>? = null,
) {
    val images = preloaded ?: rememberRewindImages(r)
    val glow = rememberGlow(r)
    var index by remember(r) { mutableIntStateOf(initialIndex) }
    val cards = remember(r) { r?.let { RewindText.cards(it) }.orEmpty() }

    // Keys come in through RewindMode.step (see handleStageRewindKey).
    LaunchedEffect(RewindMode.step) {
        val s = RewindMode.step
        if (s != 0 && cards.isNotEmpty()) index = (index + s).coerceIn(0, cards.lastIndex)
        RewindMode.step = 0
    }

    val colors = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxSize()
            .background(colors.background)
            // A full-window layer: nothing underneath should react to clicks or hovers.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            val wide = maxWidth >= 980.dp
            Column(Modifier.fillMaxSize()) {
                // ---- Header: name, periods, close
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, null, tint = colors.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Rewind", style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
                    Spacer(Modifier.weight(1f))
                    Tip("Close (Esc)") {
                        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close Rewind") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                PeriodChips(period)
                Spacer(Modifier.height(16.dp))

                when {
                    r == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    cards.isEmpty() -> EmptyRewind(period, Modifier.weight(1f).fillMaxWidth())
                    else -> {
                        val content: @Composable (Modifier) -> Unit = { m ->
                            Story(cards, index, { index = it }, r, images, glow, m)
                        }
                        if (wide) Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            content(Modifier.fillMaxHeight())
                            Spacer(Modifier.width(32.dp))
                            Actions(play, r, cards[index], images, glow, vertical = true, modifier = Modifier.weight(1f))
                        } else {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { content(Modifier.fillMaxHeight()) }
                            Spacer(Modifier.height(12.dp))
                            Actions(play, r, cards[index], images, glow, vertical = false, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodChips(current: RewindPeriod) {
    val today = remember { LocalDate.now() }
    val presets = remember(today) {
        listOf(
            RewindPeriod.thisWeek(today), RewindPeriod.thisMonth(today), RewindPeriod.thisYear(today),
            RewindPeriod.year(today.year - 1), RewindPeriod.allTime(),
        )
    }
    var picking by remember { mutableStateOf(false) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        // Same days, same chip: the December offer opens "2026", which is This year.
        fun same(p: RewindPeriod) = p.start == current.start && p.endExclusive == current.endExclusive
        presets.forEach { p ->
            FilterChip(
                selected = same(p), onClick = { RewindMode.open(p) },
                label = { Text(p.label) }, colors = selectedChipColors(), shape = RoundedCornerShape(50)
            )
        }
        val custom = presets.none(::same)
        FilterChip(
            selected = custom, onClick = { picking = true },
            label = { Text(if (custom) current.label else "Custom") },
            leadingIcon = { Icon(Icons.Default.DateRange, null, Modifier.size(18.dp)) },
            colors = selectedChipColors(), shape = RoundedCornerShape(50)
        )
    }
    if (picking) CustomPeriodDialog(onDismiss = { picking = false }) { from, to ->
        picking = false
        RewindMode.open(RewindPeriod.custom(from, to))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomPeriodDialog(onDismiss: () -> Unit, onPick: (LocalDate, LocalDate) -> Unit) {
    val state = rememberDateRangePickerState()
    fun day(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val from = day(state.selectedStartDateMillis)
            TextButton(onClick = { from?.let { onPick(it, day(state.selectedEndDateMillis) ?: it) } }, enabled = from != null) { Text("Show") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DateRangePicker(
            state = state,
            title = { Text("Pick the days to rewind", Modifier.padding(start = 24.dp, top = 16.dp)) },
            showModeToggle = false,
            modifier = Modifier.height(480.dp)
        )
    }
}

@Composable
private fun EmptyRewind(period: RewindPeriod, modifier: Modifier) {
    val paused = PreferencesManager.preferences.collectAsState().value.pauseListenHistory
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 460.dp)) {
            Icon(Icons.Default.History, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("Nothing to rewind yet", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                if (paused) "Listen history is paused in Settings, so plays are not being counted. Turn it back on and play some music, then come back."
                else "Rewind is made from what you play in Lyrenne on this computer, and there is nothing for ${period.lowerLabel} yet. Play a few songs, or pick a longer period.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (period.kind != RewindPeriod.Kind.ALL_TIME) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { RewindMode.open(RewindPeriod.allTime()) }) { Text("Try all time") }
            }
        }
    }
}

/** The card, at 9:16, with a progress bar per card and arrows at its sides. */
@Composable
private fun Story(
    cards: List<RewindCardKind>,
    index: Int,
    onIndex: (Int) -> Unit,
    report: RewindReport,
    images: Map<String, ImageBitmap>,
    glow: List<Color>,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Tip("Previous (Left arrow)") {
            IconButton(onClick = { onIndex((index - 1).coerceAtLeast(0)) }, enabled = index > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous card")
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.fillMaxHeight().aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)) {
            Row(Modifier.fillMaxWidth().height(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                cards.indices.forEach { i ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                            .background(if (i <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val shape = RoundedCornerShape(20.dp)
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    val fwd = targetState > initialState
                    (slideInHorizontally(tween(420)) { if (fwd) it / 6 else -it / 6 } + fadeIn(tween(320)) + scaleIn(tween(420), initialScale = 0.97f))
                        .togetherWith(slideOutHorizontally(tween(420)) { if (fwd) -it / 6 else it / 6 } + fadeOut(tween(240)))
                },
                modifier = Modifier.weight(1f).fillMaxWidth().shadow(18.dp, shape).clip(shape),
                label = "rewindCard"
            ) { i ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val w = constraints.maxWidth.toFloat()
                    RewindCard(
                        cards[i], report, images, glow, animate = true,
                        modifier = Modifier.fillMaxSize().pointerInput(i, w) {
                            // The left third goes back, the rest goes forward, as stories do.
                            detectTapGesturesCompat { x ->
                                if (x < w / 3) onIndex((i - 1).coerceAtLeast(0)) else onIndex((i + 1).coerceAtMost(cards.lastIndex))
                            }
                        }
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Tip("Next (Right arrow)") {
            IconButton(onClick = { onIndex((index + 1).coerceAtMost(cards.lastIndex)) }, enabled = index < cards.lastIndex) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next card")
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGesturesCompat(onTap: (Float) -> Unit) =
    detectTapGestures(onTap = { offset -> onTap(offset.x) })

/** Save as image and play the top songs: beside the card when wide, under it when narrow. */
@Composable
private fun Actions(
    play: suspend (List<com.lyrenne.desktop.playback.SongInfo>) -> Boolean,
    report: RewindReport,
    card: RewindCardKind,
    images: Map<String, ImageBitmap>,
    glow: List<Color>,
    vertical: Boolean,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var saveMenu by remember { mutableStateOf(false) }
    LaunchedEffect(message) { if (message != null) { delay(5000); message = null } }

    val save: (RewindExport.Format) -> Unit = { format ->
        saveMenu = false
        val file = RewindExport.chooseFile(report, card, format)
        if (file != null) scope.launch {
            message = try {
                RewindExport.write(file, card, report, images, glow, format)
                "Saved ${file.name}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Rewind export failed: ${e.message}")
                "Could not save the image: ${e.message ?: "unknown error"}"
            }
        }
    }
    val buttons: @Composable () -> Unit = {
        Box {
            FilledTonalButton(onClick = { saveMenu = true }) {
                Icon(Icons.Default.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save card as image")
            }
            DropdownMenu(saveMenu, onDismissRequest = { saveMenu = false }) {
                DropdownMenuItem(text = { Text("Story, 1080 × 1920") }, onClick = { save(RewindExport.Format.STORY) })
                DropdownMenuItem(text = { Text("Square, 1080 × 1080") }, onClick = { save(RewindExport.Format.SQUARE) })
            }
        }
        Button(
            onClick = {
                val queue = RewindRepository.queueOf(report)
                scope.launch {
                    message = if (play(queue)) "Playing your top ${queue.size}" else "Could not start playback"
                }
            },
            enabled = report.topSongs.isNotEmpty()
        ) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (report.topSongs.size >= 50) "Play your top 50" else "Play your top songs")
        }
    }
    if (vertical) Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "${report.period.label}: ${RewindText.minutesInContext(report.minutes).removeSuffix(".")}",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Step through with the arrow keys, or click the card. Save any card to share it.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        buttons()
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
    } else Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/**
 * Pictures the cards use, loaded once per report into bitmaps. Cards draw these directly, so the
 * exported PNG is complete in a single frame (an AsyncImage there would still be loading).
 */
@Composable
internal fun rememberRewindImages(report: RewindReport?): Map<String, ImageBitmap> {
    val images = remember(report) { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(report) {
        report ?: return@LaunchedEffect
        val urls = (report.topSongs.take(5).map { it.thumbnailUrl } + report.topArtists.take(5).map { it.thumbnailUrl } +
            report.topAlbums.take(5).map { it.thumbnailUrl } + listOf(report.discovery?.artist?.thumbnailUrl, report.replay?.song?.thumbnailUrl))
            .filterNotNull().distinct()
        for (url in urls) {
            loadBitmap(url)?.let { images[url] = it }
        }
    }
    return images
}

internal suspend fun loadBitmap(url: String): ImageBitmap? = try {
    val context = PlatformContext.INSTANCE
    val result = SingletonImageLoader.get(context)
        .execute(ImageRequest.Builder(context).data(url).size(512).build()) as? SuccessResult
    result?.let { withContext(Dispatchers.Default) { it.image.toBitmap().asComposeImageBitmap() } }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

@Composable
private fun rememberGlow(report: RewindReport?): List<Color> {
    val url = report?.topSongs?.firstOrNull()?.thumbnailUrl
    val hues by produceState(emptyList<Color>(), url) { value = url?.let { StagePalette.of(it) }.orEmpty() }
    return remember(hues) { glowFrom(hues) }
}

/** The cover's hues as glows dim enough to keep white text on the cards above 4.5:1. */
internal fun glowFrom(hues: List<Color>): List<Color> = hues.map { StageColors.tone(it, 0.5f..0.9f, 0.5f) }

/** Writes cards to PNG with an offscreen Compose scene, at a size made for sharing. */
@OptIn(ExperimentalComposeUiApi::class)
object RewindExport {
    enum class Format(val width: Int, val height: Int, val label: String) {
        STORY(1080, 1920, "story"), SQUARE(1080, 1080, "square")
    }

    /** Density 3: a card is laid out at 360 dp wide, the size it was designed at. */
    private const val DENSITY = 3f

    fun render(card: RewindCardKind, report: RewindReport, images: Map<String, ImageBitmap>, glow: List<Color>, format: Format): ByteArray {
        val scene = ImageComposeScene(format.width, format.height, Density(DENSITY)) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                RewindCard(card, report, images, glow, animate = false, modifier = Modifier.fillMaxSize())
            }
        }
        try {
            scene.render(0)
            val image = scene.render(16_000_000)
            return image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
    }

    suspend fun write(file: File, card: RewindCardKind, report: RewindReport, images: Map<String, ImageBitmap>, glow: List<Color>, format: Format) {
        val bytes = withContext(Dispatchers.Default) { render(card, report, images, glow, format) }
        withContext(Dispatchers.IO) { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
    }

    /** A save dialog opened in the Pictures folder with a descriptive name. Null if cancelled. */
    fun chooseFile(report: RewindReport, card: RewindCardKind, format: Format): File? {
        val name = "Lyrenne Rewind ${report.period.label} ${card.name.lowercase().replace('_', ' ')} ${format.label}"
            .replace(Regex("[\\\\/:*?\"<>|]"), "-") + ".png"
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Save card as image", java.awt.FileDialog.SAVE)
        val pictures = File(System.getProperty("user.home"), "Pictures")
        if (pictures.isDirectory) dialog.directory = pictures.absolutePath
        dialog.file = name
        dialog.isVisible = true
        val chosen = dialog.file ?: return null
        val file = File(dialog.directory ?: "", chosen)
        return if (file.name.endsWith(".png", ignoreCase = true)) file else File(file.parentFile, file.name + ".png")
    }
}
