package com.lyrenne.desktop.rewind

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * A span of calendar days a Rewind covers: [start] inclusive to [endExclusive], in local dates.
 *
 * Dates, not instants, on purpose. "This month" means the month on the listener's wall calendar,
 * so the instants are worked out against a zone only when the history is read ([range]). A null
 * [start] is the beginning of time and a null [endExclusive] is open-ended, which is what
 * All time is.
 */
data class RewindPeriod(
    val kind: Kind,
    val start: LocalDate?,
    val endExclusive: LocalDate?,
    /** What the cards call it: "This week", "November 2026", "2026", "All time". */
    val label: String,
) {
    enum class Kind { WEEK, MONTH, YEAR, ALL_TIME, CUSTOM }

    /** The period as epoch millis in [zone], start inclusive, end exclusive. */
    fun range(zone: ZoneId): LongRange {
        val from = start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
        val to = endExclusive?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE
        return from until to
    }

    fun contains(instant: Instant, zone: ZoneId): Boolean = instant.toEpochMilli() in range(zone)

    /** Used in file names and headings: "this year", "November 2026". */
    val lowerLabel: String get() = if (kind == Kind.WEEK || kind == Kind.ALL_TIME) label.lowercase() else label

    companion object {
        private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
        private val dayFormat = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
        private val dayYearFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

        /** The week containing [today], starting on the locale's first day of the week. */
        fun thisWeek(today: LocalDate, locale: Locale = Locale.getDefault()): RewindPeriod {
            val first: DayOfWeek = WeekFields.of(locale).firstDayOfWeek
            val start = today.with(TemporalAdjusters.previousOrSame(first))
            return RewindPeriod(Kind.WEEK, start, start.plusWeeks(1), "This week")
        }

        fun thisMonth(today: LocalDate): RewindPeriod = month(YearMonth.from(today), current = true)

        fun thisYear(today: LocalDate): RewindPeriod = year(today.year, current = true)

        fun month(month: YearMonth, current: Boolean = false): RewindPeriod = RewindPeriod(
            Kind.MONTH, month.atDay(1), month.plusMonths(1).atDay(1),
            if (current) "This month" else month.format(monthFormat)
        )

        fun year(year: Int, current: Boolean = false): RewindPeriod = RewindPeriod(
            Kind.YEAR, LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1),
            if (current) "This year" else year.toString()
        )

        fun allTime(): RewindPeriod = RewindPeriod(Kind.ALL_TIME, null, null, "All time")

        /** [from] to [to], both days included. Swapped if given backwards. */
        fun custom(from: LocalDate, to: LocalDate): RewindPeriod {
            val a = minOf(from, to)
            val b = maxOf(from, to)
            val label = when {
                a == b -> a.format(dayYearFormat)
                a.year == b.year -> "${a.format(dayFormat)} to ${b.format(dayYearFormat)}"
                else -> "${a.format(dayYearFormat)} to ${b.format(dayYearFormat)}"
            }
            return RewindPeriod(Kind.CUSTOM, a, b.plusDays(1), label)
        }
    }
}
