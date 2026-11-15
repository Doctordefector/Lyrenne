package com.lyrenne.desktop.sound.sponsorblock

import com.lyrenne.desktop.sound.HttpResult
import com.lyrenne.desktop.sound.HttpTransport
import com.lyrenne.desktop.sound.sponsorblock.SegmentSkipper.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val VIDEO = "kJQP7kiw5Fk"
private const val DURATION = 281_521L

private fun fixture() = SponsorBlockTest::class.java.getResource("/sound/sponsorblock-f1d9.json")!!.readText()

private fun seg(id: String, start: Long, end: Long, category: String = "music_offtopic", videoDuration: Long = 0, votes: Int = 0, locked: Boolean = false) =
    Segment(id, category, start, end, videoDuration, votes, locked)

class SponsorBlockTest {

    @Test
    fun `hash prefix is the first four hex digits of the id's SHA-256`() {
        assertEquals("f1d9", SponsorBlockApi.hashPrefix(VIDEO))
        assertEquals(4, SponsorBlockApi.hashPrefix("dQw4w9WgXcQ").length)
    }

    @Test
    fun `the request never carries the video id`() {
        val url = SponsorBlockApi.url(VIDEO)
        assertTrue(url, url.startsWith("https://sponsor.ajay.app/api/skipSegments/f1d9?"))
        assertFalse(url, VIDEO in url)
        assertTrue(url, "music_offtopic" in url && "actionTypes" in url)
    }

    @Test
    fun `parse picks this video's skip segments out of the prefix response`() {
        val segments = SponsorBlockApi.parse(fixture(), VIDEO)
        // The mute segment and the one that ends before it starts are dropped; other videos too
        assertEquals(listOf("uuid-intro", "uuid-outro", "uuid-filler"), segments.map { it.uuid })
        val intro = segments[0]
        assertEquals(0L, intro.startMs)
        assertEquals(21_808L, intro.endMs)
        assertEquals(282_000L, intro.videoDurationMs)
        assertTrue(intro.locked)
        assertEquals(41, intro.votes)
        assertEquals("filler", segments[2].category)
        assertTrue(SponsorBlockApi.parse(fixture(), "nope").isEmpty())
        assertTrue(SponsorBlockApi.parse("[]", VIDEO).isEmpty())
        assertTrue(SponsorBlockApi.parse("{}", VIDEO).isEmpty())
    }

