package com.lyrenne.desktop.link

import com.lyrenne.desktop.playback.SongInfo
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class NowPlayingExportTest {
    private val song = SongInfo(id = "abc", title = "Title", artist = "Artist", thumbnailUrl = null, album = "Album", duration = 100)
    private fun snap(s: SongInfo?, playing: Boolean = true) =
        LinkSnapshot(s, playing, 0, 100_000, 1f, false, "OFF", false, null, 0, 1)

    @Test
    fun `writes text, json and a jpeg cover`() {
        val dir = Files.createTempDirectory("np").toFile()
        try {
            val png = ByteArrayOutputStream().also {
                ImageIO.write(java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", it)
            }.toByteArray()
            NowPlayingExport.write(dir, snap(song), png)
            assertEquals("Artist - Title", File(dir, "now-playing.txt").readText())
            val json = Json.parseToJsonElement(File(dir, "now-playing.json").readText()).jsonObject
            assertEquals("Title", json["title"]!!.jsonPrimitive.content)
            assertEquals(true, json["playing"]!!.jsonPrimitive.boolean)
            assertEquals("https://music.youtube.com/watch?v=abc", json["url"]!!.jsonPrimitive.content)
            val cover = File(dir, "cover.jpg").readBytes()
            // Converted: OBS image sources go by the extension.
            assertTrue(cover[0] == 0xFF.toByte() && cover[1] == 0xD8.toByte())
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })

            // Nothing loaded: the text goes blank and the cover goes away.
            NowPlayingExport.write(dir, snap(null), null)
            assertEquals("", File(dir, "now-playing.txt").readText())
            assertFalse(File(dir, "cover.jpg").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a song without an artist is just its title`() {
        assertEquals("Title", NowPlayingExport.text(snap(song.copy(artist = ""))))
    }
}
