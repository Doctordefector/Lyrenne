package com.lyrenne.desktop.rewind

import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.playback.SongInfo
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Reads the local play history for Rewind. Nothing here touches the network: the Event table is
 * filled by the player as songs play, on this machine only.
 *
 * Call off the UI thread.
 */
object RewindRepository {
    /** A day of slack past the period end: a play that started inside it is stored by its end. */
    private const val END_SLACK_MS = 24L * 60 * 60 * 1000

    fun load(period: RewindPeriod, zone: ZoneId = ZoneId.systemDefault()): RewindReport {
        val range = period.range(zone)
        val until = if (range.last >= Long.MAX_VALUE - END_SLACK_MS) Long.MAX_VALUE else range.last + END_SLACK_MS
        return RewindCalculator.compute(history(until), period, zone)
    }

    fun history(untilMs: Long): List<RewindPlay> {
        val artists = DatabaseHelper.getRewindArtists().groupBy({ it.songId }) {
            RewindArtistRef(it.id, it.name, it.thumbnailUrl)
        }
        return DatabaseHelper.getRewindPlays(untilMs).map { row ->
            RewindPlay(
                songId = row.songId,
                title = row.title,
                artists = artists[row.songId].orEmpty(),
                albumId = row.albumId,
                albumTitle = row.albumTitle,
                thumbnailUrl = row.thumbnailUrl,
                albumThumbnailUrl = row.albumThumbnailUrl,
                durationSec = row.duration,
                endedAt = row.timestamp,
                playTimeMs = row.playTime,
            )
        }
    }

    /** The period's top songs as a queue, most played first. */
    fun queueOf(report: RewindReport): List<SongInfo> = report.topSongs.map {
        SongInfo(
            id = it.id,
            title = it.title,
            artist = it.artistNames.ifBlank { "Unknown artist" },
            thumbnailUrl = it.thumbnailUrl,
            durationMs = if (it.durationSec > 0) it.durationSec * 1000 else 0L,
            album = it.albumTitle,
            duration = it.durationSec.toInt(),
        )
    }
}

/**
 * When to offer a Rewind without being asked, and which one. Pure, so the rules are tested.
 *
 * - December 1 to January 15: the year's Rewind (in January, last year's).
 * - The first week of any other month: last month's, but only if it was a real month of
 *   listening ([MONTH_MINIMUM_MS]), so a quiet month never produces a prompt about nothing.
 * - Each offer is made once, ever: its key goes in [seen] as soon as it is shown.
 */
object RewindPrompt {
    const val MONTH_MINIMUM_MS = 5L * 60 * 60 * 1000
    const val YEAR_MINIMUM_MS = 60L * 60 * 1000

    data class Offer(val key: String, val period: RewindPeriod, val message: String)

    /** The offer due on [today], before checking there was enough listening for it. */
    fun candidate(today: LocalDate): Offer? {
        val yearToShow = when {
            today.monthValue == 12 -> today.year
            today.monthValue == 1 && today.dayOfMonth <= 15 -> today.year - 1
            else -> null
        }
        if (yearToShow != null) {
            return Offer("year-$yearToShow", RewindPeriod.year(yearToShow), "Your $yearToShow Rewind is ready")
        }
        if (today.dayOfMonth <= 7) {
            val last = YearMonth.from(today).minusMonths(1)
            val period = RewindPeriod.month(last)
            return Offer("month-$last", period, "Your ${period.label} Rewind is ready")
        }
        return null
    }

    /** The offer to show now, or null. [listenedMs] gives the listening time in a period. */
    fun due(today: LocalDate, seen: Set<String>, enabled: Boolean, listenedMs: (RewindPeriod) -> Long): Offer? {
        if (!enabled) return null
        val offer = candidate(today) ?: return null
        if (offer.key in seen) return null
        val minimum = if (offer.period.kind == RewindPeriod.Kind.YEAR) YEAR_MINIMUM_MS else MONTH_MINIMUM_MS
        return offer.takeIf { listenedMs(it.period) >= minimum }
    }
}
