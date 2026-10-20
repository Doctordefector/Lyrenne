package com.lyrenne.desktop.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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

/**
 * One colour per cover, for tinting the chrome around whatever is playing.
 *
 * Kept cheap on purpose, since it runs on every track change:
 * - The cover goes through the shared ImageLoader at 64 px, so it is a disk-cache hit for any
 *   song whose art has already been on screen, and never a full-size decode.
 * - That is scaled to 16x16 and averaged, weighted by saturation, so a grey border or a black
 *   letterbox does not wash the colour out. 256 pixels, off the main thread.
 * - Results are cached by URL, so going back to a song costs nothing.
 *
 * No blur, no palette library. Callers paint the result as a translucent wash over their own
 * surface, which keeps the theme's text contrast intact (see LyrenneExtras.artWashAlpha).
 */
object ArtColor {
    private const val SAMPLE = 16
    private val cache = object : LinkedHashMap<String, Color>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?) = size > 64
    }

    /** The cover's accent, brightness-normalised, or null for a greyscale or unloadable cover. */
    suspend fun of(url: String): Color? {
        synchronized(cache) { cache[url] }?.let { return it.takeIf { c -> c != Color.Unspecified } }
        val context = PlatformContext.INSTANCE
        val result = SingletonImageLoader.get(context)
            .execute(ImageRequest.Builder(context).data(url).size(64).build()) as? SuccessResult
            ?: return null
        val color = try {
            withContext(Dispatchers.Default) { accent(result.image.toBitmap(SAMPLE, SAMPLE)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        // Unspecified marks "looked, nothing usable", so a grey cover is not re-sampled each time.
        synchronized(cache) { cache[url] = color ?: Color.Unspecified }
        return color
    }

    internal fun accent(bitmap: org.jetbrains.skia.Bitmap): Color? {
        var r = 0.0; var g = 0.0; var b = 0.0; var weight = 0.0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val argb = bitmap.getColor(x, y)
            val pr = (argb shr 16 and 0xFF) / 255.0
            val pg = (argb shr 8 and 0xFF) / 255.0
            val pb = (argb and 0xFF) / 255.0
            val max = maxOf(pr, pg, pb)
            val sat = if (max == 0.0) 0.0 else (max - minOf(pr, pg, pb)) / max
            // Saturation dominates; the small floor keeps a mostly-grey cover's hint of colour.
            val w = sat * sat * max + 0.002
            r += pr * w; g += pg * w; b += pb * w; weight += w
        }
        r /= weight; g /= weight; b /= weight
        val max = maxOf(r, g, b)
        if (max < 0.05 || (max - minOf(r, g, b)) / max < 0.15) return null
        // Brightness-normalised: the wash's strength comes from its alpha, not the cover's exposure.
        return Color((r / max).toFloat(), (g / max).toFloat(), (b / max).toFloat())
    }
}

/**
 * The playing cover's accent, cross-faded on track change. Transparent when there is none.
 *
 * Returned as State so callers can read it in a draw lambda: the 600 ms fade then only redraws,
 * it never recomposes the screen it tints.
 */
@Composable
fun rememberArtTint(url: String?): State<Color> {
    var art by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) { art = url?.let { ArtColor.of(it) } }
    return animateColorAsState(art ?: Color.Transparent, tween(600), label = "artTint")
}
