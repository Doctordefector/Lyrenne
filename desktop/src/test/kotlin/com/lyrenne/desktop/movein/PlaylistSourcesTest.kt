package com.lyrenne.desktop.movein

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link parsers, against trimmed copies of real public pages captured on 2026-11-15:
 * - spotify-embed-playlist.html: open.spotify.com/embed/playlist/37i9dQZF1DX4UtSsGT1Sbe (All Out 80s, 150 songs, embed shows 100)
 * - spotify-embed-album.html: open.spotify.com/embed/album/4aawyAB9vmqN3uQ7FjRGTy
 * - apple-playlist.html: music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb
 * - apple-album.html: music.apple.com/us/album/angel-of-my-dreams/1754238138
 * - deezer-*.json: api.deezer.com/playlist/3155776842 (Top Worldwide) and its /tracks pages, api.deezer.com/album/302127
 * - tidal-embed-playlist.html: embed.tidal.com/playlists/1b418bb8-90a7-4f87-901d-707993838346 (New Arrivals)
 * - soundcloud-set.html: soundcloud.com/soundcloud-the-peak/sets/on-the-up-new-edm-hits (client id replaced)
 * Only the scripts and markup the parsers read were kept. `PlaylistSourcesSmokeTest` checks the
 * live pages still have that shape.
 */
class PlaylistSourcesTest {
    private fun text(name: String) = javaClass.getResource("/movein/$name")!!.readText()

