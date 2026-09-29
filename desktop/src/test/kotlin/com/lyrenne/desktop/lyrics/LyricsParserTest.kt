package com.lyrenne.desktop.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the formats the lyrics providers actually send.
 *
 * The parser this replaced understood only `[mm:ss.xx]text` and threw on anything else, and a
 * throw sent the whole song to the plain view as raw text. Issue #10 was BetterLyrics' word lines
 * appearing on screen verbatim; the same fault also swallowed any LRC with an ID tag, and misread
 * the three-digit milliseconds YouTube transcripts use.
 */
class LyricsParserTest {

    /** Verbatim shape of the BetterLyrics output in the issue #10 screenshot. */
    private val betterLyrics = """
        [00:09.35]'Di ko maintindihan ang nilalaman ng puso
        <'Di:9.35:9.624|ko:9.624:9.927|maintindihan:9.927:12.499|ang:12.709:12.997|nilalaman:12.997:14.722|ng:14.722:15.258|puso:15.258:17.335>
        [00:18.35]Tuwing magkahawak ang ating kamay
        <Tuwing:18.358:18.959|magkahawak:18.959:20.654|ang:20.654:21.208|ating:21.208:22.5|kamay:22.5:23.703>
    """.trimIndent()

    @Test
    fun `BetterLyrics word lines become word timings, not text`() {
        val lines = LyricsParser.parse(betterLyrics)
        assertNotNull(lines)
        lines!!
        assertEquals(2, lines.size)
        assertEquals(9_350L, lines[0].timeMs)
        assertEquals("'Di ko maintindihan ang nilalaman ng puso", lines[0].text)
        assertTrue(lines.none { it.text.contains('<') || it.text.contains('|') })

        val words = lines[0].words!!
        assertEquals(7, words.size)
        assertEquals(LyricWord("'Di", 9_350, 9_624), words[0])
        assertEquals(LyricWord("puso", 15_258, 17_335), words.last())
        assertEquals(LyricWord("kamay", 22_500, 23_703), lines[1].words!!.last())
    }

    @Test
    fun `a word containing a colon still splits on the last two`() {
        val words = LyricsParser.parseWordLine("<a:b:1.0:2.0|c:2.0:3.5>")!!
        assertEquals(LyricWord("a:b", 1_000, 2_000), words[0])
        assertEquals(LyricWord("c", 2_000, 3_500), words[1])
    }

    @Test
    fun `an unreadable word line is dropped, not displayed`() {
        val lines = LyricsParser.parse("[00:01.00]hello\n<garbage>\n[00:02.00]world")!!
        assertEquals(listOf("hello", "world"), lines.map { it.text })
        assertNull(lines[0].words)
    }

    @Test
    fun `ID tags are skipped and do not sink the song`() {
        val lines = LyricsParser.parse(
            "[ar:Bruno Mars]\n[ti:Just the Way You Are]\n[length:03:40]\n[00:01.00]Oh, her eyes"
        )!!
        assertEquals(1, lines.size)
        assertEquals("Oh, her eyes", lines[0].text)
    }

    @Test
    fun `offset shifts every line sooner when positive`() {
        val lines = LyricsParser.parse("[offset:+500]\n[00:02.00]a\n[00:03.00]b")!!
        assertEquals(listOf(1_500L, 2_500L), lines.map { it.timeMs })
    }

    @Test
    fun `three digit milliseconds from YouTube transcripts are read exactly`() {
        val lines = LyricsParser.parse("[00:01.234]first\n[01:02.050]second")!!
        assertEquals(1_234L, lines[0].timeMs)
        assertEquals("first", lines[0].text)
        assertEquals(62_050L, lines[1].timeMs)
    }

    @Test
    fun `one line with several timestamps repeats at each`() {
        val lines = LyricsParser.parse("[00:10.00][00:40.00]chorus\n[00:20.00]verse")!!
        assertEquals(listOf(10_000L, 20_000L, 40_000L), lines.map { it.timeMs })
        assertEquals(listOf("chorus", "verse", "chorus"), lines.map { it.text })
    }

    @Test
    fun `agent markers are removed and background lines attach to their lead`() {
        val lines = LyricsParser.parse(
            """
            [00:01.00]{agent:v1}Lead line
            <Lead:1.0:1.5|line:1.5:2.0>
            [00:01.20]{bg}(backing)
            <(backing):1.2:1.9>
            [00:03.00]{agent:v2}Second singer
            """.trimIndent()
        )!!
        assertEquals(2, lines.size)
        assertEquals("Lead line", lines[0].text)
        assertEquals("(backing)", lines[0].background.single().text)
        assertEquals(1_200L, lines[0].background.single().words!!.single().startMs)
        assertEquals("Second singer", lines[1].text)
    }

    @Test
    fun `inline rich sync yields words that run until the next starts`() {
        val lines = LyricsParser.parse("[00:01.00]<00:01.00>Hello <00:01.50>world\n[00:03.00]next")!!
        assertEquals("Hello world", lines[0].text)
        assertEquals(
            listOf(LyricWord("Hello", 1_000, 1_500), LyricWord("world", 1_500, 3_000)),
            lines[0].words
        )
    }

    @Test
    fun `plain lyrics are reported as unsynced`() {
        assertNull(LyricsParser.parse("Just words\nwith no timing\n"))
        assertNull(LyricsParser.parse(""))
    }

    @Test
    fun `empty timed lines are kept as instrumental gaps`() {
        val lines = LyricsParser.parse("[00:01.00]sung\n[00:05.00]\n[00:09.00]again")!!
        assertEquals(listOf("sung", "", "again"), lines.map { it.text })
    }

    @Test
    fun `html entities are decoded`() {
        val lines = LyricsParser.parse("[00:01.00]Don&#x27;t stop &amp; go")!!
        assertEquals("Don't stop & go", lines[0].text)
    }
}
