package com.lyrenne.desktop.mac

import com.lyrenne.desktop.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Objective-C plumbing behind macOS Now Playing, on a real Mac (CI): what is published reads
 * back from MPNowPlayingInfoCenter, and a remote command sent to the runtime-made target class
 * through objc_msgSend reaches Kotlin. A media key press itself cannot be simulated on a runner.
 * macOS CI only (LYRENNE_MAC_E2E).
 */
class MacNowPlayingE2ETest {

    @Test
    fun `now playing reads back and commands reach the handler`() {
        assumeTrue(Platform.isMac && System.getenv("LYRENNE_MAC_E2E") != null)
        val received = mutableListOf<String>()
        assertTrue("setup failed: ${MacNowPlaying.lastFailure}", MacNowPlaying.initializeForTest { name, _ -> received += name })

        val png = java.io.File.createTempFile("cover", ".png").apply { deleteOnExit() }
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png)
        assertTrue("NSImage could not read the cover", MacNowPlaying.setArtwork(png))
        MacNowPlaying.publish("Lyrenne Tone", "Lyrenne", "Album", 200_000, 5_000, playing = true)
        val info = MacNowPlaying.readBack()
        println("now playing: $info")
        assertEquals("Lyrenne Tone", info["title"])
        assertEquals("Lyrenne", info["artist"])
        assertEquals("200", info["duration"])
        assertEquals("1", info["state"])
        assertEquals("the artwork block gave no image", "image", info["artwork"])

        MacNowPlaying.publish("Lyrenne Tone", "Lyrenne", null, 200_000, 6_000, playing = false)
        assertEquals("2", MacNowPlaying.readBack()["state"])

        MacNowPlaying.dispatchForTest("lyrenneToggle:")
        MacNowPlaying.dispatchForTest("lyrenneNext:")
        assertEquals(listOf("toggle", "next"), received)
        MacNowPlaying.release()
        assertEquals("3", MacNowPlaying.readBack()["state"])
    }
}
