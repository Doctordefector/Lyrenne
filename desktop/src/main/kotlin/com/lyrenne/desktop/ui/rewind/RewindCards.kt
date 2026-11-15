package com.lyrenne.desktop.ui.rewind

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyrenne.desktop.rewind.RankedAlbum
import com.lyrenne.desktop.rewind.RankedArtist
import com.lyrenne.desktop.rewind.RankedSong
import com.lyrenne.desktop.rewind.RewindCardKind
import com.lyrenne.desktop.rewind.RewindPeriod
import com.lyrenne.desktop.rewind.RewindReport
import com.lyrenne.desktop.rewind.RewindText
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * One Rewind card, drawn at any size. Everything is measured in units of a 360 dp wide card, so
 * the same composable is the card on screen, the 1080x1920 story PNG (360x640 at density 3) and
 * the 1080x1080 square (360x360, where [compact] trims lists to fit).
 *
 * The cards are their own artwork, dark and saturated in both app themes, because they are made
 * to be shared: white text on every background here measures at or above 7:1, and the accent
 * tints used for numbers at or above 4.5:1 (RewindCardContrastTest).
 *
 * Pictures arrive preloaded in [images] (keyed by URL) rather than through AsyncImage, so an
 * export renders complete in one frame.
 */
@Composable
fun RewindCard(
    kind: RewindCardKind,
    report: RewindReport,
    images: Map<String, ImageBitmap>,
    glow: List<Color>,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val style = CardStyles.getValue(kind)
    val appear = remember(kind, report) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(kind, report, animate) {
        if (animate) appear.animateTo(1f, tween(1300, easing = FastOutSlowInEasing))
    }
    BoxWithConstraints(
        modifier.drawBehind {
            drawRect(Brush.linearGradient(listOf(style.from, style.to), start = Offset.Zero, end = Offset(size.width * 0.4f, size.height)))
            // The cover's colour, as a glow in one corner: every listener's cards look like theirs.
            val g = glow.getOrNull(style.glowIndex % glow.size.coerceAtLeast(1))
            if (g != null) {
                val c = Offset(size.width * style.glowX, size.height * style.glowY)
                val r = size.width * 0.95f
                drawCircle(Brush.radialGradient(listOf(g.copy(alpha = 0.42f), Color.Transparent), center = c, radius = r), r, c)
            }
        }
    ) {
        val u = maxWidth.value / 360f
        val heightUnits = maxHeight.value / u
        // Material's body style carries a fixed 24 sp line height, which no card size should inherit.
        CompositionLocalProvider(LocalTextStyle provides TextStyle.Default) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = (26 * u).dp, vertical = ((if (heightUnits < 470f) 22 else 34) * u).dp)
        ) {
            with(CardScope(u, heightUnits, appear, style.accent)) {
                // The content sits in the middle of the card; the footer keeps to the bottom.
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                when (kind) {
                    RewindCardKind.INTRO -> Intro(report, images)
                    RewindCardKind.MINUTES -> Minutes(report)
                    RewindCardKind.TOP_SONGS -> TopList("Your top songs", report.topSongs.map { it.asRow() }, images, round = false)
                    RewindCardKind.TOP_ARTISTS -> TopList("Your top artists", report.topArtists.map { it.asRow() }, images, round = true)
                    RewindCardKind.TOP_ALBUMS -> TopList("Your top albums", report.topAlbums.map { it.asRow() }, images, round = false)
                    RewindCardKind.CLOCK -> Clock(report)
                    RewindCardKind.STREAK -> Streak(report)
                    RewindCardKind.DISCOVERY -> Discovery(report, images)
                    RewindCardKind.REPLAY -> Replay(report, images)
                    RewindCardKind.SUMMARY -> Summary(report, images)
                }
                }
                Footer(report.period)
            }
        }
        }
    }
}

internal class CardStyle(val from: Color, val to: Color, val accent: Color, val glowIndex: Int, val glowX: Float, val glowY: Float)

