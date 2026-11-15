package com.lyrenne.desktop.ui.stage

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.ThemeMode
import com.lyrenne.desktop.ui.theme.LyrenneTheme
import kotlinx.coroutines.delay
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import javax.swing.SwingUtilities

/**
 * Dev tool: what Stage costs in a real window, as share of one core, while lyrics play.
 * Opens a non-focusable 1280x720 window for about 40 s. Skipped unless run with -PstageCpu:
 *
 *   ./gradlew :desktop:test --tests '*StageCpuProbe*' -PstageCpu
 *
 * Modes: "paced" is Stage as shipped (a frame, then STAGE_FRAME_MS), "unpaced" redraws every
 * vsync (the cost before pacing), "paused" is a still Stage (no clock running).
 */
class StageCpuProbe {
    @Test
    fun measure() {
        assumeTrue(System.getProperty("stageCpu") != null)
        val covers = File("build/snapshots/stage-rewind/covers")
        val song = SongInfo("s1", "Neon Rain", "Kite Theory", StageRewindFixtures.coverFile(1, covers), album = "City Static")
        val next = SongInfo("s3", "Late Train Home", "Odessa Rain", StageRewindFixtures.coverFile(3, covers))
        val lines = StageRewindFixtures.lyrics()
        val mode = mutableIntStateOf(0) // 0 paced, 1 unpaced, 2 paused
        val clock = StageClock()
        lateinit var window: ComposeWindow

        SwingUtilities.invokeAndWait {
            window = ComposeWindow().apply {
                focusableWindowState = false
                isAutoRequestFocus = false
                title = "Stage CPU probe"
                setSize(1280, 720)
                setLocation(40, 40)
                setContent {
                    LyrenneTheme(themeMode = ThemeMode.DARK) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            val m = mode.intValue
                            LaunchedEffect(m) {
                                if (m == 2) return@LaunchedEffect
                                var last = -1L
                                while (true) {
                                    withFrameNanos { f ->
                                        if (last > 0) {
                                            val dt = ((f - last) / 1e9f).coerceIn(0f, 0.1f)
                                            clock.time.floatValue += dt
                                            // Loop through the fixture lyrics so lines change too.
                                            clock.position.longValue = (clock.position.longValue + (dt * 1000).toLong()) % 60_000
                                        }
                                        last = f
                                    }
                                    if (m == 0) delay(30L) // STAGE_FRAME_MS in StageScreen
                                }
                            }
                            remember { 0 }
                            StageView(song, m != 2, 200_000, next, lines, 0, emptyMap(), clock, null, {}, StageActions({}, {}, {}, {}))
                        }
                    }
                }
                isVisible = true
            }
        }

        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        fun sample(seconds: Long): Double {
            val c0 = os.processCpuTime; val t0 = System.nanoTime()
            Thread.sleep(seconds * 1000)
            return (os.processCpuTime - c0).toDouble() / (System.nanoTime() - t0) * 100
        }
        Thread.sleep(4000) // warm-up: art, palette, first layout
        val results = linkedMapOf<String, Double>()
        for ((m, name) in listOf(0 to "paced", 1 to "unpaced", 2 to "paused")) {
            mode.intValue = m
            Thread.sleep(2000)
            results[name] = sample(10)
        }
        SwingUtilities.invokeAndWait { window.dispose() }
        val report = results.entries.joinToString("\n") { (k, v) -> "STAGE CPU $k: %.1f%% of one core".format(v) }
        println(report)
        File("build/stage-cpu.txt").writeText(report + "\n")
    }
}
