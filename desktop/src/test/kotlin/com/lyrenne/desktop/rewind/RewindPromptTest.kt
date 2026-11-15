package com.lyrenne.desktop.rewind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RewindPromptTest {
    private val plenty: (RewindPeriod) -> Long = { 100L * 60 * 60 * 1000 }

    @Test
    fun `december offers this year, early january offers last year`() {
        assertEquals("year-2026", RewindPrompt.candidate(LocalDate.of(2026, 12, 1))!!.key)
        assertEquals("year-2026", RewindPrompt.candidate(LocalDate.of(2026, 12, 31))!!.key)
        assertEquals("year-2026", RewindPrompt.candidate(LocalDate.of(2027, 1, 15))!!.key)
        assertEquals("Your 2026 Rewind is ready", RewindPrompt.candidate(LocalDate.of(2027, 1, 3))!!.message)
    }

    @Test
    fun `first week of a month offers last month, then nothing until the next one`() {
        val offer = RewindPrompt.candidate(LocalDate.of(2026, 11, 1))!!
        assertEquals("month-2026-10", offer.key)
        assertEquals("Your October 2026 Rewind is ready", offer.message)
        assertEquals(LocalDate.of(2026, 10, 1), offer.period.start)
        assertEquals("month-2026-10", RewindPrompt.candidate(LocalDate.of(2026, 11, 7))!!.key)
        assertNull(RewindPrompt.candidate(LocalDate.of(2026, 11, 8)))
        assertNull(RewindPrompt.candidate(LocalDate.of(2026, 11, 15)))
        // January after the 15th: December's, no; the year prompt has had its turn.
        assertNull(RewindPrompt.candidate(LocalDate.of(2027, 1, 16)))
        // February's first week offers January.
        assertEquals("month-2027-01", RewindPrompt.candidate(LocalDate.of(2027, 2, 2))!!.key)
    }

    @Test
    fun `shown once, never when switched off, never for a quiet period`() {
        val day = LocalDate.of(2026, 12, 5)
        assertEquals("year-2026", RewindPrompt.due(day, emptySet(), true, plenty)!!.key)
        assertNull(RewindPrompt.due(day, setOf("year-2026"), true, plenty))
        assertNull(RewindPrompt.due(day, emptySet(), false, plenty))
        assertNull(RewindPrompt.due(day, emptySet(), true) { 59L * 60 * 1000 })
        // A month needs five hours.
        val monthDay = LocalDate.of(2026, 11, 2)
        assertNull(RewindPrompt.due(monthDay, emptySet(), true) { 4L * 60 * 60 * 1000 })
        assertEquals("month-2026-10", RewindPrompt.due(monthDay, emptySet(), true) { 5L * 60 * 60 * 1000 }!!.key)
    }
}
