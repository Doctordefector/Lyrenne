package com.lyrenne.desktop.ui.components

import androidx.compose.ui.graphics.Color
import com.lyrenne.desktop.lyrics.LyricLine
import com.lyrenne.desktop.lyrics.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsHighlightTest {

    private val sung = Color.Red
    private val unsung = Color.Gray

    private fun colorAt(text: androidx.compose.ui.text.AnnotatedString, index: Int): Color =
        text.spanStyles.filter { index >= it.start && index < it.end }.last().item.color

    @Test
    fun `sung words take the accent and later words stay dim`() {
        val words = listOf(LyricWord("Hello", 0, 500), LyricWord("world", 500, 1000))
        val text = highlightWords("Hello world", words, 700, sung, unsung)
        assertEquals(sung, colorAt(text, 0))
        // "world" is 40% through, so neither colour exactly.
        val mid = colorAt(text, 6)
        assert(mid != sung && mid != unsung)

        val before = highlightWords("Hello world", words, 0, sung, unsung)
        assertEquals(unsung, colorAt(before, 6))
    }

    @Test
    fun `a word missing from the display text does not shift the others`() {
        val words = listOf(LyricWord("Hello", 0, 100), LyricWord("zzz", 100, 200), LyricWord("world", 200, 300))
        val text = highlightWords("Hello, world!", words, 1_000, sung, unsung)
        assertEquals(sung, colorAt(text, 7))
        assertEquals(unsung, colorAt(text, 5)) // the comma belongs to no word
    }

    @Test
    fun `no line is current before the first one starts`() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(2_000, "b"))
        assertEquals(-1, currentLineIndex(lines, 500))
        assertEquals(0, currentLineIndex(lines, 1_000))
        assertEquals(1, currentLineIndex(lines, 9_000))
    }
}
