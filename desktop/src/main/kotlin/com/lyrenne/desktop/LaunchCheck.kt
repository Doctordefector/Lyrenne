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
 *
 * Each `--smoke-url <url>` additionally plays that URL. Over HTTPS it proves the bundled TLS stack
 * trusts the host's certificate store (a Debian-built libVLC on Fedora, say); `file://` URLs of
 * AAC, Opus, MP3 and FLAC files prove the bundled decoders.
 */
internal object LaunchCheck {

    /** @param args the whole command line; each `--smoke-url <url>` also plays that URL through libVLC. */
    fun run(args: Array<String>): Nothing {
        System.setProperty("java.awt.headless", "true")
        val lines = mutableListOf<String>()
        fun say(line: String) { lines += line; println(line) }

        val ok = try {
            check(::say) && args.indices.filter { args[it] == "--smoke-url" }
                .mapNotNull { args.getOrNull(it + 1) }.all { stream(it, ::say) }
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
                return mpris(player, say)
            }
            Thread.sleep(100)
        }
        val s = player.state.value
        say("FAIL: playback did not advance (playing=${s.isPlaying}, position=${s.position}, error=${s.error})")
        return false
    }

    /**
     * Linux with a session bus: the MPRIS player must come up under its own name. Inside the
     * Flatpak that proves the manifest lets the app own org.mpris.MediaPlayer2.Lyrenne.
     */
    private fun mpris(player: DesktopPlayer, say: (String) -> Unit): Boolean {
        if (!Platform.isLinux || System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) return true
        val session = com.lyrenne.desktop.integration.SystemMediaSession
        session.initialize(player)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !session.active && session.lastFailure == null) Thread.sleep(100)
        return if (session.active && session.lastFailure == null) {
            say("mpris: org.mpris.MediaPlayer2.Lyrenne")
            true
        } else {
            say("FAIL: MPRIS session did not come up: ${session.lastFailure}")
            false
        }
    }

    /** Plays [url] straight through libVLC: HTTPS access, TLS trust, demux and decode. */
    private fun stream(url: String, say: (String) -> Unit): Boolean {
        // libVLC's own errors name the cause (TLS trust, a missing plugin) that a stuck player hides.
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val factory = object : uk.co.caprica.vlcj.factory.MediaPlayerFactory("--no-video") {
            val log = uk.co.caprica.vlcj.log.NativeLog(libvlcInstance).apply {
                setLevel(uk.co.caprica.vlcj.log.LogLevel.ERROR)
                addLogListener { _, module, _, _, _, _, _, message -> if (errors.size < 8) errors += "[$module] $message" }
            }
        }
        val mp = factory.mediaPlayers().newMediaPlayer()
        try {
            mp.media().play(url)
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                if (mp.status().isPlaying && mp.status().time() > 1_000) {
                    say("streamed: ${mp.status().time()} ms of $url")
                    return true
                }
                Thread.sleep(100)
            }
            say("FAIL: stream did not advance (state=${mp.status().state()}, time=${mp.status().time()}) for $url")
            errors.forEach { say("  vlc: $it") }
            return false
        } finally {
            factory.log.release()
            mp.release()
            factory.release()
        }
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
