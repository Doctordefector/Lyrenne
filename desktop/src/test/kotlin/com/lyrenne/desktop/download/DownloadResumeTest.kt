package com.lyrenne.desktop.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the safety property that makes resuming a download safe to do at all.
 *
 * Resuming appends to whatever partial file survived the last attempt. If that partial came
 * from a different encoding of the same video, the result is a valid-looking m4a made of two
 * unrelated halves, and nothing notices until someone plays it weeks later. The only thing
 * standing between here and that outcome is the partial being named after the exact byte
 * count it is a prefix of, so a partial can never be matched to a stream it did not come from.
 */
class DownloadResumeTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A partial belongs to one content length and is invisible to any other. */
    @Test
    fun `partial only matches the stream it came from`() {
        val dir = folder.newFolder()
        val songId = "dQw4w9WgXcQ"

        val first = DownloadManager.partFile(dir, songId, 3_500_000)
        first.writeBytes(ByteArray(1024))

        // Same song, different encoding. Must not resolve to the partial written above.
        val second = DownloadManager.partFile(dir, songId, 4_100_000)
        assertFalse("a different length must not reuse the partial", second.exists())
        assertTrue(first.exists())

        // And the same encoding must find it again, or resuming never happens.
        assertEquals(first, DownloadManager.partFile(dir, songId, 3_500_000))
        assertEquals(1024L, DownloadManager.partFile(dir, songId, 3_500_000).length())
    }

    /** Starting an attempt sweeps this song's dead partials and leaves other songs alone. */
    @Test
    fun `stale partials are pruned and neighbours are not`() {
        val dir = folder.newFolder()
        val songId = "dQw4w9WgXcQ"

        val stale = DownloadManager.partFile(dir, songId, 3_500_000).apply { writeBytes(ByteArray(8)) }
        val legacy = java.io.File(dir, "$songId.tmp").apply { writeBytes(ByteArray(8)) }
        val other = DownloadManager.partFile(dir, "aBcDeFgHiJk", 900_000).apply { writeBytes(ByteArray(8)) }

        val keep = DownloadManager.partFile(dir, songId, 4_100_000).apply { writeBytes(ByteArray(8)) }
        DownloadManager.pruneStalePartials(dir, songId, keep)

        assertFalse("partial for a dead length must go", stale.exists())
        assertFalse("pre-resume .tmp file must go", legacy.exists())
        assertTrue("the current partial must survive", keep.exists())
        assertTrue("another song's partial must be untouched", other.exists())
    }

    /**
     * The range parameter is what bypasses YouTube's CDN throttling, and on a resume it is
     * also the resume offset. Getting the start wrong re-downloads bytes the partial already
     * holds and appends them, which is the corruption above by another route.
     */
    @Test
    fun `range parameter carries the resume offset`() {
        val plain = "https://rr1.googlevideo.com/videoplayback?id=abc"
        assertEquals("$plain&range=0-3500000", DownloadManager.rangedUrl(plain, 0, 3_500_000))
        assertEquals("$plain&range=1024-3500000", DownloadManager.rangedUrl(plain, 1024, 3_500_000))

        val noQuery = "https://rr1.googlevideo.com/videoplayback"
        assertEquals("$noQuery?range=0-100", DownloadManager.rangedUrl(noQuery, 0, 100))

        // An unknown content length has no range to express, so the URL is passed through.
        assertEquals(plain, DownloadManager.rangedUrl(plain, 0, 0))
    }
}
