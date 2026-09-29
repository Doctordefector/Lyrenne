package com.lyrenne.desktop.integration

import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Verifies that the bundled native SMTC adapter can create a real Windows media session. */
class WindowsMediaSessionSmokeTest {

    private val onWindows =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Test
    fun `media session initializes and releases`() {
        assumeTrue("Windows-only API", onWindows)
        val player = DesktopPlayer()
        try {
            assertTrue(
                "The Windows media-session adapter could not be loaded",
                WindowsMediaSession.initialize(player)
            )
            // Started as a download, so no stream has to be resolved
            player.downloadedFileFor = { File("media-session-smoke-test") }
            runBlocking {
                player.playSong(
                    SongInfo(
                        id = "smoke-test",
                        title = "SMTC Smoke Test",
                        artist = "Lyrenne",
                        thumbnailUrl = null,
                        durationMs = 60_000
                    )
                )
            }

            val deadline = System.currentTimeMillis() + 2_000
            while (!WindowsMediaSession.active && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }

            assertTrue("The Windows media session was not enabled", WindowsMediaSession.active)
            assertNull(
                "Publishing Windows media metadata failed: ${WindowsMediaSession.lastFailure}",
                WindowsMediaSession.lastFailure
            )
        } finally {
            WindowsMediaSession.release()
            player.release()
        }
    }
}
