package com.lyrenne.desktop.rewind

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** An artist credited on a played song. */
data class RewindArtistRef(val id: String, val name: String, val thumbnailUrl: String? = null)

/**
 * One play from the local history (the Event table), with what the cards need to show it.
 *
 * [endedAt] is the Event's timestamp, which the player writes when a song stops, and
 * [playTimeMs] is the time it actually played. The play is placed at its start, [startedAt]:
 * a song started at 23:58 belongs to that evening, not to the next day.
 */
data class RewindPlay(
    val songId: String,
    val title: String,
    val artists: List<RewindArtistRef>,
    val albumId: String?,
    val albumTitle: String?,
    val thumbnailUrl: String?,
    val albumThumbnailUrl: String? = null,
    val durationSec: Long = -1,
    val endedAt: Long,
    val playTimeMs: Long,
) {
    val startedAt: Long get() = endedAt - playTimeMs.coerceAtLeast(0)
    val artistNames: String get() = artists.joinToString(", ") { it.name }
}

data class RankedSong(
    val id: String,
    val title: String,
    val artistNames: String,
    val thumbnailUrl: String?,
    val albumTitle: String?,
    val durationSec: Long,
    val plays: Int,
    val ms: Long,
)

data class RankedArtist(
    val id: String,
    val name: String,
    val thumbnailUrl: String?,
    val plays: Int,
    val ms: Long,
)

data class RankedAlbum(
    val id: String,
    val title: String,
    val artistNames: String,
    val thumbnailUrl: String?,
    val plays: Int,
    val ms: Long,
)

/** The longest run of consecutive days with at least one play. */
data class RewindStreak(val days: Int, val from: LocalDate, val to: LocalDate)

/** The artist first played in this period who was then played the most. */
data class RewindDiscovery(val artist: RankedArtist, val firstPlayed: LocalDate)

/** The most times one song was played in one day. */
data class RewindReplay(val song: RankedSong, val date: LocalDate, val plays: Int)

data class RewindReport(
    val period: RewindPeriod,
    val totalMs: Long,
    val plays: Int,
    val distinctSongs: Int,
    val distinctArtists: Int,
    val topSongs: List<RankedSong>,
    val topArtists: List<RankedArtist>,
    val topAlbums: List<RankedAlbum>,
    /** Listening time per hour of the local day, 0 to 23. */
    val hourMs: List<Long>,
    val streak: RewindStreak?,
    val discovery: RewindDiscovery?,
    val replay: RewindReplay?,
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
) {
    val minutes: Long get() = totalMs / 60_000
    val isEmpty: Boolean get() = plays == 0

    /** The hour with the most listening, or null with no listening at all. */
    val peakHour: Int? get() = hourMs.indices.maxByOrNull { hourMs[it] }?.takeIf { hourMs[it] > 0 }
}

/**
 * Everything Rewind shows, worked out from the local play history. Pure: no database, no clock,
 * no default zone, so every rule here is pinned by RewindCalculatorTest.
 *
 * Rules worth knowing:
 * - A play belongs to the local day and hour it **started** in ([RewindPlay.startedAt]).
 * - The listening clock splits each play across the hours it ran through, so an hour-long mix
 *   started at 22:30 counts half to 22:00 and half to 23:00, and a play over midnight lands on
 *   both sides of it.
 * - Top lists rank by plays, then listening time, then whichever was first played earlier in the
 *   period, then by name, so ties always come out in the same order.
 * - Discovery needs history from before the period. Without it every artist would be "new" and
 *   the card would just repeat the top artist.
 */
object RewindCalculator {
    const val TOP_LIMIT = 50