    @Test
    fun `fetch treats 404 as no segments and other errors as failures`() {
        assertTrue(SponsorBlockApi.fetch({ _, _, _, _ -> HttpResult(404, "Not Found") }, VIDEO).isEmpty())
        assertEquals(3, SponsorBlockApi.fetch({ _, url, _, _ -> assertFalse(VIDEO in url); HttpResult(200, fixture()) }, VIDEO).size)
        try {
            SponsorBlockApi.fetch(HttpTransport { _, _, _, _ -> HttpResult(503, "") }, VIDEO)
            throw AssertionError("expected a failure")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `segments timed on another cut of the video are not trusted`() {
        val s = seg("a", 0, 20_000, videoDuration = 300_000)
        assertTrue(s.trustedFor(301_500))
        assertFalse(s.trustedFor(240_000))
        // Length unknown to SponsorBlock: trusted
        assertTrue(seg("b", 0, 20_000).trustedFor(240_000))
        // Downvoted, unless locked
        assertFalse(seg("c", 0, 20_000, votes = -1).trustedFor(240_000))
        assertTrue(seg("d", 0, 20_000, votes = -1, locked = true).trustedFor(240_000))
        // Most of the track: a mistake unless locked
        assertFalse(seg("e", 0, 200_000).trustedFor(240_000))
        assertTrue(seg("f", 0, 200_000, locked = true).trustedFor(240_000))
        // Too short to bother
        assertFalse(seg("g", 1_000, 1_500).trustedFor(240_000))
    }

    private fun skipper(vararg segments: Segment) = SegmentSkipper().apply { load(VIDEO, segments.toList()) }

    @Test
    fun `playing into an intro skips to its end`() {
        val s = skipper(*SponsorBlockApi.parse(fixture(), VIDEO).toTypedArray())
        assertEquals(Decision.None, s.onTick(VIDEO, 0, DURATION, 0, 0)) // not started yet
        val d = s.onTick(VIDEO, 150, DURATION, 0, 1_000)
        assertTrue(d is Decision.SkipTo)
        assertEquals(21_808L, (d as Decision.SkipTo).positionMs)
        assertEquals(listOf("uuid-intro"), d.skipped.map { it.uuid })
    }

    @Test
    fun `never loop skips when the player did not move`() {
        val s = skipper(seg("a", 0, 20_000))
        assertTrue(s.onTick(VIDEO, 200, 200_000, 0, 0) is Decision.SkipTo)
        // VLC still reporting the old position: no second jump straight away
        assertEquals(Decision.None, s.onTick(VIDEO, 400, 200_000, 0, 200))
        assertEquals(Decision.None, s.onTick(VIDEO, 600, 200_000, 0, 400))
        // One retry after a second, then it gives up for good
        assertTrue(s.onTick(VIDEO, 1_200, 200_000, 0, 1_100) is Decision.SkipTo)
        for (t in 2_000L..10_000L step 200) assertEquals(Decision.None, s.onTick(VIDEO, t, 200_000, 0, t))
    }

    @Test
    fun `a seek into a segment is respected until playback leaves it`() {
        val s = skipper(seg("a", 60_000, 90_000))
        s.onSeek(70_000)
        for (t in 70_000L..89_800L step 200) assertEquals(Decision.None, s.onTick(VIDEO, t, 200_000, 0, t))
        assertEquals(Decision.None, s.onTick(VIDEO, 90_100, 200_000, 0, 90_100))
        // Going back before it later arms it again
        s.onSeek(50_000)
        assertTrue(s.onTick(VIDEO, 60_100, 200_000, 0, 100_000) is Decision.SkipTo)
    }

    @Test
    fun `seeking back before a skipped segment skips it again, restart included`() {
        val s = skipper(seg("intro", 0, 20_000), seg("mid", 60_000, 70_000))
        assertTrue(s.onTick(VIDEO, 200, 200_000, 0, 0) is Decision.SkipTo)
        assertTrue(s.onTick(VIDEO, 60_200, 200_000, 0, 60_000) is Decision.SkipTo)
        // Repeat One and Previous restart the track with a seek to 0: the intro goes again
        s.onSeek(0)
        assertTrue(s.onTick(VIDEO, 200, 200_000, 0, 120_000) is Decision.SkipTo)
        assertTrue(s.onTick(VIDEO, 60_200, 200_000, 0, 180_000) is Decision.SkipTo)
    }

    @Test
    fun `undo keeps a segment even across a restart`() {
        val s = skipper(seg("intro", 0, 20_000))
        assertTrue(s.onTick(VIDEO, 200, 200_000, 0, 0) is Decision.SkipTo)
        s.keep(listOf("intro"))
        s.onSeek(0)
        for (t in 200L..19_800L step 200) assertEquals(Decision.None, s.onTick(VIDEO, t, 200_000, 0, 10_000 + t))
    }

    @Test
    fun `overlapping and touching segments are one jump`() {
        val s = skipper(seg("a", 10_000, 20_000), seg("b", 18_000, 30_000, "sponsor"), seg("c", 30_300, 40_000, "selfpromo"))
        val d = s.onTick(VIDEO, 10_100, 200_000, 0, 0) as Decision.SkipTo
        assertEquals(40_000L, d.positionMs)
        assertEquals(setOf("a", "b", "c"), d.skipped.map { it.uuid }.toSet())
        // Landing in the middle of b (inside a too) still clears all three
        val s2 = skipper(seg("a", 10_000, 20_000), seg("b", 18_000, 30_000, "sponsor"))
        val d2 = s2.onTick(VIDEO, 19_000, 200_000, 0, 0) as Decision.SkipTo
        assertEquals(30_000L, d2.positionMs)
    }

    @Test
    fun `a segment running to the end ends the track instead of seeking`() {
        val segments = SponsorBlockApi.parse(fixture(), VIDEO)
        val s = skipper(*segments.toTypedArray())
        val d = s.onTick(VIDEO, 249_500, DURATION, 0, 0)
        assertTrue(d is Decision.EndTrack)
        // The filler sits inside the outro, so the outro alone decides the jump
        assertEquals(listOf("uuid-outro"), (d as Decision.EndTrack).skipped.map { it.uuid })
        // Never twice: the next song may take seconds to start
        for (t in 249_700L..256_000L step 200) assertEquals(Decision.None, s.onTick(VIDEO, t, DURATION, 0, t))
    }

    @Test
    fun `a jump that would land inside the crossfade ends the track`() {
        // Segment ends 5 s before the end of a 200 s track
        val without = skipper(seg("o", 150_000, 195_000)).onTick(VIDEO, 150_100, 200_000, 0, 0)
        assertEquals(195_000L, (without as Decision.SkipTo).positionMs)
        val with = skipper(seg("o", 150_000, 195_000)).onTick(VIDEO, 150_100, 200_000, 8_000, 0)
        assertTrue(with is Decision.EndTrack)
    }

    @Test
    fun `not worth a jump in the last second of a segment, and only for its own song`() {
        val s = skipper(seg("a", 10_000, 20_000))
        assertEquals(Decision.None, s.onTick(VIDEO, 19_500, 200_000, 0, 0))
        assertEquals(Decision.None, s.onTick("otherSong", 12_000, 200_000, 0, 0))
        // A new song forgets the old one's state
        s.load("next", listOf(seg("x", 0, 5_000)))
        assertTrue(s.onTick("next", 100, 200_000, 0, 0) is Decision.SkipTo)
    }

    @Test
    fun `untrusted segments are never skipped`() {
        val s = skipper(seg("a", 10_000, 20_000, videoDuration = 100_000))
        assertEquals(Decision.None, s.onTick(VIDEO, 12_000, 200_000, 0, 0))
    }

    @Test
    fun `default categories are the never-music ones`() {
        assertEquals(setOf("music_offtopic", "sponsor", "selfpromo"), SkipCategory.defaults)
        assertEquals(8, SkipCategory.entries.size)
    }
}
