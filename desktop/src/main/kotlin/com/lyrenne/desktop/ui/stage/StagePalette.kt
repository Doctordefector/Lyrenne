package com.lyrenne.desktop.ui.stage

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * A few colours from one cover, for Stage's moving background and Rewind's cards.
 *
 * ArtColor gives one accent for a wash; a background made of one colour does not move, so this
 * takes up to [COUNT] by hue. Same budget as ArtColor: a 64 px request through the shared loader
 * (a disk-cache hit for anything already on screen), 16x16 samples, cached by URL.
 *
 * The colours come back as hues only (raw, unclamped). [StageColors] decides how bright they may
 * be in each theme, which is what keeps lyric text legible over them.
 */
object StagePalette {
    const val COUNT = 3
    private const val SAMPLE = 16
    private val cache = object : LinkedHashMap<String, List<Color>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Color>>?) = size > 32
    }

    suspend fun of(url: String): List<Color> {
        synchronized(cache) { cache[url] }?.let { return it }
        val context = PlatformContext.INSTANCE
        val result = try {
            SingletonImageLoader.get(context)
                .execute(ImageRequest.Builder(context).data(url).size(64).build()) as? SuccessResult
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val colors = try {
            withContext(Dispatchers.Default) {
                val bmp = result.image.toBitmap(SAMPLE, SAMPLE)
                val px = IntArray(bmp.width * bmp.height)
                var i = 0
                for (y in 0 until bmp.height) for (x in 0 until bmp.width) px[i++] = bmp.getColor(x, y)
                dominant(px)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        synchronized(cache) { cache[url] = colors }
        return colors
    }

    /**
     * Up to [COUNT] colours from ARGB [pixels]: a 12-bin hue histogram weighted by saturation and
     * brightness, the heaviest bins first, each bin's weighted mean colour. Greys add nothing, so
     * a black and white cover gives an empty list and the caller falls back to the theme.
     */
    fun dominant(pixels: IntArray): List<Color> {
        val bins = 12
        val w = DoubleArray(bins)
        val r = DoubleArray(bins); val g = DoubleArray(bins); val b = DoubleArray(bins)
        for (argb in pixels) {
            val pr = (argb shr 16 and 0xFF) / 255.0
            val pg = (argb shr 8 and 0xFF) / 255.0
            val pb = (argb and 0xFF) / 255.0
            val mx = max(pr, max(pg, pb)); val mn = min(pr, min(pg, pb))
            if (mx < 0.08) continue
            val sat = (mx - mn) / mx
            if (sat < 0.18) continue
            val hue = hueOf(pr, pg, pb, mx, mn)
            val bin = ((hue / 360.0) * bins).toInt().coerceIn(0, bins - 1)
            val weight = sat * sat * mx
            w[bin] += weight; r[bin] += pr * weight; g[bin] += pg * weight; b[bin] += pb * weight
        }
        val total = w.sum()
        if (total <= 0.0) return emptyList()
        return w.indices.sortedByDescending { w[it] }
            // A bin with a sliver of the cover is noise, not a colour of it.
            .filter { w[it] >= total * 0.06 }
            .take(COUNT)
            .map { Color((r[it] / w[it]).toFloat(), (g[it] / w[it]).toFloat(), (b[it] / w[it]).toFloat()) }
    }

    private fun hueOf(r: Double, g: Double, b: Double, mx: Double, mn: Double): Double {
        val d = mx - mn
        if (d == 0.0) return 0.0
        val h = when (mx) {
            r -> 60 * (((g - b) / d) % 6)
            g -> 60 * (((b - r) / d) + 2)
            else -> 60 * (((r - g) / d) + 4)
        }
        return if (h < 0) h + 360 else h
    }
}

/**
 * The colours Stage paints with, already safe for its text.
 *
 * Dark: blobs are capped at a low brightness and drawn translucent over near-black, so the
 * page under the lyrics stays under about 0.06 luminance and white text stays far above 4.5:1
 * (the dimmed next line, at 72% white, too). Light: blobs are pastel over warm white and the
 * text is near-black, the same reasoning mirrored.
 */
data class StageColors(
    val base: Color,
    val blobs: List<Color>,
    val text: Color,
    /** The next line and secondary text. */
    val textDim: Color,
    /** Decorative only (the line just sung); large text, 3:1 or better. */
    val textFaint: Color,
    val dark: Boolean,
) {
    companion object {
        private val fallbackHues = listOf(Color(0xFFA37C43), Color(0xFF7A4E8C), Color(0xFF2F6F7A))

        fun from(hues: List<Color>, dark: Boolean): StageColors {
            val src = (hues.ifEmpty { fallbackHues } + fallbackHues).take(StagePalette.COUNT + 1)
            return if (dark) StageColors(
                base = Color(0xFF09080A),
                blobs = src.map { tone(it, saturation = 0.45f..0.85f, value = 0.36f) },
                text = Color.White,
                textDim = Color.White.copy(alpha = 0.72f),
                textFaint = Color.White.copy(alpha = 0.46f),
                dark = true,
            ) else StageColors(
                base = Color(0xFFFBF8F4),
                blobs = src.map { tone(it, saturation = 0.22f..0.42f, value = 1f) },
                text = Color(0xFF1B1714),
                textDim = Color(0xFF1B1714).copy(alpha = 0.84f),
                textFaint = Color(0xFF1B1714).copy(alpha = 0.58f),
                dark = false,
            )
        }

        /** [c]'s hue at a set brightness, with saturation clamped into a range. */
        internal fun tone(c: Color, saturation: ClosedFloatingPointRange<Float>, value: Float): Color {
            val mx = max(c.red, max(c.green, c.blue)); val mn = min(c.red, min(c.green, c.blue))
            val d = mx - mn
            val hue = when {
                d == 0f -> 0f
                mx == c.red -> 60f * (((c.green - c.blue) / d) % 6f)
                mx == c.green -> 60f * (((c.blue - c.red) / d) + 2f)
                else -> 60f * (((c.red - c.green) / d) + 4f)
            }.let { if (it < 0) it + 360f else it }
            val sat = (if (mx == 0f) 0f else d / mx).coerceIn(saturation.start, saturation.endInclusive)
            return Color.hsv(hue, sat, value)
        }

        /** WCAG relative luminance, for the contrast test. */
        fun luminance(c: Color): Double {
            fun ch(v: Float): Double = if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }

        fun contrast(a: Color, b: Color): Double {
            val la = luminance(a); val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        /** [top] at its alpha over opaque [under]. */
        fun over(top: Color, under: Color): Color {
            val a = top.alpha
            return Color(
                top.red * a + under.red * (1 - a),
                top.green * a + under.green * (1 - a),
                top.blue * a + under.blue * (1 - a),
            )
        }
    }
}

/** How strongly a blob is painted over [StageColors.base]. */
internal const val BLOB_ALPHA_DARK = 0.62f
internal const val BLOB_ALPHA_LIGHT = 0.6f

/** The playing cover's Stage colours, cross-faded on track change. Read in draw only. */
@Composable
fun rememberStageColors(url: String?, dark: Boolean): State<StageColors> {
    var hues by remember { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(url) { hues = url?.let { StagePalette.of(it) } ?: emptyList() }
    val target = remember(hues, dark) { StageColors.from(hues, dark) }
    val spec = tween<Color>(1200)
    val b0 by animateColorAsState(target.blobs[0], spec, label = "blob0")
    val b1 by animateColorAsState(target.blobs[1], spec, label = "blob1")
    val b2 by animateColorAsState(target.blobs[2], spec, label = "blob2")
    val b3 by animateColorAsState(target.blobs[3], spec, label = "blob3")
    return remember(target) { derivedStateOf { target.copy(blobs = listOf(b0, b1, b2, b3)) } }
}
