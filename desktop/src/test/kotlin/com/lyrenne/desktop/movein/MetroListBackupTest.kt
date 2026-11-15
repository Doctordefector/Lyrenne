package com.lyrenne.desktop.movein

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reading MetroList backups. The fixtures in `src/test/resources/movein/` are real Room databases:
 * `metrolist13-schema35.sql` and `metrolist14-schema65.sql` are the CREATE statements copied from
 * upstream's own schema JSON (13.x: app/schemas/.../35.json in this repo; 14.0.0:
 * shared/schemas/com.metrolist.shared.db.MusicDatabase/65.json upstream), the `-data.sql` files
 * fill them, and the `.backup` files are those databases zipped the way each version zips them
 * (13.x: song.db + settings.preferences_pb; 14.x: backup.json + settings.json + song.db).
 * Both carry a fake login cookie, which must never come out the other side.
 */
class MetroListBackupTest {
    private val utc = ZoneId.of("UTC")
    private fun fixture(name: String) = File(javaClass.getResource("/movein/$name")!!.toURI())

    @Test
    fun `13x backup reads likes, library, playlists, plays and the user's own lyrics`() {
        val lib = MetroListBackup.read(fixture("metrolist13.backup"), utc)
        assertEquals(BackupFlavor.METROLIST_13, lib.flavor)
        assertEquals(35, lib.databaseVersion)

        // Newest like first; the phone-local voice memo is not a YouTube song and is dropped
        assertEquals(listOf("FGBhQbmPwH8", "fJ9rUzIMcZQ"), lib.likes.map { it.first })
        assertEquals(LocalDateTime.parse("2025-04-15T21:30"), lib.likes[0].second)
        assertFalse(lib.songs.keys.any { it.startsWith("content:") })
        assertEquals(setOf("x8VYWazR5mE", "fJ9rUzIMcZQ", "FGBhQbmPwH8"), lib.librarySongs.map { it.first }.toSet())

        // Followed: not the local artist, not the podcast channel
        assertEquals(listOf("UCWzwfD0NGLZ-ePBTGOpu7hg", "UCiMhD4jzUqG-IgPzUmmytRQ"), lib.artists.map { it.id })
        assertEquals(listOf("MPREb_opera"), lib.albums.map { it.id })
        assertTrue("an unsaved album is still known for stats", "MPREb_disc" in lib.albumRows)

        // The local playlist in order, minus the phone file; the YouTube one as a link; no LM
        val road = lib.playlists.single { it.id == "LPa8Kd03Jx" }
        assertTrue(road.isLocal)
        assertEquals(listOf("FGBhQbmPwH8", "GDpmVUEjagg", "fJ9rUzIMcZQ"), road.songIds)
        assertEquals(listOf("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"), lib.youTubePlaylists.map { it.browseId })
        assertFalse(lib.playlists.any { it.browseId == "LM" })

        // Plays, oldest first, wall clock read back as the time it was
        assertEquals(4, lib.plays.size)
        assertEquals(LocalDateTime.parse("2024-02-14T20:00").atZone(utc).toInstant().toEpochMilli(), lib.plays.first().epochMs)
        assertEquals(354000L, lib.plays.first().playTimeMs)
        assertEquals(2024, lib.plays.minOf { java.time.Instant.ofEpochMilli(it.epochMs).atZone(utc).year })

        // Only "Manual" lyrics are the user's; the LrcLib row is a cache, not-found is nothing
        assertEquals(listOf(MlLyrics("fJ9rUzIMcZQ", "Edited", "[00:01.00]Is this the real life?")), lib.lyrics)
        assertEquals(mapOf("FGBhQbmPwH8" to 500L), lib.lyricsOffsets)

        // Artist credits come with the song, in order
        assertEquals(listOf(MlArtistRef("UCiMhD4jzUqG-IgPzUmmytRQ", "Queen")), lib.songs.getValue("fJ9rUzIMcZQ").artists)
        assertEquals("夜に駆ける", lib.songs.getValue("x8VYWazR5mE").title)
    }

