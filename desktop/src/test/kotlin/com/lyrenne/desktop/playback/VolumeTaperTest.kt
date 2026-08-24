package com.lyrenne.desktop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow

/**
 * Pins the shape of the volume fader.
 *
 * The taper has been wrong three times, and every time the only symptom was someone saying
 * by ear, weeks later, that the slider was far quieter than it read. The cause each time was
 * the same: VLC cubes the 0-100 it is handed, so any curve applied on top of it is cubed as
 * well, and a taper that looks reasonable in the source lands 30 dB lower in the room.
 * Nothing else in the build can catch that, so it is asserted here.
 */
class VolumeTaperTest {

    /**
     * Fraction of full loudness a given VLC volume produces. VLC's cube is measured against
     * the Windows session amplitude, and loudness doubles every 10 dB.
     */
    private fun loudnessOf(vlc: Int): Double {
        val amplitude = (vlc / 100.0).pow(3)
        return 2.0.pow(20 * log10(amplitude) / 10.0)
    }

    /** The bug that keeps coming back: the slider reading far louder than it sounds. */
    @Test
    fun `slider never sounds quieter than it reads`() {
        for (v in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) {
            val loudness = loudnessOf(vlcVolume(v))
            assertTrue("v=$v sounded like $loudness", loudness >= v)
        }
    }

    /**
     * The fader leans loud by exactly as much as FADER_LOUDNESS says, no more. Derived from
     * the knob rather than hardcoded, so turning the knob does not need this test edited and
     * the cube stays checked at whatever setting someone lands on. At 1.66 the exponent comes
     * out at 1.0 and the slider reads exactly as loud as it looks.
     */
    @Test
    fun `fader leans loud by the documented amount`() {
        val exponent = FADER_LOUDNESS * 2 * log10(2.0)
        for (v in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) {
            assertEquals("v=$v", v.toDouble().pow(exponent), loudnessOf(vlcVolume(v)), 0.02)
        }
    }

    /** The table in vlcVolume's docs, so the two cannot drift apart. */
    @Test
    fun `taper matches its documented table`() {
        assertEquals(52, vlcVolume(0.25f))
        assertEquals(72, vlcVolume(0.5f))
        assertEquals(87, vlcVolume(0.75f))
    }

    @Test
    fun `travel ends at true silence and unity gain`() {
        assertEquals(0, vlcVolume(0f))
        assertEquals(0, vlcVolume(FADER_MUTE_BELOW))
        assertTrue(vlcVolume(FADER_MUTE_BELOW + 0.01f) > 0)
        assertEquals(100, vlcVolume(1f))
    }

    @Test
    fun `taper never steps backwards`() {
        var previous = -1
        for (i in 0..1000) {
            val out = vlcVolume(i / 1000f)
            assertTrue("dropped at $i", out >= previous)
            previous = out
        }
    }
}