/** Deep, saturated backgrounds (luminance 0.13 or less) with a light accent tint each. */
internal val CardStyles: Map<RewindCardKind, CardStyle> = mapOf(
    RewindCardKind.INTRO to CardStyle(Color(0xFF1B0B3A), Color(0xFF4A0E5C), Color(0xFFF5C6FF), 0, 0.9f, 0.1f),
    RewindCardKind.MINUTES to CardStyle(Color(0xFF07203F), Color(0xFF173F7A), Color(0xFFA5D8FF), 1, 0.1f, 0.85f),
    RewindCardKind.TOP_SONGS to CardStyle(Color(0xFF2E0A12), Color(0xFF6E1A2B), Color(0xFFFFC9D2), 0, 0.85f, 0.15f),
    RewindCardKind.TOP_ARTISTS to CardStyle(Color(0xFF062A21), Color(0xFF0C5443), Color(0xFFA7F3D0), 1, 0.15f, 0.2f),
    RewindCardKind.TOP_ALBUMS to CardStyle(Color(0xFF241505), Color(0xFF5E370B), Color(0xFFF3D19C), 2, 0.9f, 0.8f),
    RewindCardKind.CLOCK to CardStyle(Color(0xFF0A1A3C), Color(0xFF1C3780), Color(0xFFFDE68A), 0, 0.5f, 0.45f),
    RewindCardKind.STREAK to CardStyle(Color(0xFF3A1206), Color(0xFF8A2E10), Color(0xFFFED7AA), 1, 0.85f, 0.2f),
    RewindCardKind.DISCOVERY to CardStyle(Color(0xFF1A1845), Color(0xFF4C1D95), Color(0xFFDDD6FE), 2, 0.2f, 0.3f),
    RewindCardKind.REPLAY to CardStyle(Color(0xFF45061F), Color(0xFF8C1546), Color(0xFFFBCFE8), 0, 0.8f, 0.75f),
    RewindCardKind.SUMMARY to CardStyle(Color(0xFF0F1420), Color(0xFF35104F), Color(0xFFFDE68A), 1, 0.85f, 0.1f),
)

private val White = Color.White
private val WhiteDim = Color.White.copy(alpha = 0.78f)

/** Sizes in 360-wide card units, and the shared entrance. */
internal class CardScope(
    val u: Float,
    val heightUnits: Float,
    val appear: Animatable<Float, *>,
    val accent: Color,
) {
    /** A square or shorter card: lists shrink to fit. */
    val compact: Boolean get() = heightUnits < 470f
    fun d(x: Number): Dp = (x.toFloat() * u).dp
    fun t(x: Number): TextUnit = (x.toFloat() * u).sp

    /** Element [k] fades and rises in after the ones before it. */
    fun Modifier.reveal(k: Int): Modifier = graphicsLayer {
        val p = (appear.value * 1.6f - k * 0.13f).coerceIn(0f, 1f)
        alpha = p
        translationY = (1f - p) * 26f * u * density
    }
}

private val numbers: NumberFormat get() = NumberFormat.getIntegerInstance(Locale.getDefault())
private val dayFormat = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val longDayFormat = DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH)

@Composable
private fun CardScope.Label(text: String, k: Int = 0) {
    Text(
        text.uppercase(), color = accent, fontSize = t(13), fontWeight = FontWeight.Bold,
        letterSpacing = t(1.6), modifier = Modifier.reveal(k)
    )
}

@Composable
private fun CardScope.Headline(text: String, k: Int, size: Number = 30) {
    Text(
        text, color = White, fontSize = t(size), fontWeight = FontWeight.ExtraBold,
        lineHeight = t(size.toFloat() * 1.12f), modifier = Modifier.reveal(k)
    )
}

/** A number that counts up with the entrance. Recomposes only itself while it runs. */
@Composable
private fun CardScope.CountUp(value: Long, size: Number, k: Int, color: Color = accent) {
    val shown by remember(value) { derivedStateOf { (value * appear.value.coerceIn(0f, 1f)).toLong() } }
    Text(
        numbers.format(shown), color = color, fontSize = t(size), fontWeight = FontWeight.Black,
        lineHeight = t(size.toFloat() * 1.05f), maxLines = 1, modifier = Modifier.reveal(k)
    )
}

