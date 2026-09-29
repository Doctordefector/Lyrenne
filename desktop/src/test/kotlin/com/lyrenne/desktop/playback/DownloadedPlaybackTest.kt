package com.lyrenne.desktop.playback

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins where a queued song plays from, offline: the download lookup and the stream resolver are
 * both stubbed, and the player runs without VLC, which it tolerates.
 *
 * Every queue path used to resolve a YouTube stream whether the song was downloaded or not. Only a
 * click in the Downloads tab played the file, so a download reached any other way (an album, a
 * playlist, Next, the Downloaded playlist) was streamed anyway, which fails offline.
 */
class DownloadedPlaybackTest {

    private val player = DesktopPlayer()

    /** Every song a stream was asked for, in order. */
    private val streamed = mutableListOf<String>()

    init {
        // Songs 1 and 3 are downloaded, song 2 is not
        player.downloadedFileFor = { id ->
            if (id == "id1" || id == "id3") File("Downloads", "$id.m4a") else null
        }
        player.streamFor = { id -> streamed += id; "https://stream.invalid/$id" }
        // Autoplay would fetch YouTube's radio at the end of the queue, which is not the point here
        player.autoplayAllowed = { false }
    }

    @After
    fun tearDown() = player.release()

    private fun song(n: Int) = SongInfo(
        id = "id$n",
        title = "Song $n",
        artist = "Artist",
        thumbnailUrl = null
    )

    private val playing get() = player.state.value.currentSong?.id

    @Test
    fun `every queue path plays a downloaded song from its file`() = runBlocking {
        player.playQueue(listOf(song(1), song(2), song(3)), 0)
        assertEquals("id1", playing)

        player.playNext()
        assertEquals("id2", playing)
        player.playNext()
        assertEquals("id3", playing)
        player.playPrevious()
        assertEquals("id2", playing)
        player.playAtIndex(2)
        assertEquals("id3", playing)

        // The last song ends and repeat all goes back to the first
        player.setRepeatMode(RepeatMode.ALL)
        player.onTrackFinished()
        assertEquals("id1", playing)

        assertEquals("only the song with no download was streamed", listOf("id2", "id2"), streamed)
    }

    @Test
    fun `a downloaded song played on its own plays from its file`() = runBlocking {
        assertTrue(player.playSong(song(3)))

        assertEquals("id3", playing)
        assertEquals(emptyList<String>(), streamed)
    }

    /** The previous song's length must not stand while VLC opens the file. */
    @Test
    fun `a file starts with its own duration`() = runBlocking {
        player.playQueue(listOf(song(2).copy(duration = 100), song(3).copy(durationMs = 245_000)), 0)
        assertEquals(100_000L, player.state.value.duration)

        player.playNext()

        assertEquals("id3", playing)
        assertEquals(245_000L, player.state.value.duration)
    }

    @Test
    fun `a song with nothing to play from leaves the queue alone`() = runBlocking {
        player.playQueue(listOf(song(1), song(2)), 0)
        player.streamFor = { null }

        assertFalse(player.playSong(song(4)))

        assertEquals(listOf("id1", "id2"), player.state.value.queue.map { it.id })
        assertEquals("id1", playing)
    }

    /**
     * Runs the real lookup. Unit tests never open the database, so here it fails, and a lookup that
     * fails must cost the song its offline copy, never its playback.
     */
    @Test
    fun `a song still streams when the download lookup fails`() = runBlocking {
        val real = DesktopPlayer()
        try {
            real.streamFor = { id -> streamed += id; "https://stream.invalid/$id" }
            real.autoplayAllowed = { false }

            assertTrue(real.playSong(song(5)))

            assertEquals(listOf("id5"), streamed)
        } finally {
            real.release()
        }
    }
}
