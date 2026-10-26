package com.lyrenne.desktop.lyrics

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import com.ibm.icu.text.Transliterator
import timber.log.Timber

/**
 * Latin-letter readings of lyrics written in other scripts, shown under each line.
 *
 * Offline and free, like everything else here. ICU's `Any-Latin` transliterates every script it
 * knows (Cyrillic, Hangul, Greek, Devanagari, Arabic, Thai, Han as Mandarin pinyin with tones...).
 * Japanese is the one it gets wrong: kanji have no reading without knowing the word, and ICU reads
 * them as Chinese. So a song with any kana in it goes through kuromoji, which splits the line into
 * words and knows how each is read, and ICU only spells the kana out.
 *
 * Upstream Android does this with kuromoji, TinyPinyin and hand-written tables per language. ICU
 * replaces the tables and the pinyin, and gives tone marks, which TinyPinyin does not.
 */
object Romanizer {

    /** kuromoji's dictionary is tens of MB of heap, so it loads only when a Japanese song shows. */
    private val tokenizer: Tokenizer by lazy {
        val start = System.nanoTime()
        Tokenizer().also { Timber.i("Japanese tokenizer loaded in ${(System.nanoTime() - start) / 1_000_000} ms") }
    }

    // Transliterator instances are not thread-safe, hence the locks around their use.
    private val anyLatin: Transliterator by lazy { Transliterator.getInstance("Any-Latin; NFC") }
    private val kanaLatin: Transliterator by lazy { Transliterator.getInstance("Katakana-Latin; Hiragana-Latin; NFC") }

    /** One song's results, so reopening the panel or toggling the setting back on costs nothing. */
    private var cacheKey: List<String>? = null
    private var cacheValue: Map<String, String> = emptyMap()

    /** True when [text] holds a letter from a script other than Latin. */
    fun needsRomanization(text: String): Boolean = text.codePoints().anyMatch(::isForeignLetter)

    /**
     * The romanized form of every line in [lines] that needs one, keyed by the line's own text.
     *
     * Takes the whole song at once, because whether a kanji-only line is Japanese or Chinese can
     * only be told from the rest of the song. Lines that are already Latin get no entry. Never
     * throws: a line that cannot be read is left without a romanization.
     */
    @Synchronized
    fun romanize(lines: List<String>): Map<String, String> {
        if (lines == cacheKey) return cacheValue
        val japanese = lines.any { line -> line.codePoints().anyMatch(::isKana) }
        val cyrillic by lazy { cyrillicFor(lines) }
        val result = lines.asSequence()
            .filter(::needsRomanization)
            .distinct()
            .mapNotNull { line ->
                val romanized = try {
                    when {
                        japanese && line.codePoints().anyMatch { isKana(it) || isHan(it) } -> japanese(line)
                        line.any(KoreanRomanizer::isHangulSyllable) ->
                            anyLatin.transliterate(KoreanRomanizer.romanize(line))
                        line.codePoints().anyMatch(::isCyrillic) -> bgn(cyrillic, line)
                        else -> anyLatin.transliterate(line)
                    }
                } catch (e: Exception) {
                    Timber.w("Could not romanize a lyric line: ${e.message}")
                    null
                }
                romanized?.collapseSpaces()?.takeIf { it.isNotEmpty() && it != line }?.let { line to it }
            }
            .toMap()
        cacheKey = lines
        cacheValue = result
        return result
    }

    /**
     * Reads a Japanese line word by word.
     *
     * kuromoji splits conjugations off their stem (食べ / た) and sokuon off what follows (行っ / た),
     * so a token is joined to the one before it when it is an auxiliary, a suffix or a dependent
     * form, the way romaji lyrics are written ("tabeta", not "tabe ta"). Each group is spelled out
     * in one go, which also lets a trailing ッ double the next consonant.
     */
    internal fun japanese(line: String): String {
        val groups = mutableListOf<StringBuilder>()
        var attachNext = false
        for (token in tokenizer.tokenize(line)) {
            val surface = token.surface
            if (surface.isBlank()) {
                attachNext = false
                groups += StringBuilder(" ")
                continue
            }
            val kana = kanaFor(token)
            val joins = attachNext || token.attachesToPrevious()
            if (joins && groups.isNotEmpty() && groups.last().toString() != " ") groups.last().append(kana)
            else groups += StringBuilder(kana)
            attachNext = kana.endsWith('ッ') || kana.endsWith('っ')
        }
        return groups.joinToString(" ") { group ->
            synchronized(kanaLatin) { kanaLatin.transliterate(group.toString()) }
        }.let(::latinPunctuation)
    }

