package com.lyrenne.desktop.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomanizerTest {

    private fun one(vararg song: String): Map<String, String> = Romanizer.romanize(song.toList())

    @Test
    fun `japanese reads kanji as japanese words, particles as said`() {
        val r = one("君の名は", "私は学校へ行きます", "東京に行った", "ラーメンを食べたい", "Hello 世界 yeah")
        assertEquals("kimi no na wa", r["君の名は"])
        assertEquals("watashi wa gakkou e ikimasu", r["私は学校へ行きます"])
        assertEquals("toukyou ni itta", r["東京に行った"])
        assertEquals("rāmen o tabetai", r["ラーメンを食べたい"])
        // Kanji-only line in a song with kana is read as Japanese, not as Mandarin.
        assertEquals("Hello sekai yeah", r["Hello 世界 yeah"])
    }

    @Test
    fun `chinese gets pinyin with tones`() {
        assertEquals("wǒ ài nǐ", one("我爱你")["我爱你"])
    }

    @Test
    fun `korean follows the sound changes`() {
        val r = one("사랑해요 baby", "보고 싶어", "안녕하세요", "같이 가요", "좋아", "감사합니다")
        assertEquals("saranghaeyo baby", r["사랑해요 baby"])
        assertEquals("bogo sipeo", r["보고 싶어"])
        assertEquals("annyeonghaseyo", r["안녕하세요"])
        assertEquals("gachi gayo", r["같이 가요"])
        assertEquals("joa", r["좋아"])
        assertEquals("gamsahamnida", r["감사합니다"])
    }

    @Test
    fun `cyrillic reads per language`() {
        assertEquals("Ya tebya lyublyu", one("Я тебя люблю")["Я тебя люблю"])
        // і marks the song as Ukrainian, where и is y.
        val uk = one("Що ти робиш", "і я")
        assertEquals("Shcho ty robysh", uk["Що ти робиш"])
    }

    @Test
    fun `latin lines and unreadable scripts get nothing`() {
        val r = one("Hello there", "Café déjà vu", "مرحبا", "สวัสดี")
        assertTrue(r.isEmpty())
        assertFalse(Romanizer.needsRomanization("Ça va, señor"))
        assertNull(one("Hello", "Привет")["Hello"])
    }
}
