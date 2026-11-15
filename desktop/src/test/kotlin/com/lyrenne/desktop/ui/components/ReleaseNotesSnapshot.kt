package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.settings.ThemeMode
import com.lyrenne.desktop.ui.theme.LyrenneTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Dev tool: renders release notes the way Settings > Updates shows them, to
 * build/snapshots/notes-*.png. Uses build/notes-sample.md when present (a real release body),
 * else a built-in sample. Skipped unless run with -Psnapshots.
 */
class ReleaseNotesSnapshot {
    @Test
    fun render() {
        assumeTrue(System.getProperty("snapshots") != null)
        val out = File("build/snapshots").apply { mkdirs() }
        val md = File("build/notes-sample.md").takeIf { it.exists() }?.readText() ?: """
            ## New
            - **Game Mode**: hotkeys that work in games
            - Use `winget upgrade Lyrenne`
            ### Fixed
            1. Something with [a link](https://example.com)
        """.trimIndent()
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            val scene = ImageComposeScene(620, 560, Density(1f)) {
                LyrenneTheme(themeMode = theme) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ReleaseNotesText(md, Modifier.padding(16.dp))
                    }
                }
            }
            val data = scene.render().encodeToData(EncodedImageFormat.PNG)!!
            File(out, "notes-${theme.name.lowercase()}.png").writeBytes(data.bytes)
            scene.close()
        }
    }
}
