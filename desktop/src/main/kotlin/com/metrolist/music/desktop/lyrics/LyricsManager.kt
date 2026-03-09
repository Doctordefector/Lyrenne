package com.metrolist.music.desktop.lyrics

import com.metrolist.lrclib.LrcLib
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

data class LyricsState(
    val songId: String? = null,
    val lyrics: String? = null,
    val syncedSentences: Map<Long, String>? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

object LyricsManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var fetchJob: Job? = null

    private val _state = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    fun fetchLyrics(songId: String, title: String, artist: String, durationSec: Int, album: String? = null) {
        // Don't re-fetch if we already have lyrics for this song
        if (_state.value.songId == songId && _state.value.lyrics != null) return

        fetchJob?.cancel()
        fetchJob = scope.launch {
            _state.value = LyricsState(songId = songId, isLoading = true)
            Timber.i("Fetching lyrics: title=\"$title\", artist=\"$artist\", duration=${durationSec}s, album=$album")
            try {
                // If duration is 0 or invalid, use -1 to trigger title/artist matching instead
                val effectiveDuration = if (durationSec <= 0) -1 else durationSec

                val result = LrcLib.getLyrics(
                    title = title,
                    artist = artist,
                    duration = effectiveDuration,
                    album = album
                )
                result.onSuccess { lyricsText ->
                    val sentences = LrcLib.Lyrics(lyricsText).sentences
                    Timber.i("Lyrics found: ${lyricsText.length} chars, synced=${sentences != null} (${sentences?.size ?: 0} lines)")
                    _state.value = LyricsState(
                        songId = songId,
                        lyrics = lyricsText,
                        syncedSentences = sentences,
                        isLoading = false
                    )
                }.onFailure { e ->
                    Timber.w("No lyrics found for \"$title\" by \"$artist\": ${e.message}")
                    _state.value = LyricsState(
                        songId = songId,
                        error = "No lyrics found",
                        isLoading = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("Lyrics fetch failed: ${e.message}")
                _state.value = LyricsState(
                    songId = songId,
                    error = e.message ?: "Failed to fetch lyrics",
                    isLoading = false
                )
            }
        }
    }

    fun clear() {
        fetchJob?.cancel()
        _state.value = LyricsState()
    }
}