@Composable
private fun CardScope.Footer(period: RewindPeriod) {
    Row(Modifier.fillMaxWidth().padding(top = d(10)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(d(8)).clip(CircleShape).background(accent))
        Spacer(Modifier.width(d(8)))
        Text("Lyrenne Rewind", color = White, fontSize = t(12), fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(period.label, color = WhiteDim, fontSize = t(12), maxLines = 1)
    }
}

@Composable
private fun CardScope.Art(url: String?, images: Map<String, ImageBitmap>, size: Dp, shape: Shape, fallback: String, modifier: Modifier = Modifier) {
    val bmp = url?.let { images[it] }
    Box(modifier.size(size).clip(shape).background(White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                fallback.trim().take(1).uppercase().ifEmpty { "?" }, color = White,
                fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

// ---- Cards

@Composable
private fun CardScope.Intro(report: RewindReport, images: Map<String, ImageBitmap>) {
    Label("Lyrenne Rewind", 0)
    Spacer(Modifier.height(d(10)))
    val title = report.period.label
    Text(
        title, color = White, fontSize = t(if (title.length <= 9) 64 else 40), fontWeight = FontWeight.Black,
        lineHeight = t(if (title.length <= 9) 64 else 44), modifier = Modifier.reveal(1)
    )
    Spacer(Modifier.height(d(8)))
    Text("Your listening, played back.", color = WhiteDim, fontSize = t(17), modifier = Modifier.reveal(2))
    Spacer(Modifier.height(d(if (compact) 18 else 36)))
    // A fanned stack of the covers that defined it.
    val covers = (report.topAlbums.map { it.thumbnailUrl to it.title } + report.topSongs.map { it.thumbnailUrl to it.title })
        .filter { it.first != null }.distinctBy { it.first }.take(4)
    val tile = d(if (compact) 118 else 168)
    Box(Modifier.fillMaxWidth().height(tile * 1.25f).reveal(3), contentAlignment = Alignment.Center) {
        covers.asReversed().forEachIndexed { revIdx, (url, name) ->
            val i = covers.size - 1 - revIdx
            val angle = listOf(-4f, 7f, -11f, 13f)[i]
            val dx = listOf(0f, 46f, -50f, 84f)[i] * u
            Art(
                url, images, tile, RoundedCornerShape(d(10)), name,
                Modifier.graphicsLayer {
                    rotationZ = angle * appear.value
                    translationX = dx * appear.value * density
                    shadowElevation = 18f * u * density
                    shape = RoundedCornerShape(10f * u * density)
                }
            )
        }
    }
}

@Composable
private fun CardScope.Minutes(report: RewindReport) {
    Label("Time well spent", 0)
    Spacer(Modifier.height(d(if (compact) 14 else 40)))
    Text("You listened for", color = WhiteDim, fontSize = t(20), modifier = Modifier.reveal(1))
    CountUp(report.minutes, if (report.minutes >= 100_000) 64 else 84, 2)
    Text("minutes", color = White, fontSize = t(28), fontWeight = FontWeight.Bold, modifier = Modifier.reveal(3))
    Spacer(Modifier.height(d(16)))
    Text(RewindText.minutesInContext(report.minutes), color = WhiteDim, fontSize = t(17), modifier = Modifier.reveal(4))
    Spacer(Modifier.height(d(if (compact) 16 else 40)))
    Row(Modifier.reveal(5), horizontalArrangement = Arrangement.spacedBy(d(8))) {
        Chip("${numbers.format(report.plays)} plays")
        Chip("${numbers.format(report.distinctSongs)} songs")
        if (report.distinctArtists > 0) Chip("${numbers.format(report.distinctArtists)} artists")
    }
}

@Composable
private fun CardScope.Chip(text: String) {
    Text(
        text, color = White, fontSize = t(13), fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(White.copy(alpha = 0.14f)).padding(horizontal = d(12), vertical = d(6))
    )
}

internal class Row4(val id: String, val title: String, val subtitle: String, val url: String?, val plays: Int, val ms: Long)

private fun RankedSong.asRow() = Row4(id, title, artistNames, thumbnailUrl, plays, ms)
private fun RankedArtist.asRow() = Row4(id, name, "${minutesText(ms)} listened", thumbnailUrl, plays, ms)
private fun RankedAlbum.asRow() = Row4(id, title, artistNames, thumbnailUrl, plays, ms)

private fun minutesText(ms: Long): String {
    val m = ms / 60_000
    return if (m == 1L) "1 minute" else "${numbers.format(m)} minutes"
}

private fun playsText(n: Int) = if (n == 1) "1 play" else "${numbers.format(n)} plays"

@Composable
private fun CardScope.TopList(title: String, rows: List<Row4>, images: Map<String, ImageBitmap>, round: Boolean) {
    Label(title, 0)
    Spacer(Modifier.height(d(if (compact) 12 else 22)))
    val first = rows.firstOrNull() ?: return
    val shape: Shape = if (round) CircleShape else RoundedCornerShape(d(10))
    if (compact) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.reveal(1)) {
            Art(first.url, images, d(96), shape, first.title)
            Spacer(Modifier.width(d(16)))
            Column(Modifier.weight(1f)) {
                Text("#1", color = accent, fontSize = t(16), fontWeight = FontWeight.Black)
                Text(first.title, color = White, fontSize = t(22), fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = t(25))
                Text(first.subtitle, color = WhiteDim, fontSize = t(14), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Art(first.url, images, d(172), shape, first.title, Modifier.reveal(1))
        }
        Spacer(Modifier.height(d(14)))
        Text(
            "#1  ${first.title}", color = White, fontSize = t(24), fontWeight = FontWeight.ExtraBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, lineHeight = t(28), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().reveal(2)
        )
        Text(
            "${first.subtitle}  ·  ${playsText(first.plays)}", color = WhiteDim, fontSize = t(14), maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().reveal(2)
        )
    }
    Spacer(Modifier.height(d(if (compact) 12 else 22)))
    rows.drop(1).take(if (compact) 2 else 4).forEachIndexed { i, row ->
        Row(Modifier.padding(vertical = d(5)).reveal(3 + i), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 2}", color = accent, fontSize = t(18), fontWeight = FontWeight.Black, modifier = Modifier.width(d(26)))
            Art(row.url, images, d(44), if (round) CircleShape else RoundedCornerShape(d(6)), row.title)
            Spacer(Modifier.width(d(12)))
            Column(Modifier.weight(1f)) {
                Text(row.title, color = White, fontSize = t(15), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.subtitle, color = WhiteDim, fontSize = t(12.5), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(d(8)))
            Text(playsText(row.plays), color = WhiteDim, fontSize = t(12.5), maxLines = 1)
        }
    }
}

@Composable
private fun CardScope.Clock(report: RewindReport) {
    val peak = report.peakHour ?: 0
    Label("Your listening clock", 0)
    Spacer(Modifier.height(d(10)))
    Text(
        "You're ${RewindText.listener(peak)}.", color = White, fontSize = t(if (compact) 24 else 28), fontWeight = FontWeight.ExtraBold,
        lineHeight = t(32), modifier = Modifier.reveal(1)
    )
    Text("Most of your listening happens around ${RewindText.hour(peak)}.", color = WhiteDim, fontSize = t(15), modifier = Modifier.reveal(2))
    Spacer(Modifier.height(d(if (compact) 8 else 26)))
    val dial = d(if (compact) 190 else 270)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Box(Modifier.size(dial).reveal(3), contentAlignment = Alignment.Center) {
        val max = report.hourMs.maxOrNull()?.coerceAtLeast(1) ?: 1
        val accentColor = accent
        Canvas(Modifier.fillMaxSize().padding(d(26))) {
            val c = Offset(size.width / 2, size.height / 2)
            val r0 = size.minDimension * 0.24f
            val r1 = size.minDimension * 0.5f
            drawCircle(White.copy(alpha = 0.08f), radius = r1, center = c)
            for (h in 0 until 24) {
                val f = report.hourMs[h].toFloat() / max * appear.value.coerceIn(0f, 1f)
                val a = Math.toRadians(h * 15.0 - 90.0)
                val len = (r1 - r0) * f.coerceAtLeast(0.03f)
                val start = Offset(c.x + r0 * cos(a).toFloat(), c.y + r0 * sin(a).toFloat())
                val end = Offset(c.x + (r0 + len) * cos(a).toFloat(), c.y + (r0 + len) * sin(a).toFloat())
                drawLine(
                    if (h == peak) accentColor else White.copy(alpha = 0.5f), start, end,
                    strokeWidth = size.minDimension * 0.034f, cap = StrokeCap.Round
                )
            }
        }
        Text(RewindText.hour(peak), color = accent, fontSize = t(if (compact) 17 else 22), fontWeight = FontWeight.Black)
        listOf("12 AM" to Alignment.TopCenter, "6 AM" to Alignment.CenterEnd, "12 PM" to Alignment.BottomCenter, "6 PM" to Alignment.CenterStart)
            .forEach { (label, at) ->
                Text(label, color = WhiteDim, fontSize = t(11), fontWeight = FontWeight.SemiBold, modifier = Modifier.align(at))
            }
    } }
}

@Composable
private fun CardScope.Streak(report: RewindReport) {
    val s = report.streak ?: return
    Label("Longest streak", 0)
    Spacer(Modifier.height(d(if (compact) 6 else 24)))
    CountUp(s.days.toLong(), if (compact) 76 else 104, 1)
    Text("days in a row", color = White, fontSize = t(28), fontWeight = FontWeight.Bold, modifier = Modifier.reveal(2))
    Spacer(Modifier.height(d(8)))
    Text(
        "${s.from.format(dayFormat)} to ${s.to.format(dayFormat)}, without missing a day.", color = WhiteDim, fontSize = t(16),
        modifier = Modifier.reveal(3)
    )
    Spacer(Modifier.height(d(if (compact) 14 else 34)))
    // One dot a day, a week to a row, up to four weeks.
    val shown = s.days.coerceAtMost(if (compact) 7 else 28)
    Column(Modifier.reveal(4), verticalArrangement = Arrangement.spacedBy(d(8))) {
        (0 until shown).chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(d(10))) {
                week.forEach { Box(Modifier.size(d(26)).clip(CircleShape).background(accent)) }
            }
        }
        if (s.days > shown) Text("and ${s.days - shown} more", color = WhiteDim, fontSize = t(14))
    }
}

@Composable
private fun CardScope.Discovery(report: RewindReport, images: Map<String, ImageBitmap>) {
    val disc = report.discovery ?: return
    Label("Biggest discovery", 0)
    Spacer(Modifier.height(d(if (compact) 12 else 30)))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Art(disc.artist.thumbnailUrl, images, d(if (compact) 110 else 200), CircleShape, disc.artist.name, Modifier.reveal(1))
    }
    Spacer(Modifier.height(d(18)))
    Text(
        disc.artist.name, color = White, fontSize = t(32), fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = t(36), modifier = Modifier.fillMaxWidth().reveal(2)
    )
    Spacer(Modifier.height(d(8)))
    Text(
        "New to you on ${disc.firstPlayed.format(longDayFormat)}. Then ${minutesText(disc.artist.ms)} across ${playsText(disc.artist.plays)}.",
        color = WhiteDim, fontSize = t(16), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().reveal(3)
    )
}

