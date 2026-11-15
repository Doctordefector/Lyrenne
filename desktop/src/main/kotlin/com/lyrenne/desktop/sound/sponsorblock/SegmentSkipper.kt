package com.lyrenne.desktop.sound.sponsorblock

import kotlin.math.abs

/**
 * Whether a segment is trusted enough to skip on a track of [durationMs].
 *
 * - Downvoted segments are left alone (the server already hides the worst).
 * - The track must be the video the segment was timed on: when SponsorBlock knows the video's
 *   length and ours differs by more than 2 s, the timings belong to another cut and would land
 *   in the music.
 * - A segment covering most of the track is far likelier a mistake than a skit, unless a
 *   SponsorBlock moderator locked it.
 * - Under a second is not worth a jump.
 */
fun Segment.trustedFor(durationMs: Long): Boolean {
    if (votes < 0 && !locked) return false
    if (lengthMs < 1_000) return false
    if (durationMs > 0) {
        if (videoDurationMs > 0 && abs(videoDurationMs - durationMs) > DURATION_TOLERANCE_MS) return false
        if (!locked && lengthMs > durationMs * MAX_SHARE) return false
    }
    return true
}

private const val DURATION_TOLERANCE_MS = 2_000L
private const val MAX_SHARE = 0.6

/**
 * Decides, tick by tick, when to jump past a segment. One instance per player; [load] it with each
 * song's segments. Pure apart from its own state, with time passed in, so the tests drive it.
 *
 * The rules:
 * - Playing into a segment skips it, to the end of every segment that overlaps or touches it.
 * - Seeking into a segment means you want to hear it: it plays. Seeking to the very start of
 *   the track is starting over, not choosing a segment, so the intro is skipped again.
 * - Undo keeps a segment for the rest of the play.
 * - A skipped segment is not skipped again, unless you go back before it. If VLC did not move,
 *   one retry after a second, then it gives up rather than fighting the player.
 * - A skip that would land in the last seconds (or inside the crossfade) ends the track instead,
 *   so the next one starts (and fades in) rather than playing a sliver of outro.
 */
class SegmentSkipper {
    sealed class Decision {
        data object None : Decision()
        data class SkipTo(val positionMs: Long, val skipped: List<Segment>) : Decision()
        data class EndTrack(val skipped: List<Segment>) : Decision()
    }

    private var songId: String? = null
    private var segments: List<Segment> = emptyList()
    private val attempts = HashMap<String, Int>()
    private val lastAttemptAt = HashMap<String, Long>()
    private val kept = HashSet<String>()
    private var seekedInto: String? = null

    val loadedSongId: String? @Synchronized get() = songId

    /** A new song: forget everything about the last one. */
    @Synchronized
    fun load(songId: String?, segments: List<Segment>) {
        if (songId != this.songId) {
            attempts.clear(); lastAttemptAt.clear(); kept.clear(); seekedInto = null
        }
        this.songId = songId
        this.segments = segments.sortedBy { it.startMs }
    }

    /** The user (or Listen Together) moved the position. */
    @Synchronized
    fun onSeek(positionMs: Long) {
        // Going back before a segment arms it again, as playing through it the first time did
        segments.filter { it.startMs >= positionMs }.forEach { attempts.remove(it.uuid); lastAttemptAt.remove(it.uuid) }
        seekedInto = if (positionMs < RESTART_MS) null else segments.firstOrNull { positionMs in it }?.uuid
    }

    /** Undo: play these for the rest of this play of the song. */
    @Synchronized
    fun keep(uuids: Collection<String>) {
        kept += uuids
    }

    @Synchronized
    fun onTick(songId: String?, positionMs: Long, durationMs: Long, crossfadeMs: Long, nowMs: Long): Decision {
        if (songId == null || songId != this.songId || positionMs <= 0) return Decision.None
        val active = segments.filter { it.trustedFor(durationMs) && it.uuid !in kept }
        // A segment seeked into stays chosen until playback leaves it
        seekedInto?.let { id -> if (active.none { it.uuid == id && positionMs in it }) seekedInto = null }

        fun armed(s: Segment): Boolean {
            if (s.uuid == seekedInto) return false
            val tries = attempts[s.uuid] ?: 0
            if (tries == 0) return true
            return tries < MAX_ATTEMPTS && nowMs - (lastAttemptAt[s.uuid] ?: 0) >= RETRY_AFTER_MS
        }

        val hit = active.firstOrNull { positionMs in it && armed(it) && it.endMs - positionMs >= MIN_JUMP_MS }
            ?: return Decision.None

        // Chain through overlapping or touching segments, so back-to-back ones are one jump
        val chain = mutableListOf(hit)
        var target = hit.endMs
        var grew = true
        while (grew) {
            grew = false
            for (s in active) {
                if (s in chain || s.uuid == seekedInto) continue
                if (s.startMs <= target + TOUCH_MS && s.endMs > target) {
                    chain += s; target = s.endMs; grew = true
                }
            }
        }
        // Overlapping segments that started earlier and are already behind us count as skipped too
        active.filter { it !in chain && positionMs in it }.forEach { chain += it }
        val endZone = maxOf(crossfadeMs, END_ZONE_MS)
        val ends = durationMs > 0 && target >= durationMs - endZone
        chain.forEach {
            // Ending the track is never retried: the next song can take seconds to resolve, and a
            // second "end" in that time would skip it too.
            attempts[it.uuid] = if (ends) MAX_ATTEMPTS else (attempts[it.uuid] ?: 0) + 1
            lastAttemptAt[it.uuid] = nowMs
        }
        return if (ends) Decision.EndTrack(chain) else Decision.SkipTo(target, chain)
    }

    companion object {
        /** A seek to before this is starting the track over. */
        const val RESTART_MS = 1_000L
        /** Not worth a jump when less than this of the segment is left. */
        const val MIN_JUMP_MS = 1_000L
        /** Segments this close count as touching. */
        const val TOUCH_MS = 500L
        /** Landing this close to the end (or inside the crossfade) ends the track instead. */
        const val END_ZONE_MS = 2_000L
        const val MAX_ATTEMPTS = 2
        const val RETRY_AFTER_MS = 1_000L
    }
}
