package com.lyrenne.desktop.ui.stage

import androidx.compose.ui.graphics.Color
import com.lyrenne.desktop.rewind.RewindCardKind
import com.lyrenne.desktop.rewind.RewindText
import com.lyrenne.desktop.ui.rewind.CardStyles
import com.lyrenne.desktop.ui.rewind.RewindExport
import com.lyrenne.desktop.ui.rewind.glowFrom
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contrast of Stage's and Rewind's own colours, and the PNG export. */
class StageRewindRenderTest {
    private val hues = listOf(
        Color(1f, 1f, 0f), Color(0f, 1f, 0f), Color(0f, 1f, 1f), Color(0f, 0f, 1f),
        Color(1f, 0f, 1f), Color(1f, 0f, 0f), Color(1f, 0.6f, 0.1f), Color(0.6f, 0.6f, 0.6f),
    )

    @Test
    fun `stage text stays legible over any cover colour, both themes`() {
        for (dark in listOf(true, false)) for (h in hues) {
            val c = StageColors.from(listOf(h), dark)
            for (blob in c.blobs) {
                // Worst case: text right over a blob's centre, as if it were opaque.
                val text = StageColors.contrast(c.text, blob)
                val dim = StageColors.contrast(StageColors.over(c.textDim, blob), blob)
                assertTrue("text on $blob (dark=$dark): $text", text >= 4.5)
                assertTrue("dim text on $blob (dark=$dark): $dim", dim >= 4.5)
            }
            assertTrue(StageColors.contrast(c.text, c.base) >= 7.0)
        }
    }

    @Test
    fun `rewind card text stays legible, glow included`() {
        val glows = glowFrom(hues)
        for ((kind, style) in CardStyles) {
            for (bg in listOf(style.from, style.to) + glows.map { StageColors.over(it.copy(alpha = 0.42f), style.to) }) {
                val white = StageColors.contrast(Color.White, bg)
                val dim = StageColors.contrast(StageColors.over(Color.White.copy(alpha = 0.78f), bg), bg)
                val accent = StageColors.contrast(style.accent, bg)
                assertTrue("$kind white on $bg: $white", white >= 4.5)
                assertTrue("$kind dim on $bg: $dim", dim >= 4.5)
                assertTrue("$kind accent on $bg: $accent", accent >= 4.5)
            }
        }
    }

    @Test
    fun `export writes a story and a square png of every card`() {
        val report = StageRewindFixtures.report()
        val images = StageRewindFixtures.images()
        val cards = RewindText.cards(report)
        assertEquals(RewindCardKind.entries.toList(), cards) // the fixture year fills every card
        for (format in RewindExport.Format.entries) for (card in listOf(RewindCardKind.TOP_SONGS, RewindCardKind.SUMMARY)) {
            val png = RewindExport.render(card, report, images, glowFrom(listOf(Color.Magenta)), format)
            val bmp = Bitmap.makeFromImage(Image.makeFromEncoded(png))
            assertEquals(format.width, bmp.width)
            assertEquals(format.height, bmp.height)
            // Not blank: plenty of distinct colours across a coarse grid.
            val distinct = HashSet<Int>()
            for (y in 0 until bmp.height step 40) for (x in 0 until bmp.width step 40) distinct += bmp.getColor(x, y)
            assertTrue("$card $format looks blank", distinct.size > 50)
        }
    }

    @Test
    fun `fill point follows word timing`() {
        val spans = wordSpans("Neon rain falls", listOf(
            com.lyrenne.desktop.lyrics.LyricWord("Neon", 1000, 1500),
            com.lyrenne.desktop.lyrics.LyricWord(" rain", 1600, 2000),
            com.lyrenne.desktop.lyrics.LyricWord("missing", 2000, 2100),
            com.lyrenne.desktop.lyrics.LyricWord("falls", 2200, 2200),
        ))
        assertEquals(3, spans.size)
        assertEquals(null, fillPoint(spans, 999))
        assertEquals(Triple(0, 4, 0.5f), fillPoint(spans, 1250))
        assertEquals(Triple(0, 4, 1f), fillPoint(spans, 1550)) // between words: the last one, complete
        assertEquals(Triple(5, 9, 0.5f), fillPoint(spans, 1800))
        assertEquals(Triple(10, 15, 1f), fillPoint(spans, 2200)) // zero-length word is sung at once
    }

    @Test
    fun `palette picks the heaviest hues and ignores greys`() {
        val red = 0xFFD02020.toInt(); val blue = 0xFF2040D0.toInt(); val grey = 0xFF808080.toInt()
        val pixels = IntArray(256) { i -> when { i < 150 -> red; i < 220 -> blue; else -> grey } }
        val colors = StagePalette.dominant(pixels)
        assertEquals(2, colors.size)
        assertTrue(colors[0].red > colors[0].blue)
        assertTrue(colors[1].blue > colors[1].red)
        assertTrue(StagePalette.dominant(IntArray(256) { grey }).isEmpty())
    }
}
