package com.lyrenne.desktop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shape of the crossfade tail.
 *
 * The reported bug (issue #5) was that crossfade cut the outgoing track dead and faded in
 * only the incoming one. The fix hangs on this curve reaching silence exactly at the end of
 * the track and being full volume everywhere before the window, so it is asserted here.
 */
class CrossfadeTailTest {

    @Test
    fun `full volume until the window opens`() {
        assertEquals(1f, crossfadeGain(remainingMs = 60_000, crossfadeMs = 5_000), 0f)
        assertEquals(1f, crossfadeGain(remainingMs = 5_001, crossfadeMs = 5_000), 0f)
    }

    @Test
    fun `ramps to silence at the end of the track`() {
        assertEquals(1f, crossfadeGain(5_000, 5_000), 0f)
        assertEquals(0.5f, crossfadeGain(2_500, 5_000), 0.001f)
        assertEquals(0f, crossfadeGain(0, 5_000), 0f)
    }

    /** Duration lands late from VLC, so remaining can briefly read past the end. */
    @Test
    fun `overshooting the end stays silent`() {
        assertEquals(0f, crossfadeGain(-1_200, 5_000), 0f)
    }

    /** Crossfade off must never touch the fader. */
    @Test
    fun `disabled crossfade leaves the volume alone`() {
        assertEquals(1f, crossfadeGain(500, 0), 0f)
    }

    @Test
    fun `never gets louder as the track runs out`() {
        var previous = Float.MAX_VALUE
        for (remaining in 12_000 downTo 0 step 100) {
            val gain = crossfadeGain(remaining.toLong(), 12_000)
            assertTrue("rose at $remaining ms left", gain <= previous)
            previous = gain
        }
    }
}