    /** What to spell out for one token: its reading, with the particles said differently. */
    private fun kanaFor(token: Token): String {
        if (token.partOfSpeechLevel1 == "助詞") {
            when (token.surface) {
                "は" -> return "ワ"
                "へ" -> return "エ"
                "を" -> return "オ"
            }
        }
        val reading = token.reading
        // Unknown words have no reading. Kana spell themselves; anything else is left as written,
        // since a Chinese reading of a Japanese kanji would be wrong rather than merely missing.
        return if (reading.isNullOrEmpty() || reading == "*") token.surface else reading
    }

    private fun Token.attachesToPrevious(): Boolean =
        partOfSpeechLevel1 == "助動詞" ||
            partOfSpeechLevel2 == "接尾" ||
            partOfSpeechLevel2 == "非自立" ||
            partOfSpeechLevel2 == "接続助詞" && surface in setOf("て", "で") ||
            partOfSpeechLevel1 == "記号"

    private val JAPANESE_PUNCTUATION = mapOf(
        '、' to ",", '。' to ".", '！' to "!", '？' to "?", '「' to "\"", '」' to "\"",
        '『' to "\"", '』' to "\"", '（' to "(", '）' to ")", '・' to " ", '〜' to "~", '～' to "~",
        '　' to " ",
    )

    private fun latinPunctuation(s: String): String = buildString {
        for (c in s) append(JAPANESE_PUNCTUATION[c] ?: c)
    }

    /**
     * Which Cyrillic language a song is in, from the letters only some of them have. ISO 9, ICU's
     * default, spells я as "â" and щ as "ŝ"; BGN/PCGN gives the "ya" and "shch" people expect,
     * but its spelling differs per language (и is "i" in Russian, "y" in Ukrainian).
     */
    private fun cyrillicFor(lines: List<String>): Transliterator {
        val all = lines.joinToString("\n").lowercase()
        val id = when {
            all.any { it in "іїєґ" } -> "Ukrainian-Latin/BGN"
            all.any { it == 'ў' } -> "Belarusian-Latin/BGN"
            all.any { it in "ђћџ" } -> "Serbian-Latin/BGN"
            all.any { it in "ѓќѕ" } -> "Macedonian-Latin/BGN"
            else -> "Russian-Latin/BGN"
        }
        return Transliterator.getInstance("$id; NFC")
    }

    /**
     * BGN, word by word. It capitalises a whole digraph when it stands for one capital letter, which
     * is right inside a shouted word but turns the word "Я" into "YA", and it marks soft and hard
     * signs with primes. Lyrics read better as "Ya" and without them.
     */
    private fun bgn(transliterator: Transliterator, line: String): String =
        line.replace(Regex("""\p{L}+""")) { m ->
            val word = m.value
            val latin = transliterator.transliterate(word)
            if (word.length == 1 && word[0].isUpperCase()) latin.lowercase().replaceFirstChar { it.uppercaseChar() }
            else latin
        }.replace("ʹ", "").replace("ʺ", "")

    /**
     * Letters worth romanizing. Arabic, Hebrew, Thai and their neighbours are left out on purpose:
     * they leave most vowels unwritten, so a letter-by-letter spelling ("mrḥbạ" for مرحبا) is
     * unreadable, and showing nothing is better than showing that.
     */
    private fun isForeignLetter(cp: Int): Boolean {
        if (!Character.isLetter(cp)) return false
        return when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED,
            Character.UnicodeScript.ARABIC, Character.UnicodeScript.HEBREW, Character.UnicodeScript.THAI,
            Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR,
            Character.UnicodeScript.SYRIAC, Character.UnicodeScript.THAANA -> false
            else -> true
        }
    }

    private fun isCyrillic(cp: Int) = Character.UnicodeScript.of(cp) == Character.UnicodeScript.CYRILLIC

    private fun isKana(cp: Int): Boolean {
        val script = Character.UnicodeScript.of(cp)
        // The prolonged sound mark ー is COMMON, and so are the half-width forms' marks. Only
        // real kana letters decide that a song is Japanese.
        return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
    }

    private fun isHan(cp: Int) = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN

    private fun String.collapseSpaces() =
        replace(Regex("""\s+"""), " ").replace(Regex(""" ([,.!?)])"""), "$1").replace("( ", "(").trim()
}