    fun compute(history: List<RewindPlay>, period: RewindPeriod, zone: ZoneId): RewindReport {
        val range = period.range(zone)
        val inPeriod = history.filter { it.startedAt in range && it.playTimeMs > 0 }.sortedBy { it.startedAt }
        val dayOf = { p: RewindPlay -> Instant.ofEpochMilli(p.startedAt).atZone(zone).toLocalDate() }

        val firstInPeriod = HashMap<String, Long>()
        fun firstSeen(key: String, at: Long) { firstInPeriod.merge(key, at, ::minOf) }

        // Songs
        class Acc(var plays: Int = 0, var ms: Long = 0)
        val songAcc = LinkedHashMap<String, Acc>()
        val songInfo = HashMap<String, RewindPlay>()
        val artistAcc = LinkedHashMap<String, Acc>()
        val artistInfo = HashMap<String, RewindArtistRef>()
        val albumAcc = LinkedHashMap<String, Acc>()
        val albumInfo = HashMap<String, RewindPlay>()
        for (p in inPeriod) {
            songAcc.getOrPut(p.songId) { Acc() }.apply { plays++; ms += p.playTimeMs }
            songInfo.putIfAbsent(p.songId, p)
            firstSeen("s:${p.songId}", p.startedAt)
            for (a in p.artists.distinctBy { it.id }) {
                artistAcc.getOrPut(a.id) { Acc() }.apply { plays++; ms += p.playTimeMs }
                // Keep the first ref that has a picture.
                val known = artistInfo[a.id]
                if (known == null || (known.thumbnailUrl == null && a.thumbnailUrl != null)) artistInfo[a.id] = a
                firstSeen("a:${a.id}", p.startedAt)
            }
            val albumId = p.albumId
            if (albumId != null && !p.albumTitle.isNullOrBlank()) {
                albumAcc.getOrPut(albumId) { Acc() }.apply { plays++; ms += p.playTimeMs }
                albumInfo.putIfAbsent(albumId, p)
                firstSeen("b:$albumId", p.startedAt)
            }
        }

        fun <T> rank(acc: Map<String, Acc>, prefix: String, name: (String) -> String, make: (String, Acc) -> T): List<T> =
            acc.entries.sortedWith(
                compareByDescending<Map.Entry<String, Acc>> { it.value.plays }
                    .thenByDescending { it.value.ms }
                    .thenBy { firstInPeriod["$prefix${it.key}"] ?: Long.MAX_VALUE }
                    .thenBy { name(it.key).lowercase() }
                    .thenBy { it.key }
            ).take(TOP_LIMIT).map { make(it.key, it.value) }

        fun songOf(id: String, plays: Int, ms: Long): RankedSong {
            val p = songInfo.getValue(id)
            return RankedSong(id, p.title, p.artistNames, p.thumbnailUrl ?: p.albumThumbnailUrl, p.albumTitle, p.durationSec, plays, ms)
        }

        val topSongs = rank(songAcc, "s:", { songInfo.getValue(it).title }) { id, a -> songOf(id, a.plays, a.ms) }
        val allArtists = rank(artistAcc, "a:", { artistInfo.getValue(it).name }) { id, a ->
            val ref = artistInfo.getValue(id)
            RankedArtist(id, ref.name, ref.thumbnailUrl, a.plays, a.ms)
        }
        val topAlbums = rank(albumAcc, "b:", { albumInfo.getValue(it).albumTitle.orEmpty() }) { id, a ->
            val p = albumInfo.getValue(id)
            RankedAlbum(id, p.albumTitle.orEmpty(), p.artistNames, p.albumThumbnailUrl ?: p.thumbnailUrl, a.plays, a.ms)
        }

        val days = inPeriod.map(dayOf)
        return RewindReport(
            period = period,
            totalMs = inPeriod.sumOf { it.playTimeMs },
            plays = inPeriod.size,
            distinctSongs = songAcc.size,
            distinctArtists = artistAcc.size,
            topSongs = topSongs,
            topArtists = allArtists,
            topAlbums = topAlbums,
            hourMs = listeningClock(inPeriod, zone),
            streak = longestStreak(days.toSet()),
            discovery = discovery(history, inPeriod, range, zone, artistAcc.mapValues { it.value.plays to it.value.ms }, artistInfo),
            replay = replay(inPeriod, zone, ::songOf),
            firstDay = days.minOrNull(),
            lastDay = days.maxOrNull(),
        )
    }

    /** Listening time per local hour of day, each play split over the hours it covered. */
    fun listeningClock(plays: List<RewindPlay>, zone: ZoneId): List<Long> {
        val hours = LongArray(24)
        for (p in plays) {
            var t = p.startedAt
            val end = p.endedAt.coerceAtLeast(t)
            while (t < end) {
                val at = Instant.ofEpochMilli(t).atZone(zone)
                val nextHour = at.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                val sliceEnd = minOf(end, nextHour)
                hours[at.hour] += sliceEnd - t
                t = sliceEnd
            }
        }
        return hours.toList()
    }

    /** Longest run of consecutive dates; on a tie, the most recent run. */
    fun longestStreak(days: Set<LocalDate>): RewindStreak? {
        if (days.isEmpty()) return null
        val sorted = days.sorted()
        var best = RewindStreak(1, sorted[0], sorted[0])
        var runStart = sorted[0]
        var prev = sorted[0]
        for (d in sorted.drop(1)) {
            if (d != prev.plusDays(1)) runStart = d
            prev = d
            val len = ChronoUnit.DAYS.between(runStart, d).toInt() + 1
            if (len >= best.days) best = RewindStreak(len, runStart, d)
        }
        return best
    }

