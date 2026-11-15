package com.lyrenne.desktop.sound.sponsorblock

import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.sound.HttpTransport
import com.lyrenne.desktop.sound.OkHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * SponsorBlock for what plays: fetches a song's segments when it starts, skips them from the
 * player's position tick (see [SegmentSkipper] for the rules), and tells the UI what it skipped
 * so it can offer Undo and mark the seek bar.
 *
 * Off by default, because it asks a third party about what is playing. It only ever sends four
 * hex digits of the video id's SHA-256, never the id (see [SponsorBlockApi.hashPrefix]).
 */
object SponsorBlock {
    /** What the snackbar shows after a skip. [undoable] is false once the track was ended. */
    data class SkipNotice(
        val serial: Long,
        val songId: String,
        val categories: List<String>,
        val fromMs: Long,
        val uuids: List<String>,
        val undoable: Boolean,
    )

    /** The current song's segments that will be skipped, for the seek bar. */
    data class SongSegments(val songId: String, val segments: List<Segment>)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val skipper = SegmentSkipper()
    private val serial = AtomicLong()
    internal var transport: HttpTransport = OkHttpTransport

    private val cache = object : LinkedHashMap<String, List<Segment>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Segment>>?) = size > 200
    }

    private val _notice = MutableStateFlow<SkipNotice?>(null)
    val notice: StateFlow<SkipNotice?> = _notice.asStateFlow()

    private val _segments = MutableStateFlow<SongSegments?>(null)
    val segments: StateFlow<SongSegments?> = _segments.asStateFlow()

    /** When the current song was first seen. Ticks just after a change can carry the old media's time. */
    @Volatile private var songSeenAt = 0L

    fun initialize(player: DesktopPlayer) {
        scope.launch {
            val song = player.state.map { it.currentSong?.id }.distinctUntilChanged()
            val prefs = PreferencesManager.preferences.map { it.sponsorBlockEnabled to it.sponsorBlockCategories }.distinctUntilChanged()
            combine(song, prefs) { id, p -> Triple(id, p.first, p.second) }.collect { (id, enabled, categories) ->
                if (id != skipper.loadedSongId) {
                    songSeenAt = System.currentTimeMillis()
                    _notice.value = null
                }
                if (id == null || !enabled) {
                    load(id, emptyList())
                    return@collect
                }
                val known = synchronized(cache) { cache[id] }
                if (known != null) {
                    load(id, known.filter { it.category in categories })
                    return@collect
                }
                load(id, emptyList())
                val fetched = try {
                    SponsorBlockApi.fetch(transport, id)
                } catch (e: Exception) {
                    Timber.i("SponsorBlock: no segments for $id (${e.message})")
                    return@collect
                }
                synchronized(cache) { cache[id] = fetched }
                if (fetched.isNotEmpty()) Timber.i("SponsorBlock: ${fetched.size} segments for $id: " +
                    fetched.joinToString { "${it.category} ${it.startMs / 1000.0}-${it.endMs / 1000.0}s" })
                // Still the same song, and still wanted, by the time the answer came back
                val now = PreferencesManager.preferences.value
                if (player.state.value.currentSong?.id == id && now.sponsorBlockEnabled) {
                    load(id, fetched.filter { it.category in now.sponsorBlockCategories })
                }
            }
        }
    }

    private fun load(songId: String?, segments: List<Segment>) {
        skipper.load(songId, segments)
        _segments.value = songId?.let { SongSegments(it, segments) }
    }

    /** From DesktopPlayer's position tick, every 200 ms while playing. */
    fun onPositionTick(player: DesktopPlayer, positionMs: Long) {
        val prefs = PreferencesManager.preferences.value
        if (!prefs.sponsorBlockEnabled) return
        // A Listen Together guest follows the host, whose skip arrives here as a seek
        if (!player.autoplayAllowed()) return
        val now = System.currentTimeMillis()
        if (now - songSeenAt < SETTLE_MS) return
        val state = player.state.value
        val songId = state.currentSong?.id ?: return
        when (val d = skipper.onTick(songId, positionMs, state.duration, prefs.crossfadeSec * 1000L, now)) {
            SegmentSkipper.Decision.None -> Unit
            is SegmentSkipper.Decision.SkipTo -> {
                Timber.i("SponsorBlock: skipped ${d.skipped.joinToString { it.category }} on $songId, ${positionMs / 1000.0}s to ${d.positionMs / 1000.0}s")
                player.skipSegmentTo(d.positionMs)
                _notice.value = SkipNotice(serial.incrementAndGet(), songId, d.skipped.map { it.category }.distinct(),
                    d.skipped.minOf { it.startMs }, d.skipped.map { it.uuid }, undoable = true)
            }
            is SegmentSkipper.Decision.EndTrack -> {
                Timber.i("SponsorBlock: skipped ${d.skipped.joinToString { it.category }} to the end of $songId at ${positionMs / 1000.0}s")
                player.endTrackEarly()
                _notice.value = SkipNotice(serial.incrementAndGet(), songId, d.skipped.map { it.category }.distinct(),
                    d.skipped.minOf { it.startMs }, d.skipped.map { it.uuid }, undoable = false)
            }
        }
    }

    /** From DesktopPlayer.seekTo: a seek someone chose, not one of ours. */
    fun onUserSeek(positionMs: Long) = skipper.onSeek(positionMs)

    /** Snackbar Undo: back to where the skip started, and that segment plays from now on. */
    fun undo(player: DesktopPlayer, notice: SkipNotice) {
        _notice.value = null
        if (!notice.undoable || player.state.value.currentSong?.id != notice.songId) return
        skipper.keep(notice.uuids)
        player.skipSegmentTo(notice.fromMs)
    }

    fun dismiss(notice: SkipNotice) {
        if (_notice.value?.serial == notice.serial) _notice.value = null
    }

    private const val SETTLE_MS = 700L
}
