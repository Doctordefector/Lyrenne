package com.lyrenne.desktop.link

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.settings.ThemeMode
import com.lyrenne.desktop.ui.link.LyrenneLinkSettings
import com.lyrenne.desktop.ui.link.PairingDialog
import com.lyrenne.desktop.ui.theme.LyrenneTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Dev tool like UiSnapshot: renders the Lyrenne Link settings section and the pairing dialog to
 * build/snapshots/link-*.png in both themes, narrow and wide. Skipped unless run with -Psnapshots.
 */
class LinkUiSnapshot {
    @Test
    fun render() {
        assumeTrue(System.getProperty("snapshots") != null)
        val out = File("build/snapshots").apply { mkdirs() }
        LinkSettings.fileOverride = File(Files.createTempDirectory("link-ui").toFile(), "link.properties")
        LinkSettings.load()
        LinkSettings.update { it.copy(overlayEnabled = true, remoteEnabled = true, exportEnabled = true, exportFolder = "C:/Streams/Lyrenne") }
        LyrenneLink._status.value = LinkStatus(running = true, port = 48712, lan = true, lanAddresses = listOf("192.168.1.20", "10.0.0.5"))

        fun shot(name: String, w: Int, h: Int, theme: ThemeMode, content: @androidx.compose.runtime.Composable () -> Unit) {
            val scene = ImageComposeScene(w, h, Density(1f)) {
                LyrenneTheme(themeMode = theme) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
            val start = System.nanoTime()
            repeat(30) { scene.render(System.nanoTime() - start); Thread.sleep(30) }
            val data = scene.render(System.nanoTime() - start).encodeToData(EncodedImageFormat.PNG)!!
            File(out, "link-$name.png").writeBytes(data.bytes)
            scene.close()
        }
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            val t = theme.name.lowercase()
            shot("settings-wide-$t", 1100, 1000, theme) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { LyrenneLinkSettings() }
            }
            shot("settings-narrow-$t", 620, 1500, theme) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { LyrenneLinkSettings() }
            }
            shot("pairing-$t", 800, 760, theme) { PairingDialog(onDismiss = {}) }
        }
        LinkSettings.update { it.copy(overlay = OverlayConfig(layout = "lyrics", position = "bc")) }
        shot("settings-lyrics-dark", 1100, 1000, ThemeMode.DARK) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { LyrenneLinkSettings() }
        }
        LinkSettings.update { it.copy(overlay = OverlayConfig(layout = "ticker", position = "tl", accent = "ff5577")) }
        shot("settings-ticker-dark", 1100, 1000, ThemeMode.DARK) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { LyrenneLinkSettings() }
        }
    }
}
