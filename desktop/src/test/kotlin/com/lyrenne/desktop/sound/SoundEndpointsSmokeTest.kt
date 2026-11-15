package com.lyrenne.desktop.sound

import com.lyrenne.desktop.sound.eq.AutoEq
import com.lyrenne.desktop.sound.eq.EqFitter
import com.lyrenne.desktop.sound.sponsorblock.SponsorBlockApi
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/**
 * Live checks against SponsorBlock and the AutoEq repository, so a change on their side (a moved
 * index, a new response shape) shows up before users find it. Skipped offline.
 */
class SoundEndpointsSmokeTest {

    private fun <T> online(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        assumeTrue("offline: ${e.message}", false)
        throw e
    }

    @Test
    fun sponsorBlockHashEndpointAnswersForAKnownMusicVideo() {
        // Despacito's video: locked music_offtopic segments at both ends
        val segments = online { SponsorBlockApi.fetch(OkHttpTransport, "kJQP7kiw5Fk") }
        println("SponsorBlock: ${segments.map { "${it.category} ${it.startMs}-${it.endMs}" }}")
        assertTrue(segments.any { it.category == "music_offtopic" && it.startMs < 1_000 })
    }

    @Test
    fun autoEqIndexAndProfileAreWhereWeLookForThem() {
        val dir = Files.createTempDirectory("autoeq-live").toFile()
        try {
            val autoEq = AutoEq(OkHttpTransport, dir)
            val index = online { autoEq.index() }
            println("AutoEq index: ${index.size} entries")
            assertTrue(index.size > 1000)
            val hd600 = AutoEq.search(index, "Sennheiser HD 600").first()
            val curve = online { autoEq.curve(hd600) }
            val fit = EqFitter.fit(curve, listOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0))
            println("AutoEq ${hd600.name} by ${hd600.label}: preamp ${fit.preampDb} bands ${fit.bandsDb}")
            assertTrue(fit.bandsDb[0] > 2f)
        } finally {
            dir.deleteRecursively()
        }
    }
}
