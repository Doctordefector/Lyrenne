package com.lyrenne.desktop.lyrics

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.kugou.KuGou
import com.metrolist.lrclib.LrcLib
import com.metrolist.music.betterlyrics.BetterLyrics
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
    val error: String? = null,
    val source: String? = null
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

            val effectiveDuration = if (durationSec <= 0) -1 else durationSec

            // Provider chain: BetterLyrics → LrcLib → KuGou → YouTube Lyrics → YouTube Transcript
            val result = tryBetterLyrics(title, artist, effectiveDuration, album)
                ?: tryLrcLib(title, artist, effectiveDuration, album)
                ?: tryKuGou(title, artist, effectiveDuration, album)
                ?: tryYouTubeLyrics(songId)
                ?: tryYouTubeTranscript(songId)

            if (result != null) {
                val sentences = LrcLib.Lyrics(result.first).sentences
                Timber.i("Lyrics found via ${result.second}: ${result.first.length} chars, synced=${sentences != null} (${sentences?.size ?: 0} lines)")
                _state.value = LyricsState(
                    songId = songId,
                    lyrics = result.first,
                    syncedSentences = sentences,
                    isLoading = false,
                    source = result.second
                )
            } else {
                Timber.w("No lyrics found for \"$title\" by \"$artist\" from any provider")
                _state.value = LyricsState(
                    songId = songId,
                    error = "No lyrics found",
                    isLoading = false
                )
            }
        }
    }

    /** BetterLyrics — TTML-based synced lyrics from lyrics-api.boidu.dev */
    private suspend fun tryBetterLyrics(title: String, artist: String, duration: Int, album: String?): Pair<String, String>? {
        return try {
            val result = BetterLyrics.getLyrics(
                title = title,
                artist = artist,
                duration = duration,
                album = album
            )
            result.getOrNull()?.let { it to "BetterLyrics" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("BetterLyrics failed: ${e.message}")
            null
        }
    }

    /** LrcLib — crowdsourced LRC lyrics from lrclib.net */
    private suspend fun tryLrcLib(title: String, artist: String, duration: Int, album: String?): Pair<String, String>? {
        return try {
            val result = LrcLib.getLyrics(
                title = title,
                artist = artist,
                duration = duration,
                album = album
            )
            result.getOrNull()?.let { it to "LrcLib" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("LrcLib failed: ${e.message}")
            null
        }
    }

    /** KuGou — synced LRC lyrics, strong coverage for CJK/Asian catalogs */
    private suspend fun tryKuGou(title: String, artist: String, duration: Int, album: String?): Pair<String, String>? {
        return try {
            val result = KuGou.getLyrics(
                title = title,
                artist = artist,
                duration = duration,
                album = album
            )
            result.getOrNull()?.let { it to "KuGou" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("KuGou failed: ${e.message}")
            null
        }
    }

    /** YouTube Music lyrics — plain text from YouTube's own lyrics endpoint */
    private suspend fun tryYouTubeLyrics(songId: String): Pair<String, String>? {
        return try {
            val nextResult = YouTube.next(WatchEndpoint(videoId = songId)).getOrNull()
            val endpoint = nextResult?.lyricsEndpoint ?: return null
            val lyrics = YouTube.lyrics(endpoint).getOrNull()
            if (!lyrics.isNullOrBlank()) lyrics to "YouTube" else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("YouTube lyrics failed: ${e.message}")
            null
        }
    }

    /** YouTube transcript — synced captions/subtitles as LRC fallback */
    private suspend fun tryYouTubeTranscript(songId: String): Pair<String, String>? {
        return try {
            val transcript = YouTube.transcript(songId).getOrNull()
            if (!transcript.isNullOrBlank()) transcript to "YouTube Transcript" else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("YouTube transcript failed: ${e.message}")
            null
        }
    }

    fun clear() {
        fetchJob?.cancel()
        _state.value = LyricsState()
    }
}
