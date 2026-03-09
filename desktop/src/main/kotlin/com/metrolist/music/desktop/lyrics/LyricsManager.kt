package com.metrolist.music.desktop.lyrics

import com.metrolist.lrclib.LrcLib
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
            try {
                val result = LrcLib.getLyrics(
                    title = title,
                    artist = artist,
                    duration = durationSec,
                    album = album
                )
                result.onSuccess { lyricsText ->
                    val sentences = LrcLib.Lyrics(lyricsText).sentences
                    _state.value = LyricsState(
                        songId = songId,
                        lyrics = lyricsText,
                        syncedSentences = sentences,
                        isLoading = false
                    )
                }.onFailure {
                    _state.value = LyricsState(
                        songId = songId,
                        error = "No lyrics found",
                        isLoading = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