    private fun discovery(
        history: List<RewindPlay>,
        inPeriod: List<RewindPlay>,
        range: LongRange,
        zone: ZoneId,
        artistTotals: Map<String, Pair<Int, Long>>,
        artistInfo: Map<String, RewindArtistRef>,
    ): RewindDiscovery? {
        val hasEarlierHistory = history.any { it.startedAt < range.first && it.playTimeMs > 0 }
        if (!hasEarlierHistory || inPeriod.isEmpty()) return null
        val firstEver = HashMap<String, Long>()
        for (p in history) {
            if (p.playTimeMs <= 0) continue
            for (a in p.artists) firstEver.merge(a.id, p.startedAt, ::minOf)
        }
        val newcomers = artistTotals.filter { (id, totals) ->
            val first = firstEver[id] ?: return@filter false
            first in range && totals.first >= 2
        }
        val best = newcomers.entries.sortedWith(
            compareByDescending<Map.Entry<String, Pair<Int, Long>>> { it.value.second }
                .thenByDescending { it.value.first }
                .thenBy { firstEver[it.key] ?: Long.MAX_VALUE }
                .thenBy { artistInfo[it.key]?.name?.lowercase().orEmpty() }
        ).firstOrNull() ?: return null
        val ref = artistInfo.getValue(best.key)
        return RewindDiscovery(
            RankedArtist(best.key, ref.name, ref.thumbnailUrl, best.value.first, best.value.second),
            Instant.ofEpochMilli(firstEver.getValue(best.key)).atZone(zone).toLocalDate()
        )
    }

    /** The song played most times within one local day; at least twice to count. */
    private fun replay(inPeriod: List<RewindPlay>, zone: ZoneId, songOf: (String, Int, Long) -> RankedSong): RewindReplay? {
        data class Key(val songId: String, val date: LocalDate)
        val counts = LinkedHashMap<Key, Pair<Int, Long>>()
        for (p in inPeriod) {
            val k = Key(p.songId, ZonedDateTime.ofInstant(Instant.ofEpochMilli(p.startedAt), zone).toLocalDate())
            val (c, ms) = counts[k] ?: (0 to 0L)
            counts[k] = (c + 1) to (ms + p.playTimeMs)
        }
        val best = counts.entries.sortedWith(
            compareByDescending<Map.Entry<Key, Pair<Int, Long>>> { it.value.first }
                .thenByDescending { it.value.second }
                .thenBy { it.key.date }
                .thenBy { it.key.songId }
        ).firstOrNull() ?: return null
        if (best.value.first < 2) return null
        return RewindReplay(songOf(best.key.songId, best.value.first, best.value.second), best.key.date, best.value.first)
    }
}

/** The cards a Rewind steps through, in order. Cards with nothing to say are left out. */
enum class RewindCardKind { INTRO, MINUTES, TOP_SONGS, TOP_ARTISTS, TOP_ALBUMS, CLOCK, STREAK, DISCOVERY, REPLAY, SUMMARY }

object RewindText {
    fun cards(r: RewindReport): List<RewindCardKind> = if (r.isEmpty) emptyList() else buildList {
        add(RewindCardKind.INTRO)
        add(RewindCardKind.MINUTES)
        add(RewindCardKind.TOP_SONGS)
        if (r.topArtists.isNotEmpty()) add(RewindCardKind.TOP_ARTISTS)
        if (r.topAlbums.isNotEmpty()) add(RewindCardKind.TOP_ALBUMS)
        // One or two plays do not make a daily rhythm.
        if (r.peakHour != null && r.plays >= 5) add(RewindCardKind.CLOCK)
        if ((r.streak?.days ?: 0) >= 2) add(RewindCardKind.STREAK)
        if (r.discovery != null) add(RewindCardKind.DISCOVERY)
        if (r.replay != null) add(RewindCardKind.REPLAY)
        add(RewindCardKind.SUMMARY)
    }

    /** "11 PM", "12 AM", with a no-break space so the hour never wraps away from its AM or PM. */
    fun hour(h: Int): String = when (val h12 = h % 12) {
        0 -> if (h < 12) "12\u00A0AM" else "12\u00A0PM"
        else -> "$h12\u00A0${if (h < 12) "AM" else "PM"}"
    }

    /** What kind of listener the peak hour makes you. */
    fun listener(peakHour: Int): String = when (peakHour) {
        in 5..10 -> "an early riser"
        in 11..16 -> "a daytime listener"
        in 17..21 -> "an evening listener"
        else -> "a night owl"
    }

    /** A sense of scale for a number of minutes. */
    fun minutesInContext(minutes: Long): String = when {
        minutes >= 2 * 24 * 60 -> "That's about ${minutes / (24 * 60)} days of music."
        minutes >= 120 -> "That's about ${minutes / 60} hours of music."
        minutes >= 60 -> "That's over an hour of music."
        minutes >= 1 -> "A start. Every Rewind begins somewhere."
        else -> "Less than a minute so far."
    }
}