    @Test
    fun `14x backup takes likes from settings json and skips plays pulled from YouTube`() {
        val lib = MetroListBackup.read(fixture("metrolist14.backup"), utc)
        assertEquals(BackupFlavor.METROLIST_14, lib.flavor)
        assertEquals(65, lib.databaseVersion)

        // settings.json order first (newest first there), then the DB's own like
        assertEquals(listOf("fJ9rUzIMcZQ", "4NRXx6U8ABQ", "FGBhQbmPwH8"), lib.likes.map { it.first })
        // Dates keep that order
        assertTrue(lib.likes.zipWithNext().all { (a, b) -> a.second >= b.second })
        // A liked song that only exists in settings comes with its metadata
        val blinding = lib.songs.getValue("4NRXx6U8ABQ")
        assertEquals("Blinding Lights", blinding.title)
        assertEquals(listOf(MlArtistRef("UClYV6hHlupm_S_ObS1W-DYw", "The Weeknd")), blinding.artists)
        assertEquals("After Hours", blinding.albumName)
        assertEquals(201, blinding.durationSec)
        // A renamed song keeps its real title
        assertEquals("Bohemian Rhapsody", lib.songs.getValue("fJ9rUzIMcZQ").title)

        // The YouTube-history play (playTimeKnown = 0) is not a MetroList play
        assertEquals(listOf("fJ9rUzIMcZQ", "FGBhQbmPwH8"), lib.plays.map { it.songId })

        // Saved via likedDate in 14.x
        assertEquals(listOf("MPREb_opera"), lib.albums.map { it.id })
        assertEquals(listOf("Late night"), lib.localPlaylists.map { it.name })

        // userLyrics is an edit; isUserSelected is a pick with its provider; offsetMs carries over
        assertEquals(
            setOf(
                MlLyrics("FGBhQbmPwH8", "Edited", "[00:30.00]One more time (my edit)"),
                MlLyrics("x8VYWazR5mE", "BetterLyrics", "[00:01.00]沈むように")
            ),
            lib.lyrics.toSet()
        )
        assertEquals(mapOf("x8VYWazR5mE" to -250L), lib.lyricsOffsets)
    }

    @Test
    fun `the login cookie in either backup is never read into the result`() {
        for (name in listOf("metrolist13.backup", "metrolist14.backup")) {
            val lib = MetroListBackup.read(fixture(name), utc)
            assertFalse(name, lib.toString().contains("SAPISID"))
            assertFalse(name, lib.toString().contains("fixture-not-a-real-cookie"))
        }
    }

    @Test
    fun `a bare song db works too`() {
        val dir = kotlin.io.path.createTempDirectory("movein").toFile()
        try {
            val db = File(dir, "song.db")
            java.util.zip.ZipFile(fixture("metrolist13.backup")).use { z ->
                z.getInputStream(z.getEntry("song.db")).use { input -> db.outputStream().use { input.copyTo(it) } }
            }
            assertEquals(2, MetroListBackup.read(db, utc).likes.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `things that are not a backup say so`() {
        val dir = kotlin.io.path.createTempDirectory("movein").toFile()
        try {
            val text = File(dir, "notes.backup").apply { writeText("hello") }
            try { MetroListBackup.read(text); fail() } catch (e: MetroListBackup.NotABackup) { }
            val zip = File(dir, "other.zip")
            java.util.zip.ZipOutputStream(zip.outputStream()).use { it.putNextEntry(java.util.zip.ZipEntry("readme.txt")); it.write(1) }
            try { MetroListBackup.read(zip); fail() } catch (e: MetroListBackup.NotABackup) {
                assertEquals("This file has no MetroList library in it.", e.message)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing like dates keep newest-first order`() {
        val a = LocalDateTime.parse("2025-01-10T10:00")
        val dated = MetroListBackup.datedInOrder(listOf("x" to null, "y" to a, "z" to null, "w" to a.plusDays(5)))
        assertEquals(listOf("x", "y", "z", "w"), dated.map { it.first })
        assertTrue(dated.zipWithNext().all { (p, n) -> p.second > n.second })
    }

    @Test
    fun `settings without likes is not an error`() {
        assertNull(MetroListBackup.likedSongsFromSettings("""{"formatVersion":1,"entries":[]}"""))
        assertNull(MetroListBackup.likedSongsFromSettings("not json"))
    }
}
