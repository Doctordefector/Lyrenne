package com.lyrenne.desktop.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Quick skips, as Game Mode hotkeys and the phone remote send them. Each skip resolves its stream
 * over the network, so they finish out of order. 2.21.0 testing crashed VLC when two of them landed
 * together from two threads; before that, a slow older skip could also land last and play a song the
 * queue no longer pointed at. Only the newest start may play.
 */
class RapidSkipTest {
    private val player = DesktopPlayer()

    init {
        player.downloadedFileFor = { null }
        // Song 2 resolves slowly, every other song at once: the first Next finishes after the second.
        player.streamFor = { id -> if (id == "id2") delay(300) else delay(10); "https://stream.invalid/$id" }
        player.autoplayAllowed = { false }
    }

    @After fun tearDown() = player.release()

    private fun song(n: Int) = SongInfo(id = "id$n", title = "Song $n", artist = "Artist", thumbnailUrl = null)

    @Test
    fun `two quick Nexts from different threads end on the song the queue points at`() = runBlocking {
        player.playQueue((1..5).map(::song), 0)
        listOf(async(Dispatchers.Default) { player.playNext() }, async(Dispatchers.Default) { delay(20); player.playNext() })
            .awaitAll()
        delay(400) // let the slow first resolve finish; it must not take over
        val s = player.state.value
        assertEquals("id3", s.currentSong?.id)
        assertEquals("id${s.currentIndex + 1}", s.currentSong?.id)
    }

    @Test
    fun `a slow single play is overtaken by a newer one`() = runBlocking {
        player.playQueue((1..5).map(::song), 0)
        val slow = async(Dispatchers.Default) { player.playAtIndex(1) } // id2, slow
        delay(20)
        player.playAtIndex(3) // id4, fast
        slow.await()
        assertEquals("id4", player.state.value.currentSong?.id)
    }
}
