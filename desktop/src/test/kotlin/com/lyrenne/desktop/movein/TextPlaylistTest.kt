package com.lyrenne.desktop.movein

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPlaylistTest {

    @Test
    fun `plain lines in the shapes people paste`() {
        val p = TextPlaylist.parse(
            listOf(
                "# my list",
                "Daft Punk - One More Time",
                "1. Queen \u2013 Bohemian Rhapsody (5:55)",
                "02 - Tame Impala - The Less I Know The Better",
                "Blinding Lights by The Weeknd",
                // Splits into two names; matching only needs one of them to overlap
                "Tyler, The Creator - EARFQUAKE",
                "YOASOBI\t夜に駆ける",
                "Just a title",
                "",
            ).joinToString("\r\n")
        )
        assertEquals(
            listOf(
                SourceTrack("One More Time", listOf("Daft Punk")),
                SourceTrack("Bohemian Rhapsody", listOf("Queen"), null, 355_000),
                SourceTrack("The Less I Know The Better", listOf("Tame Impala")),
                SourceTrack("Blinding Lights", listOf("The Weeknd")),
                SourceTrack("EARFQUAKE", listOf("Tyler", "The Creator")),
                SourceTrack("夜に駆ける", listOf("YOASOBI")),
                SourceTrack("Just a title", emptyList()),
            ),
            p.tracks
        )
    }

    @Test
    fun `exportify csv with quoted commas and ms durations`() {
        val csv = listOf(
            "\"Track URI\",\"Track Name\",\"Album Name\",\"Artist Name(s)\",\"Release Date\",\"Duration (ms)\",\"Popularity\"",
            "\"spotify:track:1\",\"Under Pressure - Remastered 2011\",\"Hot Space (2011 Remaster)\",\"Queen,David Bowie\",\"1982-05-21\",\"248440\",\"80\"",
            "\"spotify:track:2\",\"Say \"\"Hello\"\"\",\"Album, With Comma\",\"Someone\",\"2020\",\"180000\",\"1\"",
        ).joinToString("\n")
        val p = TextPlaylist.parse(csv)
        assertEquals(
            listOf(
                SourceTrack("Under Pressure - Remastered 2011", listOf("Queen", "David Bowie"), "Hot Space (2011 Remaster)", 248_440),
                SourceTrack("Say \"Hello\"", listOf("Someone"), "Album, With Comma", 180_000),
            ),
            p.tracks
        )
    }

    @Test
    fun `tunemymusic csv and a music app tab export`() {
        val tmm = "Track name,Artist name,Album,Playlist name,Type,ISRC\nLevitating,Dua Lipa,Future Nostalgia,Mine,Playlist,GBAHT2000942\n"
        assertEquals(listOf(SourceTrack("Levitating", listOf("Dua Lipa"), "Future Nostalgia", null)), TextPlaylist.parse(tmm).tracks)

        val music = "Name\tArtist\tComposer\tAlbum\tTime\nHey Jude\tThe Beatles\tLennon\t1\t431\n"
        assertEquals(listOf(SourceTrack("Hey Jude", listOf("The Beatles"), "1", 431_000)), TextPlaylist.parse(music).tracks)
    }
}
