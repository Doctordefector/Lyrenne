package com.lyrenne.desktop.ui.stage

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.lyrenne.desktop.lyrics.LyricLine
import com.lyrenne.desktop.lyrics.LyricWord
import com.lyrenne.desktop.rewind.RewindArtistRef
import com.lyrenne.desktop.rewind.RewindCalculator
import com.lyrenne.desktop.rewind.RewindPeriod
import com.lyrenne.desktop.rewind.RewindPlay
import com.lyrenne.desktop.rewind.RewindReport
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Point
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Made-up but plausible data for rendering Stage and Rewind in tests. No real artists. */
object StageRewindFixtures {
    val zone: ZoneOffset = ZoneOffset.UTC

    private val palettes = listOf(
        intArrayOf(0xFFE85D75.toInt(), 0xFF6A2C70.toInt()),
        intArrayOf(0xFF2EC4B6.toInt(), 0xFF011627.toInt()),
        intArrayOf(0xFFFF9F1C.toInt(), 0xFF8A2E10.toInt()),
        intArrayOf(0xFF7B2CBF.toInt(), 0xFF10002B.toInt()),
        intArrayOf(0xFF4CC9F0.toInt(), 0xFF3A0CA3.toInt()),
        intArrayOf(0xFFB5E48C.toInt(), 0xFF1E6091.toInt()),
        intArrayOf(0xFFF15BB5.toInt(), 0xFF00BBF9.toInt()),
        intArrayOf(0xFFFEE440.toInt(), 0xFFF15BB5.toInt()),
    )

    /** A cover: a diagonal gradient with a few soft circles, PNG-encoded. */
    fun coverPng(seed: Int, size: Int = 300): ByteArray {
        val surface = Surface.makeRasterN32Premul(size, size)
        val c: Canvas = surface.canvas
        val p = palettes[seed % palettes.size]
        c.drawRect(Rect.makeWH(size.toFloat(), size.toFloat()), Paint().apply {
            shader = Shader.makeLinearGradient(Point(0f, 0f), Point(size.toFloat(), size.toFloat()), p)
        })
        val rnd = java.util.Random(seed.toLong())
        repeat(4) {
            c.drawCircle(
                rnd.nextFloat() * size, rnd.nextFloat() * size, size * (0.12f + rnd.nextFloat() * 0.3f),
                Paint().apply { color = if (it % 2 == 0) 0x55FFFFFF else 0x44000000 }
            )
        }
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    fun coverBitmap(seed: Int): ImageBitmap =
        Bitmap.makeFromImage(Image.makeFromEncoded(coverPng(seed))).asComposeImageBitmap()

    /** Cover files on disk, for code that loads by URL (Stage, through Coil). */
    fun coverFile(seed: Int, dir: File): String {
        val f = File(dir, "cover-$seed.png")
        if (!f.exists()) { dir.mkdirs(); f.writeBytes(coverPng(seed)) }
        return f.absolutePath
    }

    private val artists = listOf(
        "Aurora Lane", "Kite Theory", "Mira Sol", "The Paper Lanterns", "Juniper Vale",
        "Static Bloom", "Odessa Rain", "Low Orbit", "Cass Ember", "Northbound Tide",
    )
    private val songs = listOf(
        "Neon Rain", "Paper Boats", "Slow Tide", "Golden Hour Again", "Satellites",
        "Glass Houses", "Late Train Home", "Wildflower Season", "Echo Park Lights", "Midnight Bakery",
        "Ferris Wheel", "Velvet Static", "All the Small Fires", "Sunday Radio", "Weightless",
    )

    /** A year of listening: favourites, a mid-year discovery, a binge day, evening-heavy hours. */
    fun history(): List<RewindPlay> {
        val out = ArrayList<RewindPlay>()
        val rnd = java.util.Random(7)
        fun add(songIdx: Int, at: LocalDateTime, minutes: Int = 3 + rnd.nextInt(3)) {
            val artistIdx = if (songIdx >= 12) 8 else songIdx % 8
            val start = at.toInstant(zone).toEpochMilli()
            out += RewindPlay(
                songId = "song$songIdx",
                title = songs[songIdx],
                artists = listOf(RewindArtistRef("artist$artistIdx", artists[artistIdx], "art:${artistIdx + 20}")),
                albumId = "album${songIdx / 2}",
                albumTitle = listOf("First Light", "City Static", "Tidewater", "Low Sun", "Ghost Notes", "Harbour", "Afterglow", "Sideways")[songIdx / 2 % 8],
                thumbnailUrl = "art:$songIdx",
                endedAt = start + minutes * 60_000L,
                playTimeMs = minutes * 60_000L,
            )
        }
        // Last year, so a discovery means something.
        repeat(30) { add(it % 8, LocalDateTime.of(2025, 10, 1, 20, 0).plusDays(it.toLong())) }
        var day = LocalDateTime.of(2026, 1, 1, 0, 0)
        while (day.year == 2026 && day.monthValue <= 11) {
            if (rnd.nextInt(10) < 8) {
                val sessions = 1 + rnd.nextInt(3)
                repeat(sessions) {
                    val hour = listOf(8, 13, 19, 21, 22, 23, 23, 22, 18)[rnd.nextInt(9)]
                    var t = day.withHour(hour).withMinute(rnd.nextInt(40))
                    repeat(2 + rnd.nextInt(6)) {
                        val weighted = (rnd.nextGaussian().let { g -> (kotlin.math.abs(g) * 3.5).toInt() }).coerceAtMost(11)
                        add(if (day.monthValue >= 6 && rnd.nextInt(4) == 0) 12 + rnd.nextInt(3) else weighted, t)
                        t = t.plusMinutes(4)
                    }
                }
            }
            day = day.plusDays(1)
        }
        // One song on loop all of a Saturday.
        repeat(11) { add(3, LocalDateTime.of(2026, 6, 13, 10, 0).plusMinutes(it * 25L)) }
        return out
    }

    fun report(period: RewindPeriod = RewindPeriod.year(2026)): RewindReport = RewindCalculator.compute(history(), period, zone)

    /** Pictures for every art key the fixture history uses. */
    fun images(): Map<String, ImageBitmap> = (0 until 30).associate { "art:$it" to coverBitmap(it) }

    /** Synced lines with word timing, two seconds a word. */
    fun lyrics(): List<LyricLine> {
        val text = listOf(
            "", "Neon rain on the window", "Every light in the city is humming", "I can hear you in the static",
            "夜空に光る星", "Hold on, the night is young", "And the trains run late again", "Paper boats on a silver river",
        )
        var t = 4_000L
        return text.map { line ->
            val words = line.split(' ').filter { it.isNotEmpty() }.map { w ->
                val start = t; t += 700; LyricWord(w, start, start + 600)
            }
            val l = LyricLine(timeMs = words.firstOrNull()?.startMs ?: t, text = line, words = words.takeIf { line.isNotBlank() })
            t += 600
            l
        }
    }
}
