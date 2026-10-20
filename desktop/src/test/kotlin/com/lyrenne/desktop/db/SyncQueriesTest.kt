package com.lyrenne.desktop.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The SQL that library sync leans on. Each case is a bug that shipped: a song already in the
 * database never picked up a like from YouTube, a song credit wiped a subscribed artist, and
 * callers that don't know a song's length overwrote the one stored.
 */
class SyncQueriesTest {
    private val q = LyrenneDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LyrenneDatabase.Schema.create(it) }
    ).lyrenneQueries

    private fun insert(id: String, liked: Long = 0, likedDate: String? = null, duration: Long = -1) =
        q.insertSong(id, "t", duration, null, null, null, 0, null, liked, likedDate, 0, null, null, 0, 0, null)

    @Test
    fun `markSongLiked likes an existing row and keeps an existing like's date`() {
        insert("played")
        insert("liked", liked = 1, likedDate = "2026-01-01")
        q.markSongLiked("2026-10-20", "played")
        q.markSongLiked("2026-10-20", "liked")
        assertEquals(1L, q.getSongById("played").executeAsOne().liked)
        assertEquals("2026-10-20", q.getSongById("played").executeAsOne().likedDate)
        assertEquals("2026-01-01", q.getSongById("liked").executeAsOne().likedDate)
    }

    @Test
    fun `a song credit does not wipe a subscribed artist`() {
        q.insertArtist("a", "Artist", "thumb", "ch", "now", "2026-01-01")
        q.insertArtistIfMissing("a", "Artist", "later")
        val artist = q.getArtistById("a").executeAsOne()
        assertEquals("2026-01-01", artist.bookmarkedAt)
        assertEquals("thumb", artist.thumbnailUrl)
    }

    @Test
    fun `unknown duration and year keep the stored ones`() {
        insert("s", duration = 200)
        q.updateSongMetadata("t", -1, null, null, null, 0, null, null, "s")
        assertEquals(200L, q.getSongById("s").executeAsOne().duration)
        q.updateSongMetadata("t", 210, null, null, null, 0, null, null, "s")
        assertEquals(210L, q.getSongById("s").executeAsOne().duration)
    }
}