    @Test
    fun `links are recognised from every service and junk is not`() {
        fun d(s: String) = PlaylistSources.detect(s)
        assertEquals(SourceLink(SourceService.SPOTIFY, LinkKind.PLAYLIST, "37i9dQZF1DX4UtSsGT1Sbe", "https://open.spotify.com/playlist/37i9dQZF1DX4UtSsGT1Sbe?si=abc"),
            d("https://open.spotify.com/playlist/37i9dQZF1DX4UtSsGT1Sbe?si=abc"))
        assertEquals("4aawyAB9vmqN3uQ7FjRGTy", d("https://open.spotify.com/intl-de/album/4aawyAB9vmqN3uQ7FjRGTy")?.id)
        assertEquals(LinkKind.ALBUM, d("spotify:album:4aawyAB9vmqN3uQ7FjRGTy")?.kind)
        assertEquals("pl.f4d106fed2bd41149aaacabb233eb5eb", d("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb")?.id)
        assertEquals("1754238138", d("https://music.apple.com/gb/album/angel-of-my-dreams/1754238138?i=1754238139")?.id)
        assertEquals(SourceService.DEEZER, d("https://www.deezer.com/en/playlist/3155776842")?.service)
        assertEquals("1b418bb8-90a7-4f87-901d-707993838346", d("https://tidal.com/browse/playlist/1b418bb8-90a7-4f87-901d-707993838346")?.id)
        assertEquals("1b418bb8-90a7-4f87-901d-707993838346", d("https://listen.tidal.com/playlist/1b418bb8-90a7-4f87-901d-707993838346")?.id)
        assertEquals("soundcloud-the-peak/sets/on-the-up-new-edm-hits", d("https://soundcloud.com/soundcloud-the-peak/sets/on-the-up-new-edm-hits?si=1")?.id)
        assertNull(d("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(d("Daft Punk - One More Time"))
        assertTrue(PlaylistSources.isShortLink("https://link.deezer.com/s/30ZrK5TmdHVXsQTNrfYKZ"))
        assertTrue(PlaylistSources.looksLikeLink(" https://open.spotify.com/playlist/x "))
        assertTrue(!PlaylistSources.looksLikeLink("https://a\nhttps://b"))
    }

    @Test
    fun `spotify embed gives 100 songs and says the rest are out of reach`() {
        val p = PlaylistSources.parseSpotifyEmbed(text("spotify-embed-playlist.html"))
        assertEquals("All Out 80s", p.name)
        assertEquals(100, p.tracks.size)
        assertNotNull(p.limitNote)
        assertTrue(p.limitNote!!.contains("first 100"))
        val t = p.tracks.first()
        assertTrue(t.title.isNotBlank())
        assertTrue(t.artists.isNotEmpty())
        assertTrue((t.durationMs ?: 0) > 60_000)
        // Non-breaking spaces between artists are gone
        assertTrue(p.tracks.flatMap { it.artists }.none { ' ' in it })
    }

    @Test
    fun `spotify album embed carries the album name and no limit`() {
        val p = PlaylistSources.parseSpotifyEmbed(text("spotify-embed-album.html"))
        assertTrue(p.tracks.isNotEmpty())
        assertNull(p.limitNote)
        assertTrue(p.tracks.all { it.album == p.name })
    }

    @Test
    fun `apple music playlist page`() {
        val p = PlaylistSources.parseAppleMusic(text("apple-playlist.html"))
        assertEquals("Today’s Hits", p.name)
        assertEquals(50, p.tracks.size)
        assertEquals(50, p.totalTracks)
        assertNull(p.limitNote)
        val first = p.tracks.first()
        assertEquals("Housewife", first.title)
        assertEquals(listOf("Tate McRae"), first.artists)
        assertEquals("Housewife - Single", first.album)
        assertEquals(208810L, first.durationMs)
    }

    @Test
    fun `apple music album page`() {
        val p = PlaylistSources.parseAppleMusic(text("apple-album.html"))
        assertEquals(listOf(SourceTrack("Angel Of My Dreams", listOf("JADE"), "Angel Of My Dreams - Single", 197639)), p.tracks)
    }

    @Test
    fun `deezer pages are joined in order`() {
        val p = PlaylistSources.parseDeezer(text("deezer-playlist.json"), listOf(text("deezer-tracks-page1.json"), text("deezer-tracks-page2.json")))
        assertEquals("Top Worldwide", p.name)
        assertEquals(6, p.tracks.size)
        assertEquals(100, p.totalTracks)
        assertNotNull("6 of 100 is reported", p.limitNote)
        assertEquals(SourceTrack("Boston", listOf("STELLA LEFTY"), "Boston", 170_000), p.tracks.first())
    }

    @Test
    fun `deezer album`() {
        val p = PlaylistSources.parseDeezer(text("deezer-album.json"), emptyList())
        assertEquals("Discovery", p.name)
        assertEquals(14, p.tracks.size)
        assertNull(p.limitNote)
        assertEquals("One More Time", p.tracks.first().title)
        assertEquals(listOf("Daft Punk"), p.tracks.first().artists)
        assertEquals("Discovery", p.tracks.first().album)
    }

    @Test
    fun `tidal embed lists songs in order with every artist`() {
        val p = PlaylistSources.parseTidalEmbed(text("tidal-embed-playlist.html"))
        assertEquals("New Arrivals", p.name)
        assertEquals(50, p.tracks.size)
        assertNotNull(p.limitNote)
        val first = p.tracks.first()
        assertEquals("Here Then We're Gone", first.title)
        assertEquals(listOf("Ella Langley", "The Hunger Games"), first.artists)
        assertEquals(246_000L, first.durationMs)
    }

    @Test
    fun `soundcloud set gives the first songs in full and the rest as ids to fill`() {
        val page = PlaylistSources.parseSoundCloud(text("soundcloud-set.html"))
        assertEquals("New EDM Hits: On The Up", page.playlist.name)
        assertEquals(100, page.order.size)
        assertEquals(5, page.known.size)
        assertEquals(95, page.missingIds.size)
        assertEquals("FIXTURECLIENTID0000000000000000", page.clientId)
        val first = page.playlist.tracks.first()
        assertEquals("BbY WOW (John Summit Remix)", first.title)
        assertEquals(listOf("KAROL G", "John Summit", "Judeline", "rusowsky"), first.artists)
    }

    @Test
    fun `artist strings split, titles do not`() {
        assertEquals(listOf("KAROL G", "Judeline", "rusowsky"), PlaylistSources.splitArtists("KAROL G, Judeline, rusowsky"))
        assertEquals(listOf("Calvin Harris", "Dua Lipa"), PlaylistSources.splitArtists("Calvin Harris feat. Dua Lipa"))
        assertEquals(listOf("Feather"), PlaylistSources.splitArtists("Feather"))
        assertEquals(listOf("Lil Nas X"), PlaylistSources.splitArtists("Lil Nas X"))
        assertEquals(225_000L, PlaylistSources.clockToMs("3:45"))
        assertEquals(3_723_000L, PlaylistSources.clockToMs("1:02:03"))
    }
}
