package com.lyrenne.desktop.movein

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cases that make matching hard. Each row is a song as another service names it, the right
 * YouTube Music result, and the wrong ones that look like it. The right one must rank first and
 * be confident; a wrong-only search must not be.
 */
class TrackMatcherTest {

    private fun song(id: String, title: String, vararg artists: String, sec: Int? = null, album: String? = null) =
        Candidate(id, title, artists.toList(), album, sec, isSong = true)

    private fun video(id: String, title: String, vararg artists: String, sec: Int? = null, ugc: Boolean = false) =
        Candidate(id, title, artists.toList(), null, sec, isSong = false, isUserVideo = ugc)

    private data class Case(val name: String, val source: SourceTrack, val right: Candidate, val wrong: List<Candidate>)

    private val cases = listOf(
        Case(
            "remaster suffix on the source",
            SourceTrack("Bohemian Rhapsody - Remastered 2011", listOf("Queen"), "A Night at the Opera", 354_320),
            song("right", "Bohemian Rhapsody", "Queen", sec = 355),
            listOf(
                song("live", "Bohemian Rhapsody (Live Aid)", "Queen", sec = 359),
                song("karaoke", "Bohemian Rhapsody (Karaoke Version)", "Sing2Piano", sec = 356),
                video("cover", "Bohemian Rhapsody (Cover)", "Pentatonix", sec = 357),
            )
        ),
        Case(
            "featured artist in the title on one side, in the credits on the other",
            SourceTrack("SICKO MODE (feat. Drake)", listOf("Travis Scott"), null, 312_820),
            song("right", "SICKO MODE", "Travis Scott", "Drake", sec = 313),
            listOf(song("other", "SICKO MODE (Remix)", "Some DJ", sec = 205))
        ),
        Case(
            "featured artist as feat. in the YouTube title",
            SourceTrack("Under Pressure", listOf("Queen", "David Bowie"), null, 248_000),
            song("right", "Under Pressure (Remastered 2011)", "Queen", "David Bowie", sec = 248),
            listOf(song("vi", "Under Pressure", "Vanilla Ice tribute band", sec = 240))
        ),
        Case(
            "Japanese title",
            SourceTrack("夜に駆ける", listOf("YOASOBI"), null, 261_000),
            song("right", "夜に駆ける", "YOASOBI", sec = 261),
            listOf(
                song("sped", "夜に駆ける (sped up)", "YOASOBI", sec = 210),
                song("other", "群青", "YOASOBI", sec = 250),
            )
        ),
        Case(
            "Japanese title romanized on the source, only the artist agrees",
            SourceTrack("Racing Into The Night", listOf("YOASOBI"), null, 261_000),
            song("right", "Racing Into The Night", "YOASOBI", sec = 261),
            listOf(song("jp", "夜に駆ける", "YOASOBI", sec = 261))
        ),
        Case(
            "Cyrillic with an accent-free artist",
            SourceTrack("Дым", listOf("Иван Дорн"), null, 200_000),
            song("right", "Дым", "Иван Дорн", sec = 201),
            listOf(song("other", "Дымка", "Другой", sec = 180))
        ),
        Case(
            "accents and ampersands",
            SourceTrack("Déjà Vu", listOf("Beyoncé", "JAY-Z"), null, 240_000),
            song("right", "Deja Vu", "Beyoncé", "JAY Z", sec = 240),
            listOf(song("other", "Deja Vu", "Olivia Rodrigo", sec = 215))
        ),
        Case(
            "the source asks for the live version",
            SourceTrack("Hotel California - Live On MTV, 1994", listOf("Eagles"), "Hell Freezes Over", 427_000),
            song("right", "Hotel California (Live on MTV, 1994)", "Eagles", sec = 428, album = "Hell Freezes Over"),
            listOf(song("studio", "Hotel California", "Eagles", sec = 391, album = "Hotel California"))
        ),
        Case(
            "a studio source must not take a song off a live album",
            SourceTrack("Hotel California", listOf("Eagles"), "Hotel California", 391_000),
            song("right", "Hotel California", "Eagles", sec = 391, album = "Hotel California"),
            listOf(song("livealbum", "Hotel California", "Eagles", sec = 393, album = "Live at the Forum"))
        ),
        Case(
            "a title that contains the word live is not a live version",
            SourceTrack("Live Forever", listOf("Oasis"), null, 276_000),
            song("right", "Live Forever (Remastered)", "Oasis", sec = 277),
            listOf(song("live", "Live Forever (Live at Knebworth)", "Oasis", sec = 290))
        ),
        Case(
            "sped up version asked for",
            SourceTrack("Kill Bill (Sped Up)", listOf("SZA"), null, 128_000),
            song("right", "Kill Bill (Sped Up Version)", "SZA", sec = 128),
            listOf(song("orig", "Kill Bill", "SZA", sec = 154))
        ),
        Case(
            "only a music video exists, titled Artist - Song",
            SourceTrack("Midnight City", listOf("M83"), null, 243_000),
            video("right", "M83 - Midnight City (Official Video)", "M83", sec = 244),
            listOf(video("lyrics", "Midnight City (Lyrics)", "LyricsChannel", sec = 243, ugc = true))
        ),
        Case(
            "a song beats the same song's music video",
            SourceTrack("Blinding Lights", listOf("The Weeknd"), null, 200_040),
            song("right", "Blinding Lights", "The Weeknd", sec = 200),
            listOf(video("mv", "Blinding Lights (Official Video)", "The Weeknd", sec = 262))
        ),
        Case(
            "duration picks between two cuts",
            SourceTrack("Smalltown Boy", listOf("Bronski Beat"), null, 302_000),
            song("right", "Smalltown Boy", "Bronski Beat", sec = 303),
            listOf(song("edit", "Smalltown Boy", "Bronski Beat", sec = 540))
        ),
    )

