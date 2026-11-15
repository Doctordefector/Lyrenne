package com.lyrenne.desktop

import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.PreferencesManager
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import kotlin.system.exitProcess

/**
 * `Lyrenne --smoke-play <audio file>...`: proves a packaged build can play, with no window and no
 * network. Loads libvlc the way the app does, plays each file until its position passes one
 * second, prints one line per file, and exits 0 only if every file played.
 *
 * Built for the macOS CI job, which has no Mac to look at: it runs this from inside the signed
 * .app, so a pass means the bundled libvlc, its plugins and the codecs YouTube streams use (AAC in
 * MP4) all load from the bundle. `--require-bundled-vlc` fails the run if libvlc came from
 * anywhere else, such as an installed VLC.app that would hide a broken bundle.
 *
 * It uses the normal data folder, so the queue ends up holding the test files. CI and dev use only.
 */
object SmokeTest {
    fun run(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val files = args.dropWhile { it != "--smoke-play" }.drop(1).filter { !it.startsWith("--") }.map(::File)
        val requireBundled = "--require-bundled-vlc" in args
        if (files.isEmpty()) fail("usage: Lyrenne --smoke-play [--require-bundled-vlc] <audio file>...")
        files.firstOrNull { !it.isFile }?.let { fail("no such file: $it") }

        DatabaseHelper.initialize()
        PreferencesManager.initialize()
        val player = DesktopPlayer()
        player.autoplayAllowed = { false }
        var current: File? = null
        player.downloadedFileFor = { current }

        val started = System.nanoTime()
        player.ensureVlcInitialized()
        val initMs = (System.nanoTime() - started) / 1_000_000
        if (!player.state.value.vlcAvailable) fail("libvlc did not load: ${player.state.value.error}")
        val from = player.bundledVlcPath
        report("SMOKE vlc loaded in $initMs ms from ${from ?: "the system"}")
        if (requireBundled && from == null) fail("libvlc did not come from the app bundle")

        // macOS: publish to Now Playing like the app does, and check the system holds it.
        val nowPlaying = Platform.isMac && com.lyrenne.desktop.mac.MacNowPlaying.initialize(player)
        if (Platform.isMac && !nowPlaying) fail("Now Playing setup failed: ${com.lyrenne.desktop.mac.MacNowPlaying.lastFailure}")

        var failed = 0
        for ((i, file) in files.withIndex()) {
            current = file
            val ok = runBlocking {
                player.playSong(SongInfo(id = "smoke-$i", title = file.name, artist = "Lyrenne", thumbnailUrl = null))
            }
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val s = player.state.value
                if (s.error != null || (s.isPlaying && s.position > 1_000)) break
                Thread.sleep(100)
            }
            val s = player.state.value
            val played = ok && s.error == null && s.isPlaying && s.position > 1_000
            report("SMOKE ${if (played) "PLAYED" else "FAILED"} ${file.name}: position=${s.position} ms, duration=${s.duration} ms, error=${s.error}")
            if (!played) failed++
            if (nowPlaying && played) {
                Thread.sleep(1_500) // the observer publishes once a second
                val info = com.lyrenne.desktop.mac.MacNowPlaying.readBack()
                report("SMOKE nowplaying $info")
                if (info["title"] != file.name || info["state"] != "1") failed++
            }
            player.pause()
        }
        runCatching { player.release() }
        if (failed > 0) fail("$failed of ${files.size} files did not play")
        report("SMOKE OK")
        // VLC leaves non-daemon threads behind; the app's own quit path halts the same way.
        Runtime.getRuntime().halt(0)
    }

    /** stdout for the CI log, the app log for anyone reading lyrenne.log afterwards. */
    private fun report(line: String) {
        println(line)
        Timber.i(line)
    }

    private fun fail(why: String): Nothing {
        report("SMOKE FAILED: $why")
        System.out.flush()
        Runtime.getRuntime().halt(1)
        exitProcess(1)
    }
}
