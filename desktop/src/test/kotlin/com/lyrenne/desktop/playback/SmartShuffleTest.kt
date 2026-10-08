package com.lyrenne.desktop.playback

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins Smart Shuffle (issue #15) offline: streams and YouTube's radio are both stubbed. */
class SmartShuffleTest {

    private val player = DesktopPlayer()

    init {
        player.downloadedFileFor = { null }
        player.streamFor = { id -> "https://stream.invalid/$id" }
        player.autoplayAllowed = { false }
    }

    @After
    fun tearDown() = player.release()

    private fun song(n: Int) = SongInfo(id = "id$n", title = "Song $n", artist = "Artist", thumbnailUrl = null)

    private fun item(n: Int) = SongItem(
        id = "id$n",
        title = "Song $n",
        artists = listOf(Artist(name = "Artist", id = null)),
        thumbnail = ""
    )

    @Test
    fun `one suggestion after every three songs, leftovers dropped`() {
        val songs = (1..7).map(::song)
        val suggestions = (100..105).map(::song)

        val woven = interleaveSuggestions(songs, suggestions, every = 3)

        assertEquals(
            listOf("id1", "id2", "id3", "id100", "id4", "id5", "id6", "id101", "id7"),
            woven.map { it.id }
        )
    }

    @Test
    fun `a smart shuffle plays the whole playlist with marked suggestions mixed in`() = runBlocking {
        val playlist = (1..9).map(::song)
        // Radios open with their seed and overlap the playlist; neither may come back as a suggestion
        player.radioFor = { seed -> Result.success(listOf(item(seed.removePrefix("id").toInt()), item(1), item(50), item(51), item(52))) }

        player.smartShuffle(playlist)

        val queue = player.state.value.queue
        val suggested = queue.filter { it.suggested }
        assertEquals("every playlist song still plays once", playlist.map { it.id }.sorted(), queue.filterNot { it.suggested }.map { it.id }.sorted())
        assertEquals(listOf("id50", "id51"), suggested.map { it.id })
        assertTrue("suggestions fall after three playlist songs each", queue.indexOfFirst { it.suggested } == 4)
    }
}
