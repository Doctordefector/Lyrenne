package com.lyrenne.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.lyrenne.desktop.AppPaths
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.settings.ThemeMode
import com.lyrenne.desktop.ui.theme.LyrenneTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Dev tool, not a check: renders the real app headless to PNGs so UI work can be looked at
 * without driving the desktop. Skipped unless run with -Psnapshots. Uses a copy of the dev-run
 * library (build/data/lyrenne.db), never its credentials, so it renders signed out.
 *
 *   ./gradlew :desktop:test --tests '*UiSnapshot*' -Psnapshots
 *
 * Output: build/snapshots/<name>.png. Steps come from build/snapshots/steps.txt, one per line:
 * `size W H`, `theme dark|light`, `wait MS`, `click X Y`, `rclick X Y`, `scroll X Y DY`, `shot NAME`.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class UiSnapshot {
    @Test
    fun render() {
        assumeTrue(System.getProperty("snapshots") != null)
        val out = File("build/snapshots").apply { mkdirs() }
        val steps = File(out, "steps.txt").takeIf { it.isFile }?.readLines()
            ?: listOf("theme dark", "wait 3000", "shot home-dark")

        File("build/data").listFiles { f -> f.name == "lyrenne.db" || f.name == "preferences.properties" }
            ?.forEach { it.copyTo(File(AppPaths.dataDir, it.name), overwrite = true) }
        DatabaseHelper.initialize()
        PreferencesManager.initialize()
        val player = DesktopPlayer()
        runBlocking { player.restoreQueue() }

        var w = 1200; var h = 800
        var scene: ImageComposeScene? = null
        val start = System.nanoTime()
        fun frame() = scene!!.render(System.nanoTime() - start)
        fun newScene(theme: ThemeMode) {
            scene?.close()
            scene = ImageComposeScene(w, h, Density(1f)) {
                LyrenneTheme(themeMode = theme) {
                    // As Main.kt does: the Surface is what gives text its onSurface colour.
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(player) }
                }
            }
            frame()
        }
        fun pump(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { frame(); Thread.sleep(40) }
        }
        var theme = ThemeMode.DARK
        newScene(theme)
        for (line in steps.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val p = line.split(" ")
            when (p[0]) {
                "size" -> { w = p[1].toInt(); h = p[2].toInt(); newScene(theme) }
                "theme" -> { theme = if (p[1] == "light") ThemeMode.LIGHT else ThemeMode.DARK; newScene(theme) }
                "wait" -> pump(p[1].toLong())
                "click", "rclick" -> {
                    val at = Offset(p[1].toFloat(), p[2].toFloat())
                    val button = if (p[0] == "rclick") PointerButton.Secondary else PointerButton.Primary
                    val buttons = if (p[0] == "rclick") PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true)
                    scene!!.sendPointerEvent(PointerEventType.Move, at)
                    scene!!.sendPointerEvent(PointerEventType.Press, at, buttons = buttons, button = button)
                    scene!!.sendPointerEvent(PointerEventType.Release, at, button = button)
                    pump(400)
                }
                "move" -> { scene!!.sendPointerEvent(PointerEventType.Move, Offset(p[1].toFloat(), p[2].toFloat())); pump(700) }
                "scroll" -> {
                    scene!!.sendPointerEvent(
                        PointerEventType.Scroll, Offset(p[1].toFloat(), p[2].toFloat()),
                        scrollDelta = Offset(0f, p[3].toFloat())
                    )
                    pump(400)
                }
                "type", "key" -> {
                    // AWT key events, as a real window would deliver them. `key` takes a VK name.
                    val src = java.awt.Canvas()
                    fun send(id: Int, code: Int, ch: Char) {
                        val awt = java.awt.event.KeyEvent(src, id, System.currentTimeMillis(), 0, code, ch)
                        scene!!.sendKeyEvent(
                            androidx.compose.ui.input.key.KeyEvent(
                                key = androidx.compose.ui.input.key.Key(code),
                                type = when (id) {
                                    java.awt.event.KeyEvent.KEY_PRESSED -> androidx.compose.ui.input.key.KeyEventType.KeyDown
                                    java.awt.event.KeyEvent.KEY_RELEASED -> androidx.compose.ui.input.key.KeyEventType.KeyUp
                                    else -> androidx.compose.ui.input.key.KeyEventType.Unknown
                                },
                                codePoint = if (ch == java.awt.event.KeyEvent.CHAR_UNDEFINED) 0 else ch.code,
                                nativeEvent = awt
                            )
                        )
                    }
                    if (p[0] == "key") {
                        val code = java.awt.event.KeyEvent::class.java.getField("VK_" + p[1].uppercase()).getInt(null)
                        send(java.awt.event.KeyEvent.KEY_PRESSED, code, java.awt.event.KeyEvent.CHAR_UNDEFINED)
                        send(java.awt.event.KeyEvent.KEY_RELEASED, code, java.awt.event.KeyEvent.CHAR_UNDEFINED)
                    } else for (ch in line.substringAfter(' ')) {
                        send(java.awt.event.KeyEvent.KEY_TYPED, java.awt.event.KeyEvent.VK_UNDEFINED, ch)
                    }
                    pump(300)
                }
                "shot" -> {
                    val data = frame().encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
                    File(out, "${p[1]}.png").writeBytes(data.bytes)
                    println("snapshot: ${p[1]}")
                }
            }
        }
        scene?.close()
        player.release()
    }
}
