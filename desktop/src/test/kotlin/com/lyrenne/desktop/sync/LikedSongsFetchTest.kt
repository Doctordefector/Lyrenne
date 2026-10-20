package com.lyrenne.desktop.sync

import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quick sync stops paging liked songs early. Stopping early is only safe when nothing new or
 * removed can be past the stop, so every case here is about when it must NOT stop.
 */
class LikedSongsFetchTest {
    private val original = LibrarySync.likedPage
    private var requests = 0

    @After fun restore() { LibrarySync.likedPage = original }

    private fun song(id: String) = SongItem(id = id, title = id, artists = emptyList(), thumbnail = "")

    /** Serves [ids] newest first in pages of [pageSize], reporting [count] as YouTube's total. */
    private fun remote(ids: List<String>, pageSize: Int = 100, count: Int? = ids.size) {
        val pages = ids.chunked(pageSize)
        LibrarySync.likedPage = { cont ->
            requests++
            val i = cont?.toInt() ?: 0
            LibrarySync.LikedPage(
                pages[i].map(::song),
                continuation = if (i + 1 < pages.size) "${i + 1}" else null,
                remoteCount = if (cont == null) count else null
            )
        }
    }

    private fun fetch(known: Set<String>?) = runBlocking { LibrarySync.fetchLikedSongs(known) }

    private val library = (1..500).map { "k$it" }

    @Test
    fun `nothing new stops after the first page`() {
        remote(library)
        val r = fetch(library.toSet())
        assertEquals(1, requests)
        assertFalse(r.complete)
        assertEquals(500, r.total)
    }

    @Test
    fun `new likes at the top are fetched and it still stops early`() {
        remote(listOf("n1", "n2") + library)
        val r = fetch(library.toSet())
        assertEquals(1, requests)
        assertEquals(listOf("n1", "n2"), r.songs.take(2).map { it.id })
        assertEquals(502, r.total)
    }

    @Test
    fun `an unlike on YouTube forces a full fetch so it gets pruned`() {
        remote(library - "k300")
        val r = fetch(library.toSet())
        assertTrue(r.complete)
        assertEquals(5, requests)
        assertEquals(499, r.songs.size)
    }

    @Test
    fun `a new like below the stop point is not missed`() {
        // Page 1 is all known, but the total is one short of adding up until page 2 reaches it
        remote(library.take(150) + "deep" + library.drop(150))
        val r = fetch(library.toSet())
        assertTrue(r.songs.any { it.id == "deep" })
        assertEquals(2, requests)
    }

    @Test
    fun `a page of mostly new songs keeps going`() {
        val fresh = (1..150).map { "n$it" }
        remote(fresh + library)
        val r = fetch(library.toSet())
        assertEquals(2, requests)
        assertTrue(r.songs.map { it.id }.containsAll(fresh))
    }

    @Test
    fun `no usable total means a full fetch`() {
        remote(library, count = null)
        assertTrue(fetch(library.toSet()).complete)
        assertEquals(5, requests)
    }

    @Test
    fun `first sync and full sync fetch everything`() {
        remote(library)
        assertTrue(fetch(emptySet()).complete)
        requests = 0
        assertTrue(fetch(null).complete)
        assertEquals(5, requests)
    }

    @Test
    fun `a failed page fails the fetch instead of returning a prefix`() {
        remote(library)
        val ok = LibrarySync.likedPage
        LibrarySync.likedPage = { cont -> if (cont == "2") error("network") else ok(cont) }
        assertTrue(runCatching { fetch(null) }.isFailure)
    }

    @Test
    fun `song count text`() {
        assertEquals(1234, LibrarySync.parseSongCount("1,234 songs"))
        assertEquals(1234, LibrarySync.parseSongCount("1.234 Titel"))
        assertEquals(7, LibrarySync.parseSongCount("7 songs"))
        assertNull(LibrarySync.parseSongCount("songs"))
        assertNull(LibrarySync.parseSongCount(null))
    }
}
