package com.lyrenne.desktop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the relationship between the live queue and the unshuffle order.
 *
 * `originalQueue` used to be filled in exactly one place: the moment shuffle was switched on.
 * Every other path that built a queue (playSong, playQueue, playLocalFile, restoreQueue) left it
 * empty, so an ordinary listening session ran with a live queue of twenty songs beside an
 * unshuffle order of zero. Two things fell out of that, both reported from the wild:
 *
 *  - "Play next" inserted into the live queue at `currentIndex + 1` and then repeated that index
 *    on the empty list, so `IndexOutOfBoundsException: Index: 7, Size: 0` killed the Compose
 *    click handler and froze the window (issue #9).
 *  - Turning shuffle off restored an order of zero songs, emptying the queue outright.
 *
 * Both are invisible to a compiler and to any test that only drives one method, so the invariant
 * itself is asserted here: while shuffle is off, the unshuffle order is the queue.
 */
class QueueOrderTest {

    private fun song(n: Int) = SongInfo(
        id = "id$n",
        title = "Song $n",
        artist = "Artist",
        thumbnailUrl = null
    )

    /** Reads a private field off the player, since the queue is deliberately not public. */
    @Suppress("UNCHECKED_CAST")
    private fun DesktopPlayer.queueField(name: String): MutableList<SongInfo> =
        DesktopPlayer::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this) as MutableList<SongInfo>

    private fun DesktopPlayer.setCurrentIndex(index: Int) {
        DesktopPlayer::class.java.getDeclaredField("currentIndex")
            .apply { isAccessible = true }
            .setInt(this, index)
    }

    /**
     * Rebuilds the reported crash exactly: eight songs playing, shuffle never touched, so the
     * unshuffle order is still empty when "play next" reaches for index 7.
     */
    @Test
    fun `play next survives a queue that was never shuffled`() {
        val player = DesktopPlayer()
        val queue = player.queueField("queue")
        queue.addAll((1..8).map { song(it) })
        player.queueField("originalQueue").clear()
        player.setCurrentIndex(6)

        player.addToQueueNext(song(99))

        assertEquals("id99", queue[7].id)
    }

    /** The invariant the crash came from: unshuffled, the two lists are the same list. */
    @Test
    fun `unshuffle order tracks the queue while shuffle is off`() {
        val player = DesktopPlayer()
        val queue = player.queueField("queue")
        val original = player.queueField("originalQueue")

        player.addToQueue(song(1))
        player.addToQueue(song(2))
        player.addToQueue(song(3))
        assertEquals(queue.map { it.id }, original.map { it.id })

        player.setCurrentIndex(0)
        player.addToQueueNext(song(4))
        assertEquals(queue.map { it.id }, original.map { it.id })

        player.moveInQueue(0, 2)
        assertEquals(queue.map { it.id }, original.map { it.id })

        player.removeFromQueue(3)
        assertEquals(queue.map { it.id }, original.map { it.id })
    }

    /** Turning shuffle off must never hand back a shorter queue than it was given. */
    @Test
    fun `unshuffle keeps every song even with no captured order`() {
        val player = DesktopPlayer()
        val queue = player.queueField("queue")
        queue.addAll((1..5).map { song(it) })
        player.queueField("originalQueue").clear()
        player.setCurrentIndex(0)

        // Shuffle was recorded as on by a restored session, so there is no order to go back to.
        DesktopPlayer::class.java.getDeclaredField("shuffleEnabled")
            .apply { isAccessible = true }
            .setBoolean(player, true)

        player.toggleShuffle()

        assertTrue("queue was emptied by unshuffle", queue.size == 5)
    }

    /** Shuffle on, then off, returns the songs to the order they were added in. */
    @Test
    fun `round trip through shuffle restores the added order`() {
        val player = DesktopPlayer()
        (1..12).forEach { player.addToQueue(song(it)) }
        player.setCurrentIndex(0)
        val added = player.queueField("queue").map { it.id }

        player.toggleShuffle()
        player.toggleShuffle()

        assertEquals(added, player.queueField("queue").map { it.id })
    }
}
