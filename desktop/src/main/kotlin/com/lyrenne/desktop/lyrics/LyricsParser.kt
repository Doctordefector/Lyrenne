package com.lyrenne.desktop.lyrics

/** One sung word, timed in milliseconds from the start of the track. */
data class LyricWord(val text: String, val startMs: Long, val endMs: Long)

/**
 * One displayed lyric line.
 *
 * [words] is present only when the provider sent word timings, and drives the word-by-word
 * highlight. [background] holds the backing-vocal lines that belong to this one: they are drawn
 * under it and highlighted with it, rather than becoming lines of their own that would steal the
 * "current line" from the lead vocal they overlap.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord>? = null,
    val background: List<LyricLine> = emptyList(),
)

/**
 * Turns whatever a lyrics provider sent into timed lines.
 *
 * The providers do not agree on a format, and the parser this replaced only understood the
 * narrowest one: `[mm:ss.xx]text`, one timestamp, nothing else in the file. Anything outside that
 * threw, and a throw meant the whole song fell back to the plain view showing the raw text. That is
 * issue #10: BetterLyrics sends the word-timed extension upstream's TTML converter writes, where each
 * line is followed by a `<word:start:end|word:start:end>` line in seconds, and every one of those
 * lines landed on screen verbatim.
 *
 * Understood here:
 *  - plain LRC, with two or three fractional digits, or none
 *  - several timestamps on one line, `[00:12.00][00:48.00]chorus`, one entry per timestamp
 *  - ID tags such as `[ar:...]` and `[length:...]`, skipped; `[offset:...]` is applied
 *  - BetterLyrics word lines, attached to the line above them
 *  - inline rich sync, `[00:01.00]<00:01.00>word <00:01.50>word`, as used by enhanced LRC
 *  - `{agent:v1}` and `{bg}` markers; agents are dropped, `{bg}` lines become [LyricLine.background]
 *
 * Returns null when nothing in the text carries a timestamp, which is how callers tell synced
 * lyrics from plain ones. Never throws: an unreadable line is skipped rather than allowed to sink
 * the rest of the song.
 */
object LyricsParser {

    private val LEADING_TIMESTAMPS = Regex("""^(?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+""")
    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val ID_TAG = Regex("""^\[([a-zA-Z#]+):(.*)]$""")
    private val INLINE_WORD_TIME = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val AGENT = Regex("""\{agent:[^}]*}""")
    private const val BG_MARKER = "{bg}"

    /**
     * How long the last word of an inline rich-sync line is held when nothing follows it to
     * mark its end. Inline tags only carry start times.
     */
    private const val LAST_WORD_HOLD_MS = 1_000L

