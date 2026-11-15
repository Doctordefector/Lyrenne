package com.lyrenne.desktop.link

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

/**
 * `now-playing.txt`, `now-playing.json` and `cover.jpg` in a folder the user picks, for OBS text and
 * image sources and Streamer.bot. Off by default. Each file is written next to itself and moved
 * into place, so a reader polling it never sees half a file.
 */
object NowPlayingExport {
    const val TEXT = "now-playing.txt"
    const val JSON = "now-playing.json"
    const val COVER = "cover.jpg"

    /** "Artist - Title", or empty when nothing is loaded so an OBS text source goes blank. */
    fun text(snap: LinkSnapshot): String = snap.song?.let { s ->
        val artist = LinkJson.cleanArtist(s.artist)
        if (artist.isBlank()) s.title else "$artist - ${s.title}"
    }.orEmpty()

    fun json(snap: LinkSnapshot, coverPath: String?): String = buildJsonObject {
        val s = snap.song
        put("playing", s != null && snap.playing)
        put("title", s?.title)
        put("artist", s?.artist?.let { LinkJson.cleanArtist(it) })
        put("album", s?.album)
        put("durationMs", snap.durationMs)
        put("url", s?.let { "https://music.youtube.com/watch?v=${it.id}" })
        put("cover", coverPath)
        put("text", text(snap))
        put("updatedAt", System.currentTimeMillis())
    }.toString()

    fun write(folder: File, snap: LinkSnapshot, art: ByteArray?) {
        folder.mkdirs()
        val cover = File(folder, COVER)
        val jpeg = art?.let { toJpeg(it) }
        if (jpeg != null) atomicWrite(cover, jpeg)
        else if (snap.song == null) cover.delete()
        atomicWrite(File(folder, TEXT), text(snap).toByteArray())
        atomicWrite(File(folder, JSON), json(snap, cover.takeIf { it.exists() }?.absolutePath).toByteArray())
    }

    /** YouTube serves PNG or WebP for some covers; OBS image sources want what the name says. */
    internal fun toJpeg(bytes: ByteArray): ByteArray? {
        if (bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return bytes
        val img = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val rgb = java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        return java.io.ByteArrayOutputStream().also { ImageIO.write(rgb, "jpg", it) }.toByteArray()
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.io.IOException) {
            // Windows refuses to replace a file another program holds open (OBS reading it).
            // A plain write usually still gets through; a reader may then catch it mid-write once.
            runCatching { target.writeBytes(bytes) }
            tmp.delete()
        }
    }
}
