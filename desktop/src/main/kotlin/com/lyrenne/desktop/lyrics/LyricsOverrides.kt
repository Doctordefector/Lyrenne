package com.lyrenne.desktop.lyrics

import com.lyrenne.desktop.AppPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Lyrics the user picked by hand from a search, remembered per song id.
 *
 * Stored as the full lyric text rather than a provider reference, because none of the providers
 * hand out a stable id to fetch the same result again, and a manual pick exists precisely because
 * the automatic lookup got it wrong. Lives in `data/`, like everything else the app keeps.
 */
internal object LyricsOverrides {

    @Serializable
    private data class Entry(val source: String, val text: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var cache: MutableMap<String, Entry>? = null

    /** Overridable for tests. */
    internal var file: File = File(AppPaths.dataDir, "lyrics-overrides.json")
        set(value) {
            synchronized(lock) {
                field = value
                cache = null
            }
        }

    fun get(songId: String): LyricsCandidate? = synchronized(lock) {
        load()[songId]?.let { LyricsCandidate(it.source, it.text) }
    }

    fun put(songId: String, candidate: LyricsCandidate) = synchronized(lock) {
        load()[songId] = Entry(candidate.source, candidate.text)
        save()
    }

    fun remove(songId: String) = synchronized(lock) {
        if (load().remove(songId) != null) save()
    }

    private fun load(): MutableMap<String, Entry> {
        cache?.let { return it }
        val loaded = try {
            if (file.isFile) json.decodeFromString<Map<String, Entry>>(file.readText()).toMutableMap()
            else mutableMapOf()
        } catch (e: Exception) {
            // A corrupt file costs the user their picks, not their lyrics. Start over rather
            // than failing every lookup.
            Timber.w("Could not read ${file.name}, ignoring it: ${e.message}")
            mutableMapOf()
        }
        cache = loaded
        return loaded
    }

    private fun save() {
        val map = cache ?: return
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(map as Map<String, Entry>))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Timber.w("Could not save ${file.name}: ${e.message}")
        }
    }
}
