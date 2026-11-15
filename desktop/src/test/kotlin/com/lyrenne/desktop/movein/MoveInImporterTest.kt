package com.lyrenne.desktop.movein

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.lyrenne.desktop.db.LyrenneDatabase
import com.lyrenne.desktop.db.LyrenneQueries
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Writing a MetroList library into Lyrenne's schema: a merge that never overwrites, adds nothing
 * on a second run, and leaves sync nothing to undo.
 */
class MoveInImporterTest {
    private lateinit var db: LyrenneDatabase
    private val q: LyrenneQueries get() = db.lyrenneQueries
    private lateinit var ledgerFile: File
    private val lyrics = FakeLyrics()
    private val utc = ZoneId.of("UTC")

    class FakeLyrics : LyricsSink {
        val texts = HashMap<String, Pair<String, String>>()
        val offsets = HashMap<String, Long>()
        override fun hasLyrics(songId: String) = songId in texts
        override fun putLyrics(songId: String, source: String, text: String) { texts[songId] = source to text }
        override fun offset(songId: String) = offsets[songId] ?: 0L
        override fun setOffset(songId: String, ms: Long) { offsets[songId] = ms }
    }

    @Before
    fun setUp() {
        db = LyrenneDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LyrenneDatabase.Schema.create(it) })
        ledgerFile = File.createTempFile("movein", ".json").also { it.delete() }
        MoveInLedger.file = ledgerFile
    }

    @After
    fun tearDown() { ledgerFile.delete() }

    private fun importer() = MoveInImporter(
        transaction = { body -> db.transaction { body(q) } },
        lyricsSink = lyrics,
        now = { LocalDateTime.parse("2026-11-15T12:00") }
    )

    private fun fixture(name: String) =
        MetroListBackup.read(File(javaClass.getResource("/movein/$name")!!.toURI()), utc)

    @Test
    fun `a 13x library lands in the Library, playlists and stats`() {
        val r = importer().import(fixture("metrolist13.backup"), MoveInOptions())

        assertEquals(2, r.newLikes)
        val liked = q.getLikedSongs().executeAsList()
        assertEquals(listOf("FGBhQbmPwH8", "fJ9rUzIMcZQ"), liked.map { it.id })
        assertEquals("2025-04-15T21:30", liked[0].likedDate)
        // Imported likes stay out of inLibrary so sync's prune cannot unlike them
        assertTrue(liked.all { it.inLibrary == null })
        // The library song that was not liked shows in Songs
        assertEquals(listOf("x8VYWazR5mE"), q.getAllSongs().executeAsList().map { it.id })
        assertEquals(1, r.newLibrarySongs)

        // Credits, with names
        assertEquals("Queen", q.getArtistNamesForSong("fJ9rUzIMcZQ").executeAsOne())

        // Local playlist in order under a derived local id; YouTube playlist as a link
        val roadId = MoveInImporter.localPlaylistId("LPa8Kd03Jx")
        assertEquals(listOf("FGBhQbmPwH8", "GDpmVUEjagg", "fJ9rUzIMcZQ"), q.getSongsForPlaylist(roadId).executeAsList().map { it.id })
        assertNull(q.getPlaylistById(roadId).executeAsOne().browseId)
        val yt = q.getPlaylistById("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf").executeAsOne()
        assertEquals("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf", yt.browseId)
        assertEquals(0L, q.getPlaylistSongCount(yt.id).executeAsOne())
        assertEquals(2, r.newPlaylists)
        assertNull("LM is covered by likes", q.getPlaylistById("LM").executeAsOneOrNull())

        // Saved album via inLibrary (sync never prunes it), followed artists in the ledger
        val album = q.getAlbumById("MPREb_opera").executeAsOne()
        assertNotNull(album.inLibrary)
        assertNull(album.bookmarkedAt)
        assertEquals(2, q.getAllArtists().executeAsList().size)
        assertTrue(MoveInLedger.isLocalArtist("UCiMhD4jzUqG-IgPzUmmytRQ"))

        // History: 4 plays (the phone file's play is dropped), totals, stats by album
        assertEquals(4L, q.getEventCount().executeAsOne())
        assertEquals(654000L, q.getSongById("fJ9rUzIMcZQ").executeAsOne().totalPlayTime)
        val all = q.mostPlayedAlbums(0, Long.MAX_VALUE).executeAsList()
        assertEquals(setOf("MPREb_opera", "MPREb_disc"), all.map { it.id }.toSet())

        // Lyrics and timing
        assertEquals("Edited" to "[00:01.00]Is this the real life?", lyrics.texts["fJ9rUzIMcZQ"])
        assertEquals(500L, lyrics.offsets["FGBhQbmPwH8"])
    }

    @Test
    fun `importing the same backup twice adds nothing`() {
        val lib = fixture("metrolist13.backup")
        importer().import(lib, MoveInOptions())
        val events = q.getEventCount().executeAsOne()
        val road = MoveInImporter.localPlaylistId("LPa8Kd03Jx")
        val roadSongs = q.getPlaylistSongCount(road).executeAsOne()

        val again = importer().import(lib, MoveInOptions())
        assertTrue(again.toString(), again.nothingNew)
        assertEquals(2, again.alreadyLiked)
        assertEquals(4, again.skippedPlays)
        assertEquals(events, q.getEventCount().executeAsOne())
        assertEquals(roadSongs, q.getPlaylistSongCount(road).executeAsOne())
        assertEquals(654000L, q.getSongById("fJ9rUzIMcZQ").executeAsOne().totalPlayTime)
    }

    @Test
    fun `what Lyrenne already has is never overwritten`() {
        // A song Lyrenne knows under its own title, liked earlier here
        q.insertSong("fJ9rUzIMcZQ", "Bohemian Rhapsody (Remastered 2011)", 354, "lyrenne-thumb", null, null, 0, null,
            1, "2020-01-01T00:00", 0, "2020-01-01T00:00", null, 0, 0, null)
        // An artist subscribed through sync, with its own thumbnail
        q.insertArtist("UCiMhD4jzUqG-IgPzUmmytRQ", "Queen", "sync-thumb", null, "now", "2019-01-01T00:00")
        // A playlist the user already reordered and renamed after an earlier import
        val road = MoveInImporter.localPlaylistId("LPa8Kd03Jx")
        q.insertPlaylist(road, "Road trip (mine)", null, "2025-01-01", "2025-01-01", 1, null, null, null)
        q.insertPlaylistSong(road, "fJ9rUzIMcZQ", 0)
        lyrics.texts["fJ9rUzIMcZQ"] = "Mine" to "my words"

        val r = importer().import(fixture("metrolist13.backup"), MoveInOptions())

        val song = q.getSongById("fJ9rUzIMcZQ").executeAsOne()
        assertEquals("Bohemian Rhapsody (Remastered 2011)", song.title)
        assertEquals("lyrenne-thumb", song.thumbnailUrl)
        assertEquals("2020-01-01T00:00", song.likedDate)
        assertEquals("Gaps are filled", "MPREb_opera", song.albumId)
        assertEquals(1, r.alreadyLiked)

        val artist = q.getArtistById("UCiMhD4jzUqG-IgPzUmmytRQ").executeAsOne()
        assertEquals("2019-01-01T00:00", artist.bookmarkedAt)
        assertEquals("sync-thumb", artist.thumbnailUrl)
        assertTrue("already followed through sync, so not the import's", !MoveInLedger.isLocalArtist(artist.id))

        // Existing playlist: name kept, its song stays first, the missing ones go on the end
        assertEquals("Road trip (mine)", q.getPlaylistById(road).executeAsOne().name)
        assertEquals(listOf("fJ9rUzIMcZQ", "FGBhQbmPwH8", "GDpmVUEjagg"), q.getSongsForPlaylist(road).executeAsList().map { it.id })
        assertEquals(1, r.mergedPlaylists)

        assertEquals("Mine" to "my words", lyrics.texts["fJ9rUzIMcZQ"])
    }

    @Test
    fun `unticked parts are left alone`() {
        importer().import(fixture("metrolist13.backup"), MoveInOptions(likes = false, history = false, playlists = false))
        assertEquals(0, q.getLikedSongs().executeAsList().size)
        assertEquals(0L, q.getEventCount().executeAsOne())
        assertTrue(q.getAllPlaylists().executeAsList().isEmpty())
        assertEquals(3, q.getAllSongs().executeAsList().size)
    }

    @Test
    fun `a 14x library imports its settings likes and its edited lyrics`() {
        val r = importer().import(fixture("metrolist14.backup"), MoveInOptions())
        assertEquals(3, r.newLikes)
        assertEquals(listOf("fJ9rUzIMcZQ", "4NRXx6U8ABQ", "FGBhQbmPwH8"), q.getLikedSongs().executeAsList().map { it.id })
        assertEquals("The Weeknd", q.getArtistNamesForSong("4NRXx6U8ABQ").executeAsOne())
        assertEquals(2L, q.getEventCount().executeAsOne())
        assertEquals("Edited", lyrics.texts["FGBhQbmPwH8"]?.first)
        assertEquals(-250L, lyrics.offsets["x8VYWazR5mE"])
        assertEquals(listOf("x8VYWazR5mE", "FGBhQbmPwH8"),
            q.getSongsForPlaylist(MoveInImporter.localPlaylistId("LPq7Wm2Zx1")).executeAsList().map { it.id })
    }

    @Test
    fun `a big library imports in one quick transaction`() {
        val songs = (0 until 12_000).associate { i ->
            "v$i" to MlSong("v$i", "Song $i", listOf(MlArtistRef("UC${i % 400}", "Artist ${i % 400}")), "MPREb${i % 900}", "Album ${i % 900}", 200)
        }
        val start = LocalDateTime.parse("2024-01-01T00:00")
        val lib = MetroListLibrary(
            flavor = BackupFlavor.METROLIST_13, databaseVersion = 35, songs = songs,
            likes = (0 until 1_500).map { "v$it" to start.plusMinutes(1_500L - it) },
            librarySongs = (0 until 4_000).map { "v$it" to start },
            artists = emptyList(), albums = emptyList(), albumRows = emptyMap(),
            playlists = (0 until 20).map { p -> MlPlaylist("LP$p", "List $p", null, start, true, null, (0 until 300).map { "v${p * 300 + it}" }) },
            plays = (0 until 40_000).map { MlPlay("v${it % 12_000}", 1_700_000_000_000L + it * 60_000L, 180_000) },
            lyrics = emptyList(), lyricsOffsets = emptyMap()
        )
        val t0 = System.nanoTime()
        val r = importer().import(lib, MoveInOptions())
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("big import: ${ms} ms, $r".take(300))
        assertEquals(40_000, r.newPlays)
        assertEquals(1_500, r.newLikes)
        assertTrue("took $ms ms", ms < 30_000)

        val t1 = System.nanoTime()
        val again = importer().import(lib, MoveInOptions())
        println("big re-import: ${(System.nanoTime() - t1) / 1_000_000} ms")
        assertTrue(again.nothingNew)
    }
}
