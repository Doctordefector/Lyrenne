package com.lyrenne.desktop.lyrics

/**
 * Hangul in Revised Romanization, the way Korean lyrics are usually romanized.
 *
 * ICU's Hangul-Latin is a letter-for-letter transliteration: it writes 사랑해 as "salanghae" and
 * 싶어 as "sip-eo", which nobody singing along would recognise. Korean is pronounced across
 * syllable boundaries, so each final consonant is resolved together with the initial after it:
 * carried over before a vowel (싶어 sipeo), palatalized before 이 (같이 gachi), nasalized before
 * ㄴ and ㅁ (합니다 hamnida), silent ㅎ dropped (좋아 joa). Not every rule of the standard, the
 * common ones.
 */
internal object KoreanRomanizer {

    private const val SYLLABLE_FIRST = 0xAC00
    private const val SYLLABLE_LAST = 0xD7A3

    private val INITIAL = arrayOf(
        "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"
    )
    private val VOWEL = arrayOf(
        "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo",
        "u", "wo", "we", "wi", "yu", "eu", "ui", "i"
    )

    /** How each final sounds at the end of a word or before another consonant. Index 0 is none. */
    private val FINAL = arrayOf(
        "", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l",
        "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t"
    )

    /**
     * What a final becomes when the next syllable starts with a vowel: the part that stays, and
     * the part that moves over to begin the next syllable.
     */
    private val CARRIED = arrayOf(
        "" to "", "" to "g", "" to "kk", "k" to "s", "" to "n", "n" to "j", "n" to "", "" to "d",
        "" to "r", "l" to "g", "l" to "m", "l" to "b", "l" to "s", "l" to "t", "l" to "p", "" to "r",
        "" to "m", "" to "b", "p" to "s", "" to "s", "" to "ss", "ng" to "", "" to "j", "" to "ch",
        "" to "k", "" to "t", "" to "p", "" to ""
    )

    // Indices into the tables above that the sound rules name.
    private const val I_NIEUN = 2
    private const val I_DIGEUT = 3
    private const val I_RIEUL = 5
    private const val I_MIEUM = 6
    private const val I_SIOS = 9
    private const val I_IEUNG = 11
    private const val I_JIEUT = 12
    private const val I_GIYEOK = 0
    private const val I_HIEUH = 18
    private const val V_I = 20
    private const val F_DIGEUT = 7
    private const val F_TIEUT = 25
    private const val F_HIEUH = 27

    fun isHangulSyllable(c: Char) = c.code in SYLLABLE_FIRST..SYLLABLE_LAST

    fun romanize(text: String): String {
        val out = StringBuilder(text.length * 3)
        var i = 0
        while (i < text.length) {
            if (!isHangulSyllable(text[i])) {
                out.append(text[i])
                i++
                continue
            }
            // A run of syllables is one word as far as sound changes go.
            var end = i
            while (end < text.length && isHangulSyllable(text[end])) end++
            romanizeWord(text, i, end, out)
            i = end
        }
        return out.toString()
    }

    private fun romanizeWord(text: String, from: Int, to: Int, out: StringBuilder) {
        val syllables = (from until to).map { idx ->
            val s = text[idx].code - SYLLABLE_FIRST
            Triple(s / (21 * 28), (s % (21 * 28)) / 28, s % 28)
        }
        var initial = INITIAL[syllables[0].first]
        for (k in syllables.indices) {
            val (_, vowel, final) = syllables[k]
            out.append(initial).append(VOWEL[vowel])
            val next = syllables.getOrNull(k + 1)
            if (next == null) {
                out.append(FINAL[final])
                return
            }
            val (finalText, nextInitial) = join(final, next.first, next.second)
            out.append(finalText)
            initial = nextInitial
        }
    }

    /** Resolves a final consonant against the next syllable's initial (and vowel, for 이). */
    private fun join(final: Int, nextInitial: Int, nextVowel: Int): Pair<String, String> {
        val plainInitial = INITIAL[nextInitial]
        if (final == 0) return "" to plainInitial
        val sound = FINAL[final]
        return when {
            nextInitial == I_IEUNG -> when {
                // 같이 gachi, 굳이 guji: palatalization before 이
                final == F_TIEUT && nextVowel == V_I -> "" to "ch"
                final == F_DIGEUT && nextVowel == V_I -> "" to "j"
                else -> CARRIED[final]
            }
            final == F_HIEUH -> when (nextInitial) {
                I_GIYEOK -> "" to "k"
                I_DIGEUT -> "" to "t"
                I_JIEUT -> "" to "ch"
                I_SIOS -> "" to "ss"
                I_NIEUN -> "n" to "n"
                else -> "" to plainInitial
            }
            nextInitial == I_NIEUN || nextInitial == I_MIEUM -> when (sound) {
                "k" -> "ng" to plainInitial
                "t" -> "n" to plainInitial
                "p" -> "m" to plainInitial
                "l" -> if (nextInitial == I_NIEUN) "l" to "l" else sound to plainInitial
                else -> sound to plainInitial
            }
            nextInitial == I_RIEUL -> when (sound) {
                "n", "l" -> "l" to "l"
                "k" -> "ng" to "n"
                "t" -> "n" to "n"
                "p" -> "m" to "n"
                else -> sound to "n"
            }
            else -> sound to plainInitial
        }
    }
}
