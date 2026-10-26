package com.lyrenne.desktop.lyrics

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * A small JSON map from song id to [V], read once and written whole on every change.
 *
 * Shared by the lyrics picked by hand and the per-song timing offsets. Both are tiny, both are
 * written only when the user does something, and both live in `data/` so backups carry them.
 */
internal class PerSongFile<V>(initialFile: File, valueSerializer: KSerializer<V>) {

    private val serializer = MapSerializer(String.serializer(), valueSerializer)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var cache: MutableMap<String, V>? = null

    /** Overridable for tests. Re-pointing it drops the cache. */
    var file: File = initialFile
        set(value) {
            synchronized(lock) {
                field = value
                cache = null
            }
        }

    fun get(songId: String): V? = synchronized(lock) { load()[songId] }

    fun put(songId: String, value: V) = synchronized(lock) {
        load()[songId] = value
        save()
    }

    fun remove(songId: String) = synchronized(lock) {
        if (load().remove(songId) != null) save()
    }

    private fun load(): MutableMap<String, V> {
        cache?.let { return it }
        val loaded = try {
            if (file.isFile) json.decodeFromString(serializer, file.readText()).toMutableMap()
            else mutableMapOf()
        } catch (e: Exception) {
            // A corrupt file costs the user these settings, not their lyrics. Start over rather
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
            tmp.writeText(json.encodeToString(serializer, map))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Timber.w("Could not save ${file.name}: ${e.message}")
        }
    }
}
