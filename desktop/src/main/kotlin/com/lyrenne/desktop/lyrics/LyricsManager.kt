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
    /** Parsed timed lines, or null when the lyrics carry no timestamps and show as plain text. */
    val lines: List<LyricLine>? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val source: String? = null,
    /** True when the user picked these lyrics by hand from a search. */
    val isManual: Boolean = false,
)

/** One result offered by a manual lyrics search. */
data class LyricsCandidate(val source: String, val text: String) {
    val lines: List<LyricLine>? by lazy { LyricsParser.parse(text) }
    val isSynced: Boolean get() = lines != null
    val hasWordTiming: Boolean get() = lines?.any { it.words != null } == true

    /** The first couple of sung lines, for telling results apart in the picker. */
    val preview: String by lazy {
        (lines?.map { it.text } ?: text.lines())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString(" / ")
    }
}

object LyricsManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var fetchJob: Job? = null

    private val _state = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    /** Results per provider in a manual search. KuGou downloads each one, so this bounds it. */
    private const val SEARCH_RESULTS_PER_PROVIDER = 5
    private const val SEARCH_TIMEOUT_MS = 20_000L

    /**
     * Fetches lyrics for a song through the provider chain, unless the user already chose lyrics
     * for it by hand, in which case those are used without touching the network.
     */
    fun fetchLyrics(songId: String, title: String, artist: String, durationSec: Int, album: String? = null) {
        // Don't re-fetch if we already have lyrics for this song
        if (_state.value.songId == songId && _state.value.lyrics != null) return

        fetchJob?.cancel()
        fetchJob = scope.launch {
            LyricsOverrides.get(songId)?.let { saved ->
                Timber.i("Using lyrics chosen by hand for $songId (${saved.source})")
                publish(songId, saved, isManual = true)
                return@launch
            }

            _state.value = LyricsState(songId = songId, isLoading = true)
            Timber.i("Fetching lyrics: title=\"$title\", artist=\"$artist\", duration=${durationSec}s, album=$album")

            val effectiveDuration = if (durationSec <= 0) -1 else durationSec

            // Provider chain: BetterLyrics → LrcLib → KuGou → YouTube Lyrics → YouTube Transcript
            val result = tryBetterLyrics(title, artist, effectiveDuration, album)
                ?: tryLrcLib(title, artist, effectiveDuration, album)
                ?: tryKuGou(title, artist, effectiveDuration, album)
                ?: tryYouTubeLyrics(songId)
                ?: tryYouTubeTranscript(songId)

            // A lookup cancelled by a track change must not write over the next one's state.
            ensureActive()
            if (result != null) {
                publish(songId, LyricsCandidate(result.second, result.first), isManual = false)
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

    private fun publish(songId: String, candidate: LyricsCandidate, isManual: Boolean) {
        val lines = candidate.lines
        Timber.i(
            "Lyrics found via ${candidate.source}: ${candidate.text.length} chars, " +
                "synced=${lines != null} (${lines?.size ?: 0} lines, words=${candidate.hasWordTiming})"
        )
        _state.value = LyricsState(
            songId = songId,
            lyrics = candidate.text,
            lines = lines,
            isLoading = false,
            source = candidate.source,
            isManual = isManual,
        )
    }

    /**
     * Asks every provider at once for [title] and [artist] and returns everything they have,
     * synced results first. Duration is deliberately not used: someone searching by hand is
     * usually doing it because the automatic, duration-matched lookup already failed.
     */
    suspend fun search(songId: String?, title: String, artist: String): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            // Each provider gets its own deadline. One shared deadline round the lot meant a slow
            // KuGou (it downloads every match, one after another) threw away the answers the
            // others had already given, and the dialog said nothing was found.
            suspend fun single(fetch: suspend () -> Pair<String, String>?): List<LyricsCandidate> =
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) { fetch() }
                    ?.let { listOf(LyricsCandidate(it.second, it.first)) }
                    .orEmpty()

            val found = listOf(
                async { single { tryBetterLyrics(title, artist, -1, null) } },
                async { collectUpTo("LrcLib") { LrcLib.getAllLyrics(title, artist, -1, null, it) } },
                async { collectUpTo("KuGou") { KuGou.getAllPossibleLyricsOptions(title, artist, -1, null, it) } },
                async { songId?.let { id -> single { tryYouTubeLyrics(id) } }.orEmpty() },
            ).awaitAll().flatten()

            found
                .filter { it.text.isNotBlank() }
                .distinctBy { it.text.trim() }
                .sortedWith(compareByDescending<LyricsCandidate> { it.hasWordTiming }.thenByDescending { it.isSynced })
        }

    /**
     * Rethrows cancellation that a provider swallowed.
     *
     * The vendored providers wrap their work in `runCatching`, which catches the
     * CancellationException a track change delivers and hands back an ordinary failure. The chain
     * then carried on to the next provider as if nothing had happened, every one failed instantly,
     * and the cancelled lookup finished by publishing "No lyrics found" for a song that was no
     * longer playing. That is what the run of "No lyrics found" lines in the issue #9 log was.
     */
    private suspend fun ensureStillWanted() = currentCoroutineContext().ensureActive()

    /** Signals [collectUpTo] that enough results have arrived. Not an error. */
    private class Enough : RuntimeException("enough results")

    /**
     * Runs a callback-style provider search and keeps its first few results. The providers offer
     * no limit of their own, and KuGou downloads a full lyric file per match.
     */
    private suspend fun collectUpTo(
        source: String,
        search: suspend (callback: (String) -> Unit) -> Unit,
    ): List<LyricsCandidate> {
        val results = mutableListOf<LyricsCandidate>()
        // Whatever arrived before the deadline is kept.
        withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            try {
                search { text ->
                    results += LyricsCandidate(source, text)
                    if (results.size >= SEARCH_RESULTS_PER_PROVIDER) throw Enough()
                }
            } catch (_: Enough) {
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("$source search failed: ${e.message}")
            }
        }
        return results.toList()
    }

    /**
     * Remembers [candidate] for [songId], and shows it if that song's lyrics are the ones on
     * screen. The track can move on while the search dialog is open; the choice still belongs to
     * the song it was made for, and must not be shown against the one playing now.
     */
    fun choose(songId: String, candidate: LyricsCandidate) {
        LyricsOverrides.put(songId, candidate)
        if (_state.value.songId != songId) return
        fetchJob?.cancel()
        publish(songId, candidate, isManual = true)
    }

    /** Forgets a hand-picked choice and runs the normal lookup again. */
    fun resetToAutomatic(songId: String, title: String, artist: String, durationSec: Int, album: String?) {
        LyricsOverrides.remove(songId)
        _state.value = LyricsState()
        fetchLyrics(songId, title, artist, durationSec, album)
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
            ensureStillWanted()
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
            ensureStillWanted()
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
            ensureStillWanted()
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
            ensureStillWanted()
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
            ensureStillWanted()
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
