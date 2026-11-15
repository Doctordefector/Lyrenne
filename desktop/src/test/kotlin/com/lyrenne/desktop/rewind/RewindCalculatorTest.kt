package com.lyrenne.desktop.rewind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class RewindCalculatorTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    /** The checked-in history: see the comment at the top of the file. */
    private val fixture: List<RewindPlay> by lazy {
        val text = javaClass.getResource("/rewind/history.csv")!!.readText()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
            val f = line.split(',')
            RewindPlay(
                songId = f[0],
                title = f[1],
                artists = f[2].split('|').map { a -> a.split(':').let { RewindArtistRef(it[0], it[1]) } },
                albumId = f[3].ifEmpty { null },
                albumTitle = f[4].ifEmpty { null },
                thumbnailUrl = null,
                endedAt = Instant.parse(f[5]).toEpochMilli(),
                playTimeMs = f[6].toLong() * 1000,
            )
        }
    }

    private fun play(
        song: String,
        startLocal: String,
        zone: ZoneId,
        minutes: Long = 4,
        artists: List<RewindArtistRef> = listOf(RewindArtistRef("a-$song", "Artist $song")),
        title: String = "Song $song",
    ): RewindPlay {
        val start = LocalDateTime.parse(startLocal).atZone(zone).toInstant().toEpochMilli()
        return RewindPlay(song, title, artists, null, null, null, endedAt = start + minutes * 60_000, playTimeMs = minutes * 60_000)
    }

    // ---- The fixture, end to end

    @Test
    fun `year in UTC covers what started in it, including a play that ended after midnight`() {
        val r = RewindCalculator.compute(fixture, RewindPeriod.year(2026), utc)
        assertEquals(10, r.plays)
        assertEquals(2340_000L, r.totalMs)
        assertEquals(39L, r.minutes)
        assertEquals(listOf("s2", "s4", "s1", "s6", "s3", "s5"), r.topSongs.map { it.id })
        assertEquals(listOf(4, 2, 1, 1, 1, 1), r.topSongs.map { it.plays })
        assertEquals(listOf("a2", "a3", "a1", "a4"), r.topArtists.map { it.id })
        assertEquals(listOf(5, 3, 2, 1), r.topArtists.map { it.plays })
        // Slow Tide has no album, so it is not in the album list at all.
        assertEquals(listOf("al2", "al1", "al3"), r.topAlbums.map { it.id })
        assertEquals("Kite Theory, Mira Sol", r.topSongs.first { it.id == "s3" }.artistNames)
        assertEquals(6, r.distinctSongs)
        assertEquals(4, r.distinctArtists)
    }

    @Test
    fun `streak and replay day follow the listener's zone`() {
        val inUtc = RewindCalculator.compute(fixture, RewindPeriod.year(2026), utc)
        assertEquals(RewindStreak(4, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 4)), inUtc.streak)
        assertEquals("s2", inUtc.replay!!.song.id)
        assertEquals(4, inUtc.replay!!.plays)
        assertEquals(LocalDate.of(2026, 1, 1), inUtc.replay!!.date)

        // Same instants, nine hours ahead: the last Neon Rain moves to Jan 2, the Dec 31 play
        // moves into 2026 and the Dec 31 2026 one moves out.
        val inTokyo = RewindCalculator.compute(fixture, RewindPeriod.year(2026), tokyo)
        assertEquals(10, inTokyo.plays)
        assertTrue(inTokyo.topSongs.none { it.id == "s6" })
        assertEquals(2, inTokyo.topSongs.first { it.id == "s1" }.plays)
        assertEquals(RewindStreak(3, LocalDate.of(2026, 1, 4), LocalDate.of(2026, 1, 6)), inTokyo.streak)
        assertEquals(3, inTokyo.replay!!.plays)
        assertEquals(LocalDate.of(2026, 1, 1), inTokyo.replay!!.date)
    }

    @Test
    fun `discovery is the newcomer played most, and needs earlier history`() {
        val r = RewindCalculator.compute(fixture, RewindPeriod.year(2026), utc)
        assertNotNull(r.discovery)
        val d = r.discovery!!
        // Aurora Lane was already played in 2025; Single Visit was played once only.
        assertEquals("a2", d.artist.id)
        assertEquals(LocalDate.of(2026, 1, 1), d.firstPlayed)
        assertEquals(1140_000L, d.artist.ms)

        // All time has nothing before it, so nothing in it can be a discovery.
        assertNull(RewindCalculator.compute(fixture, RewindPeriod.allTime(), utc).discovery)

        // Without the 2025 play, 2026 is the whole history: no discovery either.
        val onlyThisYear = fixture.filter { it.endedAt >= Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() }
        assertNull(RewindCalculator.compute(onlyThisYear, RewindPeriod.year(2026), utc).discovery)
    }

    @Test
    fun `discovery ranks by listening time, then plays`() {
        val zone = utc
        val history = listOf(
            play("old", "2026-01-05T10:00", zone, artists = listOf(RewindArtistRef("veteran", "Veteran"))),
            // Many short plays...
            *Array(4) { play("short$it", "2026-02-0${it + 1}T10:00", zone, minutes = 2, artists = listOf(RewindArtistRef("quick", "Quick"))) },
            // ...lose to fewer long ones.
            play("long1", "2026-02-10T10:00", zone, minutes = 10, artists = listOf(RewindArtistRef("deep", "Deep"))),
            play("long2", "2026-02-11T10:00", zone, minutes = 10, artists = listOf(RewindArtistRef("deep", "Deep"))),
            // The veteran is played most of all in February, but is not new.
            *Array(6) { play("old", "2026-02-1${it}T20:00", zone, minutes = 5, artists = listOf(RewindArtistRef("veteran", "Veteran"))) },
        )
        val r = RewindCalculator.compute(history, RewindPeriod.month(YearMonth.of(2026, 2)), zone)
        assertEquals("deep", r.discovery!!.artist.id)
        assertEquals("veteran", r.topArtists.first().id)
    }

    // ---- Ties

    @Test
    fun `ties break by first play in the period, then by name`() {
        val zone = utc
        val history = listOf(
            play("b", "2026-03-02T10:00", zone, title = "Bravo"),
            play("a", "2026-03-03T10:00", zone, title = "Alpha"),
            play("c", "2026-03-01T10:00", zone, title = "Charlie", minutes = 5),
            play("b", "2026-03-04T10:00", zone, title = "Bravo"),
            play("a", "2026-03-05T10:00", zone, title = "Alpha"),
        )
        val r = RewindCalculator.compute(history, RewindPeriod.month(YearMonth.of(2026, 3)), zone)
        // a and b: 2 plays and 8 minutes each; b was played first. c has more time but fewer plays.
        assertEquals(listOf("b", "a", "c"), r.topSongs.map { it.id })

        // Same moment, same plays, same time: alphabetical, case-insensitive.
        val same = listOf(
            play("z", "2026-03-01T10:00", zone, title = "zebra"),
            play("y", "2026-03-01T10:00", zone, title = "Apple"),
        )
        assertEquals(listOf("y", "z"), RewindCalculator.compute(same, RewindPeriod.month(YearMonth.of(2026, 3)), zone).topSongs.map { it.id })
    }

    @Test
    fun `top lists stop at fifty`() {
        val history = (1..60).map { play("s$it", "2026-04-01T10:00", utc) }
        val r = RewindCalculator.compute(history, RewindPeriod.year(2026), utc)
        assertEquals(50, r.topSongs.size)
        assertEquals(60, r.distinctSongs)
    }

    // ---- Period boundaries

    @Test
    fun `period start is inclusive and end is exclusive, by start time`() {
        val zone = newYork
        val march = RewindPeriod.month(YearMonth.of(2026, 3))
        val history = listOf(
            // Started a minute before March, ended inside it: February's.
            play("before", "2026-02-28T23:59", zone, minutes = 4),
            play("first", "2026-03-01T00:00", zone),
            // DST starts March 8 in New York; local dates still work.
            play("dst", "2026-03-08T03:30", zone),
            // Started in the last minute of March, ended in April: March's.
            play("last", "2026-03-31T23:59", zone, minutes = 4),
            play("after", "2026-04-01T00:00", zone),
        )
        val r = RewindCalculator.compute(history, march, zone)
        assertEquals(setOf("first", "dst", "last"), r.topSongs.map { it.id }.toSet())
        assertEquals(LocalDate.of(2026, 3, 1), r.firstDay)
        assertEquals(LocalDate.of(2026, 3, 31), r.lastDay)
    }

    @Test
    fun `periods map to calendar dates`() {
        val wed = LocalDate.of(2026, 11, 18)
        assertEquals(LocalDate.of(2026, 11, 15), RewindPeriod.thisWeek(wed, Locale.US).start) // Sunday
        assertEquals(LocalDate.of(2026, 11, 16), RewindPeriod.thisWeek(wed, Locale.UK).start) // Monday
        assertEquals(LocalDate.of(2026, 11, 23), RewindPeriod.thisWeek(wed, Locale.UK).endExclusive)
        RewindPeriod.thisMonth(wed).let {
            assertEquals(LocalDate.of(2026, 11, 1), it.start)
            assertEquals(LocalDate.of(2026, 12, 1), it.endExclusive)
            assertEquals("This month", it.label)
        }
        assertEquals("November 2026", RewindPeriod.month(YearMonth.of(2026, 11)).label)
        assertEquals("2026", RewindPeriod.year(2026).label)
        RewindPeriod.custom(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 3)).let {
            assertEquals(LocalDate.of(2026, 3, 3), it.start)
            assertEquals(LocalDate.of(2026, 3, 10), it.endExclusive) // last day included
            assertEquals("Mar 3 to Mar 9, 2026", it.label)
        }
        assertEquals("Dec 30, 2025 to Jan 2, 2026", RewindPeriod.custom(LocalDate.of(2025, 12, 30), LocalDate.of(2026, 1, 2)).label)
        // A custom single day is a whole day in the zone.
        val oneDay = RewindPeriod.custom(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1))
        val range = oneDay.range(tokyo)
        assertEquals(24L * 3600 * 1000, range.last - range.first + 1)
    }

    // ---- Streaks

    @Test
    fun `streak runs across midnight`() {
        val zone = newYork
        val history = listOf(
            play("a", "2026-05-01T23:50", zone, minutes = 20), // starts on the 1st, ends on the 2nd
            play("b", "2026-05-02T00:10", zone),
            play("c", "2026-05-03T23:59", zone),
            play("d", "2026-05-05T12:00", zone),
        )
        val r = RewindCalculator.compute(history, RewindPeriod.month(YearMonth.of(2026, 5)), zone)
        assertEquals(RewindStreak(3, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3)), r.streak)
    }

    @Test
    fun `streak tie goes to the most recent run, and one day is a streak of one`() {
        val days = setOf(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 11))
        assertEquals(RewindStreak(2, LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 11)), RewindCalculator.longestStreak(days))
        assertEquals(1, RewindCalculator.longestStreak(setOf(LocalDate.of(2026, 1, 1)))!!.days)
        assertNull(RewindCalculator.longestStreak(emptySet()))
    }

    @Test
    fun `streak crosses a month and a year end`() {
        val days = setOf(LocalDate.of(2025, 12, 30), LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1))
        assertEquals(3, RewindCalculator.longestStreak(days)!!.days)
    }

    // ---- Listening clock

    @Test
    fun `clock splits plays across the hours they ran through`() {
        val zone = newYork
        val plays = listOf(
            play("mix", "2026-05-01T22:30", zone, minutes = 60),
            play("late", "2026-05-01T23:50", zone, minutes = 20),
        )
        val hours = RewindCalculator.listeningClock(plays, zone)
        assertEquals(30 * 60_000L, hours[22])
        assertEquals(40 * 60_000L, hours[23])
        assertEquals(10 * 60_000L, hours[0])
        assertEquals(80 * 60_000L, hours.sum())
        val r = RewindCalculator.compute(plays, RewindPeriod.month(YearMonth.of(2026, 5)), zone)
        assertEquals(23, r.peakHour)
    }

    @Test
    fun `clock is read in the listener's zone`() {
        val p = listOf(play("x", "2026-05-01T10:15", utc, minutes = 30))
        assertEquals(30 * 60_000L, RewindCalculator.listeningClock(p, utc)[10])
        assertEquals(30 * 60_000L, RewindCalculator.listeningClock(p, tokyo)[19])
    }

    // ---- Replay and thin data

    @Test
    fun `replay needs two plays in one day`() {
        val zone = utc
        val once = listOf(play("a", "2026-05-01T10:00", zone), play("a", "2026-05-02T10:00", zone))
        assertNull(RewindCalculator.compute(once, RewindPeriod.year(2026), zone).replay)
        val twice = once + play("a", "2026-05-02T18:00", zone)
        RewindCalculator.compute(twice, RewindPeriod.year(2026), zone).replay!!.let {
            assertEquals(2, it.plays)
            assertEquals(LocalDate.of(2026, 5, 2), it.date)
        }
    }

    @Test
    fun `empty history is an empty report, not a crash`() {
        val r = RewindCalculator.compute(emptyList(), RewindPeriod.thisYear(LocalDate.of(2026, 11, 15)), utc)
        assertTrue(r.isEmpty)
        assertEquals(0L, r.minutes)
        assertNull(r.streak)
        assertNull(r.discovery)
        assertNull(r.replay)
        assertNull(r.peakHour)
        assertTrue(r.topSongs.isEmpty())
    }

    @Test
    fun `a song with no artist still counts, just not for artists`() {
        val p = play("x", "2026-05-01T10:00", utc, artists = emptyList())
        val r = RewindCalculator.compute(listOf(p), RewindPeriod.year(2026), utc)
        assertEquals(1, r.topSongs.size)
        assertTrue(r.topArtists.isEmpty())
        assertEquals("", r.topSongs.first().artistNames)
    }
}
