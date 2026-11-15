package com.lyrenne.desktop.gamemode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.lyrics.LyricLine
import com.lyrenne.desktop.lyrics.LyricWord
import com.lyrenne.desktop.lyrics.LyricsManager
import com.lyrenne.desktop.lyrics.LyricsState
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.ThemeMode
import com.lyrenne.desktop.ui.screens.gameModeSettings
import com.lyrenne.desktop.ui.theme.LyrenneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Dev tool: renders Game Mode's overlay states over a game-like background, and its settings, to
 * build/snapshots/gamemode/. Skipped unless run with -Psnapshots.
 */
class GameModeSnapshot {
    private val out = File("build/snapshots/gamemode").apply { mkdirs() }

    private fun shoot(name: String, w: Int, h: Int, theme: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(w, h, Density(1f)) { LyrenneTheme(themeMode = theme) { content() } }
        val start = System.nanoTime()
        repeat(40) { scene.render(System.nanoTime() - start); Thread.sleep(25) }
        File(out, "$name.png").writeBytes(scene.render(System.nanoTime() - start).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    /** Something busy and bright behind the overlay, like a game. */
    private val gameBackground = Brush.linearGradient(listOf(Color(0xFF6FB3E8), Color(0xFFE8D28A), Color(0xFF3E7A3A), Color(0xFF1B2A44)))

    @Test
    fun render() = runBlocking {
        assumeTrue(System.getProperty("snapshots") != null)
        val player = DesktopPlayer().apply {
            downloadedFileFor = { null }; streamFor = { "https://stream.invalid/$it" }; autoplayAllowed = { false }
        }
        val song = SongInfo("s1", "Midnight City", "M83", null, album = "Hurry Up, We're Dreaming")
        player.playQueue(listOf(song), 0)
        val words = listOf(LyricWord("Waiting", 0, 600), LyricWord(" in", 600, 900), LyricWord(" a", 900, 1000), LyricWord(" car", 1000, 1600))
        val lines = listOf(
            LyricLine(0, "Waiting in a car", words = words),
            LyricLine(4000, "Waiting for a ride in the dark"),
        )
        @Suppress("UNCHECKED_CAST")
        val f = LyricsManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
        (f.get(LyricsManager) as MutableStateFlow<LyricsState>).value = LyricsState(songId = "s1", lyrics = "x", lines = lines)

        val views = mapOf(
            "toast" to OverlayView(card = true),
            "lyrics" to OverlayView(lyrics = true),
            "peek" to OverlayView(card = true, lyrics = true),
            "flash" to OverlayView(flash = OverlayFlash(OverlayFlash.Kind.VOLUME, "Volume 80%", 1)),
            "flash-next" to OverlayView(card = true, flash = OverlayFlash(OverlayFlash.Kind.NEXT, "Next", 2)),
        )
        for ((name, view) in views) for (corner in listOf(OverlayCorner.TOP_RIGHT, OverlayCorner.BOTTOM_CENTER)) {
            shoot("overlay-$name-${corner.name.lowercase()}", 520, 340) {
                Box(Modifier.fillMaxSize().background(gameBackground)) {
                    OverlayContent(player, view, corner, Modifier.fillMaxSize())
                }
            }
        }
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            shoot("settings-${theme.name.lowercase()}", 1000, 2200, theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LazyColumn(Modifier.padding(16.dp)) { gameModeSettings(GameModePrefs()) }
                }
            }
        }
        player.release()
    }
}
