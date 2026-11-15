package com.lyrenne.desktop.movein

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Live check that each service still serves its public page in the shape the parsers and the
 * fixtures assume. Skips cleanly offline. First run against these playlists on 2026-11-15, all
 * matching the fixtures in `src/test/resources/movein/`.
 */
class PlaylistSourcesSmokeTest {

    private fun online(host: String): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, 443), 4000) }
        true
    }.getOrDefault(false)

    private fun check(url: String, host: String, minTracks: Int) {
        assumeTrue("offline", online(host))
        val link = PlaylistSources.detect(url) ?: error("not detected: $url")
        val p = PlaylistSources.fetch(link)
        println("${link.service.label}: \"${p.name}\", ${p.tracks.size} songs (total ${p.totalTracks}), note: ${p.limitNote}")
        println("  first: ${p.tracks.firstOrNull()}")
        assertTrue("${link.service}: only ${p.tracks.size} songs", p.tracks.size >= minTracks)
        assertTrue("${link.service}: songs without artists", p.tracks.count { it.artists.isEmpty() } <= p.tracks.size / 10)
        assertTrue("${link.service}: songs without a length", p.tracks.count { it.durationMs == null } <= p.tracks.size / 10)
    }

    @Test fun spotify() = check("https://open.spotify.com/playlist/37i9dQZF1DX4UtSsGT1Sbe", "open.spotify.com", 50)
    @Test fun spotifyAlbum() = check("https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy", "open.spotify.com", 5)
    @Test fun appleMusic() = check("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb", "music.apple.com", 20)
    @Test fun deezer() = check("https://www.deezer.com/en/playlist/3155776842", "api.deezer.com", 50)
    @Test fun tidal() = check("https://tidal.com/browse/playlist/1b418bb8-90a7-4f87-901d-707993838346", "embed.tidal.com", 20)
    @Test fun soundcloud() = check("https://soundcloud.com/soundcloud-the-peak/sets/on-the-up-new-edm-hits", "soundcloud.com", 50)
}

/** Live check that real songs from a real playlist match on YouTube Music, signed out. */
class TrackMatchSmokeTest {
    @Test
    fun `songs from the apple fixture match confidently`() = kotlinx.coroutines.runBlocking {
        val online = runCatching { Socket().use { it.connect(InetSocketAddress("music.youtube.com", 443), 4000) }; true }.getOrDefault(false)
        assumeTrue("offline", online)
        val html = javaClass.getResource("/movein/apple-playlist.html")!!.readText()
        val tracks = PlaylistSources.parseAppleMusic(html).tracks.take(8) + listOf(
            SourceTrack("Bohemian Rhapsody - Remastered 2011", listOf("Queen"), null, 354_320),
            SourceTrack("夜に駆ける", listOf("YOASOBI"), null, 261_000),
        )
        var good = 0
        for (t in tracks) {
            val m = TrackMatcher.match(t, MoveInController::searchYouTubeMusic)
            println("${m.quality} ${t.artistLine} - ${t.title}  =>  ${m.chosen?.title} by ${m.chosen?.artists} (${m.options.firstOrNull()?.score})")
            if (m.quality == MatchQuality.GOOD) good++
        }
        assertTrue("only $good of ${tracks.size} matched well", good >= tracks.size - 2)
    }
}