    @Test
    fun `the right song ranks first in every case`() {
        val failures = cases.mapNotNull { c ->
            val ranked = TrackMatcher.rank(c.source, c.wrong + c.right)
            val scores = ranked.joinToString { "${it.candidate.videoId}=${"%.2f".format(it.score)}" }
            println("${c.name}: $scores")
            when {
                ranked.first().candidate.videoId != "right" -> "${c.name}: picked ${ranked.first().candidate.videoId} ($scores)"
                TrackMatcher.quality(ranked.first().score) == MatchQuality.NONE -> "${c.name}: right one not found ($scores)"
                else -> null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `clear cases are confident`() {
        for (name in listOf("remaster suffix on the source", "Japanese title", "a song beats the same song's music video", "accents and ampersands")) {
            val c = cases.single { it.name == name }
            val score = TrackMatcher.score(c.source, c.right)
            assertEquals("$name scored $score", MatchQuality.GOOD, TrackMatcher.quality(score))
        }
    }

    @Test
    fun `only wrong versions on offer is not a confident match`() {
        val source = SourceTrack("Bohemian Rhapsody", listOf("Queen"), null, 355_000)
        for (wrong in listOf(
            song("k", "Bohemian Rhapsody (Karaoke Version)", "Sing2Piano", sec = 356),
            video("n", "Bohemian Rhapsody - Nightcore", "NightcoreFan", sec = 290, ugc = true),
            song("d", "Don't Stop Me Now", "Queen", sec = 209),
        )) {
            val q = TrackMatcher.quality(TrackMatcher.score(source, wrong))
            assertTrue("${wrong.title} was $q", q != MatchQuality.GOOD)
        }
    }

    @Test
    fun `titles split into the song and its version`() {
        val p = TrackMatcher.splitTitle("Under Pressure (feat. David Bowie) - Remastered 2011")
        assertEquals("under pressure", p.core)
        assertEquals(listOf("David Bowie"), p.featured)
        assertTrue(p.tags.isEmpty())
        assertEquals(setOf("live"), TrackMatcher.splitTitle("Hotel California - Live On MTV, 1994").tags)
        assertTrue(TrackMatcher.splitTitle("Live Forever").tags.isEmpty())
        assertEquals("deja vu", TrackMatcher.normalize("Déjà Vu!"))
        assertEquals("がっこう", TrackMatcher.normalize("がっこう"))
    }

    @Test
    fun `search falls back to videos only when no song is good`() = runBlocking {
        val calls = ArrayList<Boolean>()
        val source = SourceTrack("Midnight City", listOf("M83"), null, 243_000)
        val m = TrackMatcher.match(source) { _, videos ->
            calls += videos
            if (videos) listOf(video("v", "M83 - Midnight City (Official Video)", "M83", sec = 244))
            else listOf(song("s", "Midnight Sun", "Someone", sec = 200))
        }
        assertEquals(listOf(false, true), calls)
        assertEquals("v", m.chosen?.videoId)

        calls.clear()
        val good = TrackMatcher.match(SourceTrack("Blinding Lights", listOf("The Weeknd"), null, 200_000)) { _, videos ->
            calls += videos
            listOf(song("s", "Blinding Lights", "The Weeknd", sec = 200))
        }
        assertEquals(listOf(false), calls)
        assertEquals(MatchQuality.GOOD, good.quality)

        val none = TrackMatcher.match(SourceTrack("Zzz", listOf("Nobody"), null, 100_000)) { _, _ -> emptyList() }
        assertNull(none.chosen)
        assertEquals(MatchQuality.NONE, none.quality)
    }

    @Test
    fun `search query drops noise but keeps a version that was asked for`() {
        assertEquals("Queen Bohemian Rhapsody", TrackMatcher.query(SourceTrack("Bohemian Rhapsody - Remastered 2011", listOf("Queen"))))
        assertEquals("Travis Scott SICKO MODE", TrackMatcher.query(SourceTrack("SICKO MODE (feat. Drake)", listOf("Travis Scott"))))
        assertEquals("SZA Kill Bill (Sped Up)", TrackMatcher.query(SourceTrack("Kill Bill (Sped Up)", listOf("SZA"))))
    }
}
