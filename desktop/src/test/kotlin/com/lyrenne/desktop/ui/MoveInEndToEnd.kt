package com.lyrenne.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.lyrenne.desktop.AppPaths
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.movein.BackupState
import com.lyrenne.desktop.movein.MoveInController
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.settings.ThemeMode
import com.lyrenne.desktop.ui.theme.LyrenneTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The MetroList import end to end in the real app, headless: the real App and database, the
 * real controller reading a fixture backup, the preview and result windows drawn over the app,
 * then the Library afterwards. The native file picker is the one step not exercised; the
 * controller is handed the file it would return. Skipped unless -Psnapshots.
 *
 *   ./gradlew :desktop:test --tests '*MoveInEndToEnd*' -Psnapshots
 *
 * Writes build/snapshots/e2e-*.png and leaves the imported database in build/movein-e2e/.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class MoveInEndToEnd {
    @Test
    fun importThenLookAtTheLibrary() {
        assumeTrue(System.getProperty("snapshots") != null)
        val out = File("build/snapshots").apply { mkdirs() }
        // A fresh, empty library: what a MetroList user has on day one
        for (name in listOf("lyrenne.db", "lyrenne.db-wal", "lyrenne.db-shm", "movein.json")) File(AppPaths.dataDir, name).delete()
        AppPaths.dataDir.mkdirs()
        File(AppPaths.dataDir, "preferences.properties").writeText(
            listOf("onboardingCompleted=true", "windowsMediaShortcut=false", "autoSyncOnStartup=false", "checkUpdatesOnLaunch=false")
                .joinToString("\n")
        )
        DatabaseHelper.initialize()
        PreferencesManager.initialize()
        val player = DesktopPlayer()

        val (w, h) = 1200 to 800
        val start = System.nanoTime()
        val scene = ImageComposeScene(w, h, Density(1f)) {
            LyrenneTheme(themeMode = ThemeMode.DARK) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(player) }
            }
        }
        fun frame() = scene.render(System.nanoTime() - start)
        fun pump(ms: Long) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { frame(); Thread.sleep(40) } }
        fun shot(name: String) { File(out, "e2e-$name.png").writeBytes(frame().encodeToData(EncodedImageFormat.PNG)!!.bytes); println("snapshot: e2e-$name") }
        fun click(x: Int, y: Int) {
            val at = Offset(x.toFloat(), y.toFloat())
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
            pump(600)
        }
        fun waitFor(what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 20_000
            while (!cond() && System.currentTimeMillis() < end) pump(100)
            assertTrue("timed out waiting for $what", cond())
        }

        pump(1500)
        val backup = File(javaClass.getResource("/movein/metrolist13.backup")!!.toURI())
        // What "Choose backup file" does once the picker returns
        MoveInController.openBackup()
        MoveInController.readBackup(backup)
        waitFor("preview") { MoveInController.backup.value is BackupState.Preview }
        pump(800)
        shot("1-preview")
        MoveInController.runImport()
        waitFor("result") { MoveInController.backup.value is BackupState.Done }
        pump(800)
        shot("2-done")
        println("result: ${(MoveInController.backup.value as BackupState.Done).result}")
        MoveInController.close()
        pump(500)

        // Library rail button, then a look at each tab
        click(37, 335)
        pump(1500)
        shot("3-library")
        // Liked filter on, then off; Playlists; Albums; Artists (coordinates of the 1200x800 layout)
        click(994, 163); shot("4-liked"); click(994, 163)
        click(414, 163); shot("5-playlists")
        click(226, 163); shot("6-albums")
        click(319, 163); shot("7-artists")
        click(37, 550); pump(1500); click(596, 140); pump(1500); shot("8-stats-all-time")

        scene.close()
        player.release()
        // Keep the result for a run of the real app
        val keep = File("build/movein-e2e").apply { mkdirs() }
        DatabaseHelper.snapshotTo(File(keep, "lyrenne.db"))
        File(AppPaths.dataDir, "movein.json").takeIf { it.isFile }?.copyTo(File(keep, "movein.json"), overwrite = true)
    }
}
