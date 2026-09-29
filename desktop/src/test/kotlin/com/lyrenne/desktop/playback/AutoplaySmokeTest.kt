package com.lyrenne.desktop.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live-network smoke test for autoplay (issue #12): a song played on its own, the way search,
 * home and the library play one, must carry on into similar songs instead of stopping. Runs the
 * real player against YouTube with no VLC, which it tolerates, so streams are resolved but never
 * opened. Fails if YouTube stops returning a radio for a well known song.
 */
class AutoplaySmokeTest {

    private val seed = SongInfo(
        id = "dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        artist = "Rick Astley",
        thumbnailUrl = null
    )

    private suspend fun DesktopPlayer.queueOnceGrown(timeoutMs: Long = 20_000): List<SongInfo> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (state.value.queue.size <= 1 && System.currentTimeMillis() < deadline) delay(100)
        return state.value.queue
    }

    @Test
    fun `similar songs are queued while a lone song plays, and Next plays them`(): Unit = runBlocking {
        val player = DesktopPlayer()
        try {
            assertTrue("no stream for the seed", player.playSong(seed))

            val queue = player.queueOnceGrown()
            assertTrue("nothing was queued after the song", queue.size > 1)
            assertTrue("queued songs are not marked as autoplay's", queue.drop(1).all { it.fromAutoplay })
            println("autoplay queued ${queue.size - 1} songs, first: ${queue[1].title} by ${queue[1].artist}")

            player.playNext()
            assertEquals(queue[1].id, player.state.value.currentSong?.id)
        } finally {
            player.release()
        }
    }

    /** The fallback: the song ends before anything was queued, so the fetch happens then. */
    @Test
    fun `a lone song that ends with nothing queued still carries on`(): Unit = runBlocking {
        val player = DesktopPlayer()
        try {
            player.autoplayAllowed = { false } // holds the prefetch back
            assertTrue("no stream for the seed", player.playSong(seed))
            assertEquals(1, player.state.value.queue.size)
            player.autoplayAllowed = { true }

            player.onTrackFinished()

            val state = player.state.value
            assertTrue("the queue stopped at its one song", state.queue.size > 1)
            assertEquals(state.queue[1].id, state.currentSong?.id)
        } finally {
            player.release()
        }
    }
}
