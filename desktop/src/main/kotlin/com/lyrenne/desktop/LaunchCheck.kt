package com.lyrenne.desktop

import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.PreferencesManager
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * `Lyrenne --smoke-test`: proves a packaged build works on this machine without a window or a
 * network. It resolves where data goes, opens the database there, loads libVLC through the same
 * path the player uses, and plays a generated tone until the position moves.
 *
 * Built for CI, which runs it on clean machines against every package (portable ZIP in a fake
 * winget folder, AppImage on several distros, Flatpak). The result goes to stdout and to
 * `smoke-test.txt` in the data dir, because the Windows launcher is a GUI exe with no console.
 * Exit code 0 means it played.
 */
internal object LaunchCheck {

    fun run(): Nothing {
        System.setProperty("java.awt.headless", "true")
        val lines = mutableListOf<String>()
        fun say(line: String) { lines += line; println(line) }

        val ok = try {
            check(::say)
        } catch (e: Throwable) {
            say("FAIL: ${e::class.simpleName}: ${e.message}")
            false
        }
        say(if (ok) "SMOKE OK" else "SMOKE FAILED")
        runCatching { File(AppPaths.dataDir, "smoke-test.txt").writeText(lines.joinToString("\n", postfix = "\n")) }
        System.out.flush()
        // The player's threads are not daemons; leave without waiting for them.
        Runtime.getRuntime().halt(if (ok) 0 else 1)
        exitProcess(1)
    }

    private fun check(say: (String) -> Unit): Boolean {
        say("version: ${com.lyrenne.desktop.update.AutoUpdater.CURRENT_VERSION}")
        say("os: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        say("app dir: ${AppPaths.appDir}")
        say("layout: ${AppPaths.layout.kind}")
        say("data dir: ${AppPaths.dataDir}")
        say("downloads: ${AppPaths.defaultDownloadsDir}")
        say("update route: ${if (com.lyrenne.desktop.update.AutoUpdater.canSelfUpdate) "self" else com.lyrenne.desktop.update.AutoUpdater.updateHint}")

        DatabaseHelper.initialize()
        PreferencesManager.initialize()
        if (!AppPaths.databaseFile.exists()) {
            say("FAIL: no database at ${AppPaths.databaseFile}")
            return false
        }

        val tone = File.createTempFile("lyrenne-smoke", ".wav").apply { deleteOnExit() }
        writeTone(tone, seconds = 10)

        val player = DesktopPlayer()
        player.downloadedFileFor = { tone }
        player.autoplayAllowed = { false }
        player.ensureVlcInitialized()
        if (!player.state.value.vlcAvailable) {
            say("FAIL: libVLC did not load: ${player.state.value.error}")
            return false
        }
        say("libvlc: ${runCatching { uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_get_version() }.getOrElse { "?" }}")
        say("libvlc file: ${runCatching { com.sun.jna.NativeLibrary.getInstance(uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil.getLibVlcLibraryName()).file }.getOrNull()}")

        runBlocking {
            player.playSong(SongInfo(id = "smoke", title = "Lyrenne smoke test", artist = "Lyrenne", thumbnailUrl = null, durationMs = 10_000))
        }
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val s = player.state.value
            if (s.isPlaying && s.position > 1_000) {
                say("played: ${s.position} ms")
                return true
            }
            Thread.sleep(100)
        }
        val s = player.state.value
        say("FAIL: playback did not advance (playing=${s.isPlaying}, position=${s.position}, error=${s.error})")
        return false
    }

    /** A 440 Hz tone, so the check needs no fixture file in the package. */
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
