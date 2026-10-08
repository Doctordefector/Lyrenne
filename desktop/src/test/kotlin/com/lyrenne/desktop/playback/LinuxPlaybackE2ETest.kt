package com.lyrenne.desktop.playback

import com.lyrenne.desktop.Platform
import com.lyrenne.desktop.integration.SystemMediaSession
import com.lyrenne.desktop.settings.PreferencesManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.sin

/**
 * The plan's Phase 1 playback criteria, against the distro's real VLC: a local file plays, EQ,
 * speed, normalize and skip-silence apply without error, and the MPRIS session is visible to and
 * controllable from `playerctl`. Linux CI only (LYRENNE_LINUX_E2E), run inside a D-Bus session
 * with a null PulseAudio sink, because it needs all three.
 */
class LinuxPlaybackE2ETest {

    @Test
    fun `local file plays through system VLC and answers MPRIS`() {
        assumeTrue(Platform.isLinux && System.getenv("LYRENNE_LINUX_E2E") != null)

        val wav = File.createTempFile("lyrenne-e2e", ".wav").apply { deleteOnExit() }
        writeTone(wav, seconds = 20)

        com.lyrenne.desktop.db.DatabaseHelper.initialize()
        PreferencesManager.initialize()
        PreferencesManager.setNormalizeAudio(true)
        PreferencesManager.setSkipSilence(true)
        PreferencesManager.setEqEnabled(true)

        val player = DesktopPlayer()
        player.downloadedFileFor = { wav }
        player.autoplayAllowed = { false }
        try {
            player.ensureVlcInitialized()
            assertTrue("system VLC not loaded: ${player.state.value.error}", player.state.value.vlcAvailable)
            assertTrue("MPRIS session not created: ${SystemMediaSession.lastFailure}", SystemMediaSession.initialize(player))

            runBlocking {
                player.playSong(SongInfo(id = "e2e", title = "Lyrenne E2E Tone", artist = "Lyrenne", thumbnailUrl = null, durationMs = 20_000))
            }
            waitFor("playback to advance") { player.state.value.isPlaying && player.state.value.position > 1_000 }

            player.setEqualizerPreset("Rock")
            player.setPlaybackSpeed(1.5f)
            assertNull("player reported an error", player.state.value.error)

            waitFor("playerctl to see the title", diagnose = {
                "players=[${playerctl("-l")}] metadata=[${playerctl("metadata")}] status=[${playerctl("status")}] " +
                    "busNames=[${run("dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus.ListNames")}]"
            }) { playerctl("metadata", "title") == "Lyrenne E2E Tone" }
            assertEquals("Playing", playerctl("status"))
            playerctl("pause")
            waitFor("MPRIS pause to reach the player") { !player.state.value.isPlaying }
        } finally {
            SystemMediaSession.release()
            player.release()
        }
    }

    private fun playerctl(vararg args: String): String = run("playerctl", *args)

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return out
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, diagnose: () -> String = { "" }, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() >= deadline) throw AssertionError("timed out waiting for $what. ${diagnose()}")
            Thread.sleep(100)
        }
    }

    /** A 440 Hz tone; no binary fixture in the repo. */
    private fun writeTone(file: File, seconds: Int) {
        val rate = 44_100f
        val samples = (rate * seconds).toInt()
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (sin(2 * Math.PI * 440 * i / rate) * 8_000).toInt()
            bytes[2 * i] = (v and 0xFF).toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        val format = AudioFormat(rate, 16, 1, true, false)
        AudioSystem.write(AudioInputStream(bytes.inputStream(), format, samples.toLong()), AudioFileFormat.Type.WAVE, file)
    }
}
