package com.lyrenne.desktop.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins playlist sorting (issue #16). */
class PlaylistSortTest {

    private data class S(val title: String, val artist: String, val album: String?)

    private val songs = listOf(
        S("beta", "Zed", null),
        S("Álamo", "amy", "Second"),
        S("alpha", "Amy", "first"),
        S("Gamma", "bob", ""),
    )

    private fun sort(sort: PlaylistSort, reversed: Boolean = false, seed: Int = 0) =
        sortPlaylist(songs, sort, reversed, seed, { it.title }, { it.artist }, { it.album }).map { it.title }

    @Test
    fun defaultKeepsOrderOrReversesIt() {
        assertEquals(listOf("beta", "Álamo", "alpha", "Gamma"), sort(PlaylistSort.DEFAULT))
        assertEquals(listOf("Gamma", "alpha", "Álamo", "beta"), sort(PlaylistSort.DEFAULT, reversed = true))
    }

    @Test
    fun titleIgnoresCaseAndAccentsAndKeepsTiesInPlaylistOrder() {
        // Á sorts as a, so "Álamo" lands before "alpha" instead of after "Gamma"
        assertEquals(listOf("Álamo", "alpha", "beta", "Gamma"), sort(PlaylistSort.TITLE))
        assertEquals(listOf("Gamma", "beta", "alpha", "Álamo"), sort(PlaylistSort.TITLE, reversed = true))
        // amy == Amy: stable, so playlist order decides
        assertEquals(listOf("Álamo", "alpha", "Gamma", "beta"), sort(PlaylistSort.ARTIST))
    }

    @Test
    fun blankAlbumsGoLastBothWays() {
        assertEquals(listOf("alpha", "Álamo", "beta", "Gamma"), sort(PlaylistSort.ALBUM))
        assertEquals(listOf("Álamo", "alpha", "beta", "Gamma"), sort(PlaylistSort.ALBUM, reversed = true))
    }

    @Test
    fun randomIsStablePerSeed() {
        assertEquals(sort(PlaylistSort.RANDOM, seed = 7), sort(PlaylistSort.RANDOM, seed = 7))
        assertEquals(songs.map { it.title }.sorted(), sort(PlaylistSort.RANDOM, seed = 7).sorted())
    }
}
