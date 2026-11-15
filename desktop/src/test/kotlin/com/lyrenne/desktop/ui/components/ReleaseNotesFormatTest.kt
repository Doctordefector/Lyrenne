package com.lyrenne.desktop.ui.components

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.lyrenne.desktop.ui.components.ReleaseNotesFormat.Block
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesFormatTest {
    private val bold = SpanStyle(fontWeight = FontWeight.Bold)
    private val code = SpanStyle()

    @Test
    fun headingsBulletsAndParagraphs() {
        val md = """
            ## New

            - **Game Mode**: hotkeys that work in games
              and an overlay
            - Second
              - Nested
            1. Numbered

            Plain paragraph
            continues here.

            ---
            ### Fixes ###
        """.trimIndent()
        assertEquals(
            listOf(
                Block.Heading("New"),
                Block.Bullet("**Game Mode**: hotkeys that work in games and an overlay", 0),
                Block.Bullet("Second", 0),
                Block.Bullet("Nested", 1),
                Block.Bullet("Numbered", 0),
                Block.Paragraph("Plain paragraph continues here."),
                Block.Heading("Fixes"),
            ),
            ReleaseNotesFormat.parse(md)
        )
    }

    @Test
    fun loneBoldLineIsAHeading() {
        assertEquals(
            listOf(Block.Heading("New"), Block.Bullet("Thing", 0), Block.Paragraph("**Mixed** text")),
            ReleaseNotesFormat.parse("**New**\n- Thing\n\n**Mixed** text")
        )
    }

    @Test
    fun stripsImagesCommentsAndTags() {
        val md = "<!-- hidden -->\n![shot](a.png)\nSee <b>this</b> [the docs](https://x.y)\r\n"
        assertEquals(listOf(Block.Paragraph("See this [the docs](https://x.y)")), ReleaseNotesFormat.parse(md))
    }

    @Test
    fun inlineBoldCodeAndLinks() {
        val s = ReleaseNotesFormat.inline("Use **Ctrl+Alt** and `scoop` per [guide](https://g)", bold, code)
        assertEquals("Use Ctrl+Alt and scoop per guide", s.text)
        assertEquals(listOf("Ctrl+Alt"), s.spanStyles.filter { it.item == bold }.map { s.text.substring(it.start, it.end) })
    }

    @Test
    fun unclosedMarkersStayLiteral() {
        assertEquals("2 ** 3 and a ` tick", ReleaseNotesFormat.inline("2 ** 3 and a ` tick", bold, code).text)
    }
}
