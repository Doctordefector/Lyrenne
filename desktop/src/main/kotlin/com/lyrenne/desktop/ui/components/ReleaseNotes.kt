package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Release notes are written in GitHub Markdown. The updater used to print them raw, so readers saw
 * `## New` and `**bold**`. This turns the subset our notes use into styled text: headings, bullets
 * (nested by indent), paragraphs, `**bold**`, `` `code` `` and links (shown as their text). Anything
 * else passes through as plain text, which is the safe failure.
 */
object ReleaseNotesFormat {
    sealed class Block {
        data class Heading(val text: String) : Block()
        data class Bullet(val text: String, val level: Int) : Block()
        data class Paragraph(val text: String) : Block()
    }

    private val heading = Regex("""^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$""")
    private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)\d+[.)]\s+(.*)$""")
    private val rule = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val image = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val htmlComment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val htmlTag = Regex("""</?[a-zA-Z][^>]*>""")

    fun parse(markdown: String): List<Block> {
        val text = htmlComment.replace(markdown.replace("\r\n", "\n"), "")
        val blocks = mutableListOf<Block>()
        val para = StringBuilder()
        fun flush() {
            val p = para.toString().trim()
            // Our notes title sections as a lone bold line ("**New**"), so treat that as a heading.
            val boldOnly = Regex("""^\*\*([^*]+)\*\*:?$""").matchEntire(p)
            if (boldOnly != null) blocks += Block.Heading(boldOnly.groupValues[1])
            else if (p.isNotEmpty()) blocks += Block.Paragraph(p)
            para.clear()
        }
        for (raw in text.lines()) {
            val line = htmlTag.replace(image.replace(raw, ""), "")
            if (line.isBlank() || rule.matches(line)) { flush(); continue }
            val h = heading.matchEntire(line)
            val b = bullet.matchEntire(line) ?: numbered.matchEntire(line)
            val last = blocks.lastOrNull()
            when {
                h != null -> { flush(); blocks += Block.Heading(h.groupValues[1]) }
                b != null -> {
                    flush()
                    val level = (b.groupValues[1].replace("\t", "    ").length / 2).coerceIn(0, 3)
                    blocks += Block.Bullet(b.groupValues[2].trim(), level)
                }
                // A continuation line under a bullet belongs to that bullet.
                para.isEmpty() && last is Block.Bullet && raw.startsWith(" ") ->
                    blocks[blocks.lastIndex] = last.copy(text = last.text + " " + line.trim())
                else -> {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(line.trim())
                }
            }
        }
        flush()
        return blocks
    }

    /** `**bold**` / `__bold__`, `` `code` ``, links as their text. Unclosed markers stay literal. */
    fun inline(text: String, bold: SpanStyle, code: SpanStyle): AnnotatedString {
        val s = link.replace(text) { it.groupValues[1] }
        return buildAnnotatedString {
            var i = 0
            while (i < s.length) {
                val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
                when {
                    two == "**" || two == "__" -> {
                        val end = s.indexOf(two, i + 2)
                        if (end > i + 2) {
                            withStyle(bold) { append(s.substring(i + 2, end)) }
                            i = end + 2
                        } else { append(two); i += 2 }
                    }
                    s[i] == '`' -> {
                        val end = s.indexOf('`', i + 1)
                        if (end > i + 1) {
                            withStyle(code) { append(s.substring(i + 1, end)) }
                            i = end + 1
                        } else { append('`'); i++ }
                    }
                    else -> { append(s[i]); i++ }
                }
            }
        }
    }
}

/**
 * Formatted release notes. Shows the first [collapsedBlocks] blocks with a "Show all" toggle when
 * there are more.
 */
@Composable
fun ReleaseNotesText(markdown: String, modifier: Modifier = Modifier, collapsedBlocks: Int = 8) {
    val blocks = remember(markdown) { ReleaseNotesFormat.parse(markdown) }
    if (blocks.isEmpty()) return
    var expanded by remember(markdown) { mutableStateOf(false) }
    val shown = if (expanded) blocks else blocks.take(collapsedBlocks)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val body = MaterialTheme.typography.bodySmall
    val bold = SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    val code = SpanStyle(fontFamily = FontFamily.Monospace)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEachIndexed { index, block ->
            when (block) {
                is ReleaseNotesFormat.Block.Heading -> Text(
                    ReleaseNotesFormat.inline(block.text, bold, code),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 6.dp)
                )
                is ReleaseNotesFormat.Block.Bullet -> Row(Modifier.padding(start = (block.level * 14).dp)) {
                    Text(if (block.level == 0) "•" else "◦", style = body, color = color)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text(ReleaseNotesFormat.inline(block.text, bold, code), style = body, color = color)
                }
                is ReleaseNotesFormat.Block.Paragraph ->
                    Text(ReleaseNotesFormat.inline(block.text, bold, code), style = body, color = color)
            }
        }
        if (blocks.size > collapsedBlocks) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if (expanded) "Show less" else "Show all notes")
            }
        }
    }
}
