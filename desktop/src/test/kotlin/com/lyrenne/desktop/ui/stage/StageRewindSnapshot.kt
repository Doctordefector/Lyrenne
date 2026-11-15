package com.lyrenne.desktop.ui.stage

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.rewind.RewindPeriod
import com.lyrenne.desktop.rewind.RewindText
import com.lyrenne.desktop.settings.ThemeMode
import com.lyrenne.desktop.ui.rewind.RewindExport
import com.lyrenne.desktop.ui.rewind.RewindView
import com.lyrenne.desktop.ui.rewind.glowFrom
import com.lyrenne.desktop.ui.theme.LyrenneTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Renders Stage and Rewind to build/snapshots/stage-rewind/ for looking at. Skipped unless run
 * with -Psnapshots, like UiSnapshot:
 *
 *   ./gradlew :desktop:test --tests '*StageRewindSnapshot*' -Psnapshots
 */
@OptIn(ExperimentalComposeUiApi::class)
class StageRewindSnapshot {
    private val out = File("build/snapshots/stage-rewind").apply { mkdirs() }

    private fun shoot(name: String, w: Int, h: Int, theme: ThemeMode, pumpMs: Long = 1500, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(w, h, Density(1f)) {
            LyrenneTheme(themeMode = theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        val start = System.nanoTime()
        val end = System.currentTimeMillis() + pumpMs
        while (System.currentTimeMillis() < end) { scene.render(System.nanoTime() - start); Thread.sleep(30) }
        val img = scene.render(System.nanoTime() - start)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("snapshot: $name")
    }

    @Test
    fun stage() {
        assumeTrue(System.getProperty("snapshots") != null)
        val covers = File(out, "covers")
        val song = SongInfo("s1", "Neon Rain", "Kite Theory", StageRewindFixtures.coverFile(1, covers), album = "City Static")
        val longSong = SongInfo("s2", "All the Small Fires (Live at the Harbour Sessions, Extended)", "The Paper Lanterns", StageRewindFixtures.coverFile(2, covers))
        val next = SongInfo("s3", "Late Train Home", "Odessa Rain", StageRewindFixtures.coverFile(3, covers))
        val lines = StageRewindFixtures.lyrics()
        val actions = StageActions({}, {}, {}, {})
        // Mid-way through "Every light in the city is humming".
        val midLine = lines[2].words!![3].startMs + 300

        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            val t = theme.name.lowercase()
            for ((size, label) in listOf(Pair(1600 to 900, "wide"), Pair(760 to 900, "narrow"))) {
                val (w, h) = size
                shoot("stage-lyrics-$label-$t", w, h, theme) {
                    val clock = StageClock().apply { position.longValue = midLine; time.floatValue = 12f }
                    StageView(song, true, 200_000, next, lines, 0, emptyMap(), clock, null, {}, actions)
                }
                shoot("stage-nolyrics-$label-$t", w, h, theme) {
                    val clock = StageClock().apply { position.longValue = 64_000; time.floatValue = 3f }
                    StageView(longSong, false, 300_000, next, null, 0, emptyMap(), clock, null, {}, actions)
                }
            }
            // Romanized line, wide.
            shoot("stage-romanized-$t", 1400, 800, theme) {
                val clock = StageClock().apply { position.longValue = lines[4].timeMs + 300; time.floatValue = 5f }
                StageView(song, true, 200_000, next, lines, 0, mapOf(lines[4].text to "yozora ni hikaru hoshi"), clock, null, {}, actions)
            }
            shoot("stage-empty-$t", 1200, 760, theme) {
                StageView(null, false, 0, null, null, 0, emptyMap(), StageClock(), null, {}, actions)
            }
        }
    }

    @Test
    fun rewind() {
        assumeTrue(System.getProperty("snapshots") != null)
        val report = StageRewindFixtures.report()
        val images = StageRewindFixtures.images()
        val glow = glowFrom(listOf(Color(0.9f, 0.3f, 0.5f), Color(0.2f, 0.7f, 0.8f), Color(1f, 0.6f, 0.1f)))
        val cards = RewindText.cards(report)
        for (card in cards) for (format in RewindExport.Format.entries) {
            File(out, "card-${card.name.lowercase()}-${format.label}.png").writeBytes(RewindExport.render(card, report, images, glow, format))
        }
        // The screen around the card, at each size and theme, on a couple of cards.
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            val t = theme.name.lowercase()
            shoot("rewind-wide-$t", 1400, 900, theme, 2000) { RewindView(report.period, report, {}, { true }, preloaded = images) }
            shoot("rewind-narrow-$t", 760, 860, theme, 2000) { RewindView(report.period, report, {}, { true }, initialIndex = 5, preloaded = images) }
            val empty = com.lyrenne.desktop.rewind.RewindCalculator.compute(emptyList(), RewindPeriod.thisWeek(java.time.LocalDate.of(2026, 11, 15)), StageRewindFixtures.zone)
            shoot("rewind-empty-$t", 1200, 800, theme) { RewindView(empty.period, empty, {}, { true }) }
        }
    }
}