    fun parse(raw: String): List<LyricLine>? {
        val text = decodeEntities(raw.replace("\r\n", "\n").replace('\r', '\n'))

        var offsetMs = 0L
        // Built as mutable pairs so a following word line can attach to the entries it belongs to.
        val entries = mutableListOf<MutableEntry>()
        // Entries produced by the most recent timed line: the ones a BetterLyrics word line refers
        // to. A multi-timestamp line produces several, and each needs its own shifted copy.
        var lastLine: List<MutableEntry> = emptyList()

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("<") && line.endsWith(">") && !INLINE_WORD_TIME.matches(line)) {
                val words = parseWordLine(line)
                if (words != null && lastLine.isNotEmpty()) {
                    val base = lastLine.first().timeMs
                    lastLine.forEach { entry ->
                        val shift = entry.timeMs - base
                        entry.words = words.map {
                            it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift)
                        }
                    }
                }
                // A word line is never displayable text, even when it cannot be read.
                continue
            }

            val stamps = LEADING_TIMESTAMPS.find(line)
            if (stamps == null) {
                ID_TAG.matchEntire(line)?.let { tag ->
                    if (tag.groupValues[1].equals("offset", ignoreCase = true)) {
                        offsetMs = tag.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: 0L
                    }
                }
                lastLine = emptyList()
                continue
            }

            val times = TIMESTAMP.findAll(stamps.value).map { it.toMillis() }.toList()
            var content = line.substring(stamps.range.last + 1)

            val isBackground = content.contains(BG_MARKER)
            content = content.replace(BG_MARKER, "").replace(AGENT, "")

            val inlineWords = parseInlineWords(content)
            val display = if (inlineWords != null) {
                content.replace(INLINE_WORD_TIME, " ").collapseSpaces()
            } else {
                content.collapseSpaces()
            }

            val produced = times.map { time ->
                MutableEntry(
                    timeMs = time,
                    text = display,
                    isBackground = isBackground,
                    words = inlineWords?.let { words ->
                        val shift = time - (times.firstOrNull() ?: time)
                        words.map { it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift) }
                    },
                )
            }
            entries += produced
            lastLine = produced
        }

        if (entries.isEmpty()) return null

        // Offset is applied last because the tag may sit anywhere in the header. Per the LRC
        // convention a positive offset makes the lyrics appear sooner.
        val shifted = entries.map { it.shift(-offsetMs) }.sortedBy { it.timeMs }

        // Inline rich-sync words know only when they start. The last one ends where the next line
        // begins, or after a short hold at the end of the song.
        val sorted = shifted.mapIndexed { index, entry ->
            val words = entry.words ?: return@mapIndexed entry
            val nextStart = shifted.drop(index + 1).firstOrNull { !it.isBackground }?.timeMs
            entry.copy(words = words.mapIndexed { i, w ->
                if (w.endMs > w.startMs) w
                else {
                    val end = words.getOrNull(i + 1)?.startMs
                        ?: nextStart?.takeIf { it > w.startMs }
                        ?: (w.startMs + LAST_WORD_HOLD_MS)
                    w.copy(endMs = end)
                }
            })
        }

        return group(sorted)
    }

    /** Folds each `{bg}` entry into the lead line before it. A leading orphan stays a line. */
    private fun group(sorted: List<MutableEntry>): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (entry in sorted) {
            val previous = lines.lastOrNull()
            if (entry.isBackground && previous != null && entry.text.isNotBlank()) {
                lines[lines.lastIndex] = previous.copy(background = previous.background + entry.toLine())
            } else if (entry.text.isNotBlank() || entry.words != null) {
                lines += entry.toLine()
            } else {
                // A timed empty line is an instrumental gap. Kept, so the highlight leaves the
                // last sung line instead of sitting on it through a solo.
                lines += LyricLine(entry.timeMs, "")
            }
        }
        return lines
    }

    /**
     * Reads a BetterLyrics word line: `<word:1.23:1.56|word:1.56:2.01>`, times in seconds.
     *
     * Split on the last two colons of each segment rather than on every colon, because the word
     * itself can contain one. Returns null if any segment is unreadable, so a half-parsed line
     * never highlights the wrong words.
     */
    internal fun parseWordLine(line: String): List<LyricWord>? {
        val body = line.removePrefix("<").removeSuffix(">")
        if (body.isEmpty()) return null
        val words = body.split('|').map { segment ->
            val endSep = segment.lastIndexOf(':')
            if (endSep <= 0) return null
            val startSep = segment.lastIndexOf(':', endSep - 1)
            if (startSep < 0) return null
            val start = segment.substring(startSep + 1, endSep).toDoubleOrNull() ?: return null
            val end = segment.substring(endSep + 1).toDoubleOrNull() ?: return null
            val word = segment.substring(0, startSep)
            LyricWord(word, (start * 1000).toLong(), (end * 1000).toLong())
        }
        return words.takeIf { it.isNotEmpty() }
    }

    /**
     * Reads inline rich sync, `<00:01.00>word <00:01.50>word`. End times are left equal to the
     * start and filled in by the caller, since only starts are written.
     */
    private fun parseInlineWords(content: String): List<LyricWord>? {
        val tags = INLINE_WORD_TIME.findAll(content).toList()
        if (tags.isEmpty()) return null
        val words = tags.mapIndexedNotNull { i, tag ->
            val textEnd = tags.getOrNull(i + 1)?.range?.first ?: content.length
            val word = content.substring(tag.range.last + 1, textEnd).trim()
            if (word.isEmpty()) return@mapIndexedNotNull null
            val start = tag.toMillis()
            LyricWord(word, start, start)
        }
        return words.takeIf { it.isNotEmpty() }?.let { list ->
            // Each word runs until the next one starts.
            list.mapIndexed { i, w -> list.getOrNull(i + 1)?.let { w.copy(endMs = it.startMs) } ?: w }
        }
    }

    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLongOrNull() ?: 0L
        val seconds = groupValues[2].toLongOrNull() ?: 0L
        val fraction = groupValues[3]
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return minutes * 60_000 + seconds * 1_000 + millis
    }

    private fun String.collapseSpaces() = replace(Regex("""\s+"""), " ").trim()

    /** Providers occasionally send HTML-escaped text. Only the entities actually seen are handled. */
    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return s.replace(Regex("""&#x([0-9a-fA-F]+);""")) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }.replace(Regex("""&#(\d+);""")) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private data class MutableEntry(
        val timeMs: Long,
        val text: String,
        val isBackground: Boolean,
        var words: List<LyricWord>?,
    ) {
        fun shift(by: Long) = copy(
            timeMs = (timeMs + by).coerceAtLeast(0),
            words = words?.map { it.copy(startMs = it.startMs + by, endMs = it.endMs + by) },
        )

        fun toLine() = LyricLine(timeMs, text, words)
    }
}
