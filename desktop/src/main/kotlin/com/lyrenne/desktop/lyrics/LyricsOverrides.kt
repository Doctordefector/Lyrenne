package com.lyrenne.desktop.lyrics

import com.lyrenne.desktop.AppPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Lyrics the user picked by hand from a search, or wrote themselves, remembered per song id.
 *
 * Stored as the full lyric text rather than a provider reference, because none of the providers
 * hand out a stable id to fetch the same result again, and a manual pick exists precisely because
 * the automatic lookup got it wrong. Lives in `data/`, like everything else the app keeps.
 */
internal object LyricsOverrides {

    @Serializable
    internal data class Entry(val source: String, val text: String)

    private val store = PerSongFile(File(AppPaths.dataDir, "lyrics-overrides.json"), Entry.serializer())

    /** Overridable for tests. */
    internal var file: File
        get() = store.file
        set(value) { store.file = value }

    fun get(songId: String): LyricsCandidate? = store.get(songId)?.let { LyricsCandidate(it.source, it.text) }

    fun put(songId: String, candidate: LyricsCandidate) = store.put(songId, Entry(candidate.source, candidate.text))

    fun remove(songId: String) = store.remove(songId)
}

/**
 * How far each song's lyrics are moved in time, in milliseconds, set by hand from the lyrics panel.
 *
 * Positive shows the lyrics sooner, the same sign as an LRC `[offset:]` tag and upstream Android's
 * per-song offset. Kept apart from [LyricsOverrides] because it applies to whichever lyrics are
 * showing: going back to automatic lyrics must not lose it, and neither must picking new ones.
 */
internal object LyricsOffsets {

    private val store = PerSongFile(File(AppPaths.dataDir, "lyrics-offsets.json"), Long.serializer())

    /** Overridable for tests. */
    internal var file: File
        get() = store.file
        set(value) { store.file = value }

    fun get(songId: String): Long = store.get(songId) ?: 0L

    /** Zero is stored as no entry, so a song put back on time leaves nothing behind. */
    fun set(songId: String, offsetMs: Long) {
        if (offsetMs == 0L) store.remove(songId) else store.put(songId, offsetMs)
    }
}
