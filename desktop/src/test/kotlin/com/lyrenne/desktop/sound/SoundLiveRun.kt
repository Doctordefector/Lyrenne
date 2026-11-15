package com.lyrenne.desktop.sound

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
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.settings.ThemeMode
import com.lyrenne.desktop.sound.eq.AutoEq
import com.lyrenne.desktop.sound.eq.EqFitter
import com.lyrenne.desktop.sound.sponsorblock.SponsorBlock
import com.lyrenne.desktop.ui.App
import com.lyrenne.desktop.ui.theme.LyrenneTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Dev tool, not a check: plays a real music video through VLC, muted, with the real App rendered
 * headless, to watch SponsorBlock skip it and the seek bar mark it, and AutoEQ land in VLC.
 * Needs the network. Runs only with -Psnapshots and a `build/snapshots/sound-live` file present:
 *
 *   touch desktop/build/snapshots/sound-live
 *   ./gradlew :desktop:test --tests '*SoundLiveRun*' -Psnapshots
 */
@OptIn(ExperimentalComposeUiApi::class)
class SoundLiveRun {
    @Test
    fun run() {
        val out = File("build/snapshots")
        assumeTrue(System.getProperty("snapshots") != null && File(out, "sound-live").exists())
        DatabaseHelper.initialize()
        PreferencesManager.initialize()
        PreferencesManager.setOnboardingCompleted(true)
        PreferencesManager.setSponsorBlockEnabled(true)
        PreferencesManager.setEqEnabled(true)
        PreferencesManager.setVolume(0f)

        val player = DesktopPlayer()
        player.ensureVlcInitialized()
        player.setVolume(0f)
        SponsorBlock.initialize(player)

        val scene = ImageComposeScene(1200, 800, Density(1f)) {
            LyrenneTheme(themeMode = ThemeMode.DARK) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(player) }
            }
        }
        val start = System.nanoTime()
        fun frame() = scene.render(System.nanoTime() - start)
        fun shot(name: String) {
            File(out, "$name.png").writeBytes(frame().encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("snapshot: $name at ${player.state.value.position} ms")
        }
        fun pumpUntil(maxMs: Long, cond: () -> Boolean): Boolean {
            val end = System.currentTimeMillis() + maxMs
            while (System.currentTimeMillis() < end) {
                frame()
                if (cond()) return true
                Thread.sleep(50)
            }
            return false
        }
        fun click(x: Float, y: Float) {
            val at = Offset(x, y)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
        }

        // A music video with a non-music intro: the first line of the sound-live file, or Despacito's
        // (a 21.8 s intro and an outro of credits, both locked)
        val id = File(out, "sound-live").readLines().firstOrNull()?.trim()?.takeIf { it.length == 11 } ?: "kJQP7kiw5Fk"
        val introEnd = SponsorBlockApiProbe.introEnd(id)
        val song = SongInfo(id, "Music video $id", "Live run", "https://i.ytimg.com/vi/$id/mqdefault.jpg")
        // YouTube can refuse signed-out streams (a bot check, or a URL VLC is 403'd). A stand-in file
        // of the video's exact length then plays under the video's id: the segments, VLC, the skip
        // and the UI are all still the real ones, only the audio is not the song.
        File(out, "sound-live.m4a").takeIf { it.exists() }?.let { standIn ->
            println("playing ${standIn.name} in place of the stream")
            player.downloadedFileFor = { standIn }
        }
        check(runBlocking { player.playSong(song) }) { "no stream" }

        // 1. The intro is skipped as soon as playback starts
        val skipped = pumpUntil(30_000) { player.state.value.position > introEnd - 1_000 }
        println("intro skipped: $skipped, notice ${SponsorBlock.notice.value}")
        pumpUntil(600) { false }
        shot("live-intro-skipped")

        // 2. Undo, through the snackbar's own button: back to 0:00, and the intro now plays
        val notice = SponsorBlock.notice.value
        if (notice != null) {
            // `undo X Y` in the sound-live file clicks the snackbar's button there instead
            val at = File(out, "sound-live").readLines().firstOrNull { it.startsWith("undo ") }?.split(" ")
            if (at != null) click(at[1].toFloat(), at[2].toFloat()) else SponsorBlock.undo(player, notice)
            pumpUntil(3_000) { false }
            println("after undo: position ${player.state.value.position} ms (intro kept, should stay under 21800 for a while)")
            shot("live-after-undo")
        }

        // 3. A seek into the outro is respected; playing into it from before is skipped
        val outro = SponsorBlockApiProbe.segments(id).lastOrNull { it.startMs > 60_000 }
        if (outro != null) player.seekTo(outro.startMs - 4_000)
        val ended = pumpUntil(12_000) { player.state.value.position < 5_000 || player.state.value.currentSong?.id != song.id }.takeIf { outro != null }
        println("outro: track ended early or moved on: $ended, now ${player.state.value.currentSong?.title} at ${player.state.value.position}")

        // 4. AutoEQ into VLC, while something plays
        val autoEq = AutoEq()
        val entry = AutoEq.search(autoEq.index(), "Sennheiser HD 600").first()
        val bands = player.getEqualizerBands()
        val fit = EqFitter.fit(autoEq.curve(entry), bands.map { it.toDouble() })
        PreferencesManager.setEqBands(fit.bandsDb)
        PreferencesManager.setEqPreamp(fit.preampDb)
        player.applyEqualizer()
        println("AutoEQ fit for bands $bands: preamp ${fit.preampDb}, ${fit.bandsDb}")
        println("VLC equalizer now: ${player.equalizerSnapshot()}")

        scene.close()
        player.release()
    }
}

/** The live run's own look at the segments, to know where to wait. */
private object SponsorBlockApiProbe {
    fun segments(id: String) = com.lyrenne.desktop.sound.sponsorblock.SponsorBlockApi.fetch(OkHttpTransport, id)
        .filter { it.category == "music_offtopic" }
    fun introEnd(id: String) = segments(id).firstOrNull { it.startMs < 1_000 }?.endMs ?: error("no intro segment for $id")
}
