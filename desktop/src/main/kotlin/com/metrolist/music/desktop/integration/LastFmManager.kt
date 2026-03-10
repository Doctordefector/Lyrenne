package com.metrolist.music.desktop.integration

import com.metrolist.lastfm.LastFM
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.settings.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber

/**
 * Manages Last.fm scrobbling integration.
 * - Sends "now playing" updates when a song starts
 * - Scrobbles after 50% or 4 minutes of playback (whichever is first)
 * - Requires Last.fm API key, secret, and session key
 */
object LastFmManager {
    private var observeJob: Job? = null
    private var scrobbleJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastScrobbledSongId: String? = null

    fun initialize(player: DesktopPlayer) {
        val prefs = PreferencesManager.preferences.value
        if (!prefs.lastFmEnabled) return

        val apiKey = prefs.lastFmApiKey
        val secret = prefs.lastFmSecret
        val sessionKey = prefs.lastFmSessionKey

        if (apiKey.isNullOrBlank() || secret.isNullOrBlank() || sessionKey.isNullOrBlank()) {
            Timber.d("Last.fm not configured — skipping initialization")
            return
        }

        LastFM.initialize(apiKey, secret)
        LastFM.sessionKey = sessionKey

        Timber.i("Last.fm initialized")

        observeJob = scope.launch {
            var previousSongId: String? = null

            player.state.collectLatest { state ->
                val song = state.currentSong
                if (song != null && song.id != previousSongId && state.isPlaying) {
                    previousSongId = song.id
                    lastScrobbledSongId = null

                    // Send now playing
                    updateNowPlaying(song)

                    // Schedule scrobble
                    scrobbleJob?.cancel()
                    scrobbleJob = launch {
                        scheduleScrobble(song, state.duration)
                    }
                } else if (song == null || !state.isPlaying) {
                    scrobbleJob?.cancel()
                }
            }
        }
    }

    private suspend fun updateNowPlaying(song: SongInfo) {
        try {
            LastFM.updateNowPlaying(
                artist = song.artist,
                track = song.title,
                album = song.album,
                duration = if (song.duration > 0) song.duration else null
            ).onFailure { e ->
                Timber.w("Last.fm now playing failed: ${e.message}")
            }
        } catch (e: Exception) {
            Timber.w("Last.fm now playing error: ${e.message}")
        }
    }

    private suspend fun scheduleScrobble(song: SongInfo, durationMs: Long) {
        // Scrobble rules (Last.fm spec):
        // - Track must be longer than 30 seconds
        // - Scrobble after 50% of track or 4 minutes, whichever comes first
        val durationSec = if (song.duration > 0) song.duration.toLong()
        else if (durationMs > 0) durationMs / 1000
        else return // Unknown duration, skip

        if (durationSec < LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION) return

        val scrobbleDelayMs = minOf(
            (durationSec * 1000 * LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT).toLong(),
            LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS * 1000L
        )

        delay(scrobbleDelayMs)

        // Only scrobble once per song play
        if (lastScrobbledSongId == song.id) return
        lastScrobbledSongId = song.id

        try {
            LastFM.scrobble(
                artist = song.artist,
                track = song.title,
                timestamp = System.currentTimeMillis() / 1000,
                album = song.album,
                duration = if (song.duration > 0) song.duration else null
            ).onSuccess {
                Timber.d("Scrobbled: ${song.artist} - ${song.title}")
            }.onFailure { e ->
                Timber.w("Scrobble failed: ${e.message}")
            }
        } catch (e: Exception) {
            Timber.w("Scrobble error: ${e.message}")
        }
    }

    /**
     * Authenticate with Last.fm using username/password (mobile session).
     * Returns the session key on success.
     */
    suspend fun login(apiKey: String, secret: String, username: String, password: String): Result<String> {
        LastFM.initialize(apiKey, secret)
        return try {
            val auth = LastFM.getMobileSession(username, password).getOrThrow()
            val key = auth.session.key
            LastFM.sessionKey = key
            Result.success(key)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun release() {
        observeJob?.cancel()
        scrobbleJob?.cancel()
    }
}