@Composable
private fun CardScope.Replay(report: RewindReport, images: Map<String, ImageBitmap>) {
    val r = report.replay ?: return
    Label("On repeat", 0)
    Spacer(Modifier.height(d(if (compact) 12 else 30)))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.reveal(1)) {
        Art(r.song.thumbnailUrl, images, d(if (compact) 92 else 132), RoundedCornerShape(d(10)), r.song.title)
        Spacer(Modifier.width(d(16)))
        Column(Modifier.weight(1f)) {
            Text(r.song.title, color = White, fontSize = t(22), fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = t(26))
            Text(r.song.artistNames, color = WhiteDim, fontSize = t(14), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(d(if (compact) 12 else 36)))
    Text("On ${r.date.format(longDayFormat)} you played it", color = WhiteDim, fontSize = t(18), modifier = Modifier.reveal(2))
    Row(verticalAlignment = Alignment.Bottom) {
        CountUp(r.plays.toLong(), 96, 3)
        Spacer(Modifier.width(d(10)))
        Text("times", color = White, fontSize = t(28), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = d(16)).reveal(3))
    }
    Text("in a single day.", color = WhiteDim, fontSize = t(18), modifier = Modifier.reveal(4))
}

@Composable
private fun CardScope.Summary(report: RewindReport, images: Map<String, ImageBitmap>) {
    Label("${report.period.label} in music", 0)
    Spacer(Modifier.height(d(if (compact) 10 else 22)))
    val top = report.topSongs.firstOrNull()
    if (!compact && top != null) {
        Art(top.thumbnailUrl, images, d(120), RoundedCornerShape(d(10)), top.title, Modifier.reveal(1))
        Spacer(Modifier.height(d(20)))
    }
    val cells = buildList {
        add("Minutes" to numbers.format(report.minutes))
        top?.let { add("Top song" to it.title) }
        report.topArtists.firstOrNull()?.let { add("Top artist" to it.name) }
        report.topAlbums.firstOrNull()?.let { add("Top album" to it.title) }
        report.streak?.takeIf { it.days >= 2 }?.let { add("Longest streak" to "${it.days} days") }
        report.peakHour?.let { add("Peak hour" to RewindText.hour(it)) }
    }.take(if (compact) 4 else 6)
    cells.chunked(2).forEachIndexed { row, pair ->
        Row(Modifier.fillMaxWidth().padding(vertical = d(if (compact) 5 else 9)).reveal(2 + row), horizontalArrangement = Arrangement.spacedBy(d(16))) {
            pair.forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(label, color = accent, fontSize = t(12), fontWeight = FontWeight.Bold)
                    Text(value, color = White, fontSize = t(19), fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = t(22))
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
