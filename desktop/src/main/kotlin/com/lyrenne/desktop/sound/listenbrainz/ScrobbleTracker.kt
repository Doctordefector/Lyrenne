package com.lyrenne.desktop.sound.listenbrainz

import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.playback.knownDurationMs

/** Tracks shorter than this are never a listen (the Last.fm and ListenBrainz convention). */
const val MIN_LISTEN_TRACK_MS = 30_000L

/** Four minutes of a long track is a listen however long the track is. */
const val MAX_LISTEN_THRESHOLD_MS = 240_000L

/**
 * How much of a track has to be heard before it counts: half of it or four minutes, whichever
 * comes first. Null when it never counts. An unknown length counts after four minutes.
 */
fun listenThresholdMs(durationMs: Long): Long? = when {
    durationMs <= 0 -> MAX_LISTEN_THRESHOLD_MS
    durationMs < MIN_LISTEN_TRACK_MS -> null
    else -> minOf(durationMs / 2, MAX_LISTEN_THRESHOLD_MS)
}

/**
 * Turns the player's state into "now playing" and "listen" events, counting time actually heard.
 *
 * Last.fm's path waits half the track on a timer from the start, so a pause cancels the timer and
 * the play is never counted, and skipping ahead counts time nobody heard. This counts position
 * that advanced by playing: steps of a few seconds at most, so a seek forward adds nothing and a
 * pause simply stops the count until play resumes.
 *
 * Pure (no clock or I/O of its own), so the tests can feed it ticks.
 */
class ScrobbleTracker {
    sealed class Event {
        data class NowPlaying(val song: SongInfo, val durationMs: Long) : Event()
        data class Listen(val song: SongInfo, val listenedAtSec: Long, val durationMs: Long) : Event()
    }

    private var song: SongInfo? = null
    private var heardMs = 0L
    private var lastPosition = -1L
    private var startedAtMs = 0L
    private var announced = false
    private var counted = false

    fun onState(song: SongInfo?, isPlaying: Boolean, positionMs: Long, durationMs: Long, nowMs: Long): List<Event> {
        if (song == null) {
            this.song = null
            return emptyList()
        }
        if (song.id != this.song?.id) start(song, positionMs)
        // Repeat One, or starting the song over after it already counted: a second listen
        if (counted && positionMs < RESTART_WINDOW_MS && lastPosition - positionMs > RESTART_JUMP_MS) start(song, positionMs)

        val events = mutableListOf<Event>()
        val duration = if (durationMs > 0) durationMs else song.knownDurationMs()
        if (isPlaying) {
            if (!announced) {
                announced = true
                startedAtMs = nowMs - positionMs.coerceAtLeast(0)
                events += Event.NowPlaying(song, duration)
            }
            val step = positionMs - lastPosition
            if (lastPosition >= 0 && step in 1..MAX_TICK_STEP_MS) heardMs += step
        }
        lastPosition = positionMs

        val threshold = listenThresholdMs(duration)
        if (!counted && announced && threshold != null && heardMs >= threshold) {
            counted = true
            events += Event.Listen(song, startedAtMs / 1000, duration)
        }
        return events
    }

    private fun start(song: SongInfo, positionMs: Long) {
        this.song = song
        heardMs = 0
        lastPosition = positionMs
        announced = false
        counted = false
    }

    companion object {
        /** Position ticks every 200 ms; anything past this between two looks is a seek, not play. */
        const val MAX_TICK_STEP_MS = 3_000L
        private const val RESTART_WINDOW_MS = 3_000L
        private const val RESTART_JUMP_MS = 10_000L
    }
}
