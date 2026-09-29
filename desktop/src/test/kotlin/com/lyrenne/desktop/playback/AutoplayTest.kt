package com.lyrenne.desktop.playback

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins autoplay (issue #12) without the network: YouTube's radio is swapped for a fixed page.
 *
 * Until 2.13.0 the auto-queue check ran only inside playNext(), and a finished track reached
 * playNext() only when there was a song after it. A song played on its own, which is everything
 * started from search, home or the library, is a one-song queue, so it always just stopped.
 */
class AutoplayTest {

    private val player = DesktopPlayer()

    @After
    fun tearDown() = player.release()

    private fun song(n: Int, fromAutoplay: Boolean = false) = SongInfo(
        id = "id$n",
        title = "Song $n",
        artist = "Artist",
        thumbnailUrl = null,
        fromAutoplay = fromAutoplay
    )

    private fun item(n: Int, explicit: Boolean = false) = SongItem(
        id = "id$n",
        title = "Song $n",
        artists = listOf(Artist(name = "Artist", id = null)),
        thumbnail = "",
        explicit = explicit
    )

    @Suppress("UNCHECKED_CAST")
    private fun list(name: String): MutableList<SongInfo> =
        DesktopPlayer::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(player) as MutableList<SongInfo>

    private val queue get() = list("queue")
    private val originalQueue get() = list("originalQueue")

    /** Lays out a queue directly: playSong() and playQueue() would resolve real streams. */
    private fun setQueue(songs: List<SongInfo>, current: Int) {
        queue.apply { clear(); addAll(songs) }
        originalQueue.apply { clear(); addAll(songs) }
        DesktopPlayer::class.java.getDeclaredField("currentIndex")
            .apply { isAccessible = true }
            .setInt(player, current)
    }

    private fun radioReturns(vararg items: SongItem) {
        player.radioFor = { Result.success(items.toList()) }
    }

    @Test
    fun `a song played on its own gets similar songs queued after it`() = runBlocking {
        setQueue(listOf(song(1)), current = 0)
        // A radio opens with its own seed, and pages can repeat a song
        radioReturns(item(1), item(2), item(3), item(2))

        assertTrue(player.awaitSimilarSongs())

        assertEquals(listOf("id1", "id2", "id3"), queue.map { it.id })
        assertTrue("appended songs are autoplay's", queue.drop(1).all { it.fromAutoplay })
        assertFalse("the user's song is not", queue[0].fromAutoplay)
        assertEquals(queue.map { it.id }, originalQueue.map { it.id })
    }

    @Test
    fun `songs the user adds go in front of autoplay's`() {
        setQueue(listOf(song(1), song(2, fromAutoplay = true), song(3, fromAutoplay = true)), current = 0)

        player.addToQueue(song(5))
        player.addToQueue(song(6))
        player.addToQueueNext(song(7))

        assertEquals(listOf("id1", "id7", "id5", "id6", "id2", "id3"), queue.map { it.id })
        assertEquals(queue.map { it.id }, originalQueue.map { it.id })
    }

    @Test
    fun `adding to a queue autoplay has not touched still appends`() {
        setQueue(listOf(song(1), song(2)), current = 0)

        player.addToQueue(song(3))

        assertEquals(listOf("id1", "id2", "id3"), queue.map { it.id })
    }

    @Test
    fun `a Listen Together guest never autoplays`() = runBlocking {
        setQueue(listOf(song(1)), current = 0)
        var asked = false
        player.radioFor = { asked = true; Result.success(listOf(item(2))) }
        player.autoplayAllowed = { false }

        assertFalse(player.awaitSimilarSongs())
        assertFalse("the radio was not even fetched", asked)
        assertEquals(1, queue.size)
    }

    @Test
    fun `repeat modes never autoplay`() = runBlocking {
        setQueue(listOf(song(1)), current = 0)
        radioReturns(item(2))

        for (mode in listOf(RepeatMode.ALL, RepeatMode.ONE)) {
            player.setRepeatMode(mode)
            assertFalse("autoplayed under $mode", player.awaitSimilarSongs())
        }
        assertEquals(1, queue.size)
    }

    @Test
    fun `Start Radio fills the queue even where autoplay would not, as its own songs`() = runBlocking {
        setQueue(listOf(song(1)), current = 0)
        DesktopPlayer::class.java.getDeclaredField("radioStarted")
            .apply { isAccessible = true }
            .setBoolean(player, true)
        player.setRepeatMode(RepeatMode.ALL)
        radioReturns(item(1), item(2), item(3))

        assertTrue(player.awaitSimilarSongs(force = true))

        assertEquals(listOf("id1", "id2", "id3"), queue.map { it.id })
        assertTrue("a radio the user asked for is theirs", queue.none { it.fromAutoplay })
    }

    /**
     * The fetch takes seconds, and the user can start something else meanwhile. Its songs must
     * not land in the new queue, even when the fetch cannot be cancelled in time.
     */
    @Test
    fun `a fetch that outlives its queue adds nothing`() = runBlocking {
        setQueue(listOf(song(1)), current = 0)
        val gate = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        player.radioFor = {
            withContext(NonCancellable) {
                gate.await()
                finished.complete(Unit)
                Result.success(listOf(item(2), item(3)))
            }
        }

        val result = async(Dispatchers.Default) { player.awaitSimilarSongs() }
        delay(100)
        player.clearQueue() // replaces the queue: same song, new generation
        gate.complete(Unit)

        assertFalse(withTimeout(5_000) { result.await() })
        withTimeout(5_000) { finished.await() }
        delay(100)
        assertEquals(listOf("id1"), queue.map { it.id })
    }

    @Test
    fun `a radio page with nothing new adds nothing`() = runBlocking {
        setQueue(listOf(song(1), song(2)), current = 1)
        radioReturns(item(1), item(2))

        assertFalse(player.awaitSimilarSongs())
        assertEquals(2, queue.size)
    }

    @Test
    fun `explicit songs stay out while explicit content is hidden`() {
        val page = listOf(item(1), item(2, explicit = true), item(3))

        val hidden = similarSongsToQueue(page, queued = emptySet(), hideExplicit = true, fromAutoplay = true)
        val shown = similarSongsToQueue(page, queued = emptySet(), hideExplicit = false, fromAutoplay = true)

        assertEquals(listOf("id1", "id3"), hidden.map { it.id })
        assertEquals(listOf("id1", "id2", "id3"), shown.map { it.id })
    }

    @Test
    fun `switching autoplay off takes back its songs still to come but not the history`() {
        setQueue(
            listOf(
                song(0, fromAutoplay = true), // already played
                song(1),                      // playing
                song(2, fromAutoplay = true),
                song(3),                      // queued by the user
                song(4, fromAutoplay = true)
            ),
            current = 1
        )

        player.dropQueuedAutoplay()

        assertEquals(listOf("id0", "id1", "id3"), queue.map { it.id })
        assertEquals(queue.map { it.id }, originalQueue.map { it.id })
    }

    /** Dragged into place by hand, a song is the user's pick and later additions go after it. */
    @Test
    fun `moving an autoplay song by hand makes it the user's`() {
        setQueue(
            listOf(song(1), song(2, fromAutoplay = true), song(3, fromAutoplay = true), song(4, fromAutoplay = true)),
            current = 0
        )

        player.moveInQueue(3, 1)
        player.addToQueue(song(5))

        assertEquals(listOf("id1", "id4", "id5", "id2", "id3"), queue.map { it.id })
        assertFalse(queue[1].fromAutoplay)
    }

    /** Left out of the unshuffle order, autoplay's songs vanished the moment shuffle went off. */
    @Test
    fun `autoplay songs survive turning shuffle off`() = runBlocking {
        setQueue(listOf(song(1), song(2)), current = 0)
        player.toggleShuffle()
        radioReturns(item(3), item(4))

        assertTrue(player.awaitSimilarSongs())
        player.toggleShuffle()

        assertEquals(setOf("id1", "id2", "id3", "id4"), queue.map { it.id }.toSet())
        assertEquals(4, queue.size)
    }

    /**
     * Nor may they become the whole of that order when none was saved, as after a restart with
     * shuffle on. Turning shuffle off would then have left only autoplay's songs.
     */
    @Test
    fun `autoplay with no saved shuffle order keeps the whole queue`() = runBlocking {
        setQueue(listOf(song(1), song(2)), current = 1)
        originalQueue.clear()
        DesktopPlayer::class.java.getDeclaredField("shuffleEnabled")
            .apply { isAccessible = true }
            .setBoolean(player, true)
        radioReturns(item(3), item(4))

        assertTrue(player.awaitSimilarSongs())
        player.toggleShuffle()

        assertEquals(listOf("id1", "id2", "id3", "id4"), queue.map { it.id })
        assertEquals("id2", queue[player.state.value.currentIndex].id)
    }
}
