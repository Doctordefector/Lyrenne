package com.lyrenne.desktop.integration

import com.lyrenne.desktop.AppPaths
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.PlaybackState
import com.lyrenne.desktop.playback.RepeatMode
import com.lyrenne.desktop.settings.PreferencesManager
import dev.toastbits.mediasession.MediaSession
import dev.toastbits.mediasession.MediaSessionLoopMode
import dev.toastbits.mediasession.MediaSessionMetadata
import dev.toastbits.mediasession.MediaSessionPlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Publishes Lyrenne playback through Windows System Media Transport Controls.
 *
 * Windows and tools such as Taskbar Fluent Media Player consume this session for Now Playing
 * metadata, artwork, playback state, timeline data and transport commands. Native integration is
 * optional: any load or runtime failure is logged and leaves the existing AWT media-key fallback
 * active.
 */
object WindowsMediaSession {
    private const val TIMELINE_UPDATE_MS = 5_000L
    private const val MAX_CACHED_ARTWORK = 24
    private const val MAX_ARTWORK_BYTES = 10L * 1024 * 1024

    private val onWindows =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private val client = OkHttpClient.Builder().followRedirects(true).build()
    private val lock = Any()

    private var scope: CoroutineScope? = null
    private var observerJob: Job? = null
    private var preferencesJob: Job? = null
    private var artworkJob: Job? = null
    @Volatile
    private var session: MediaSession? = null
    @Volatile
    private var currentSongId: String? = null
    @Volatile
    private var currentArtwork: File? = null

    @Volatile
    internal var lastFailure: String? = null
        private set

    internal val active: Boolean get() = session?.enabled == true

    /**
     * Start publishing [player] state. Returns true when SMTC replaced the AWT media-key path.
     */
    fun initialize(player: DesktopPlayer): Boolean {
        if (!onWindows || observerJob != null) return session != null
        lastFailure = null

        return try {
            val integrationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val mediaSession = MediaSession.create { player.state.value.position }
                ?: run {
                    integrationScope.cancel()
                    return false
                }

            scope = integrationScope
            session = mediaSession

            mediaSession.onPlay = player::play
            mediaSession.onPause = player::pause
            mediaSession.onStop = player::pause
            mediaSession.onNext = { integrationScope.launch { player.playNext() } }
            mediaSession.onPrevious = { integrationScope.launch { player.playPrevious() } }
            mediaSession.onSetPosition = { position ->
                player.seekTo(position)
                safely { it.onPositionChanged() }
            }
            mediaSession.onSetShuffle = { enabled ->
                if (player.state.value.shuffleEnabled != enabled) player.toggleShuffle()
            }
            mediaSession.onSetLoop = { mode ->
                player.setRepeatMode(mode.toRepeatMode())
            }
            mediaSession.onSetRate = player::setPlaybackSpeed

            observerJob = integrationScope.launch { observe(player) }
            preferencesJob = integrationScope.launch {
                PreferencesManager.preferences
                    .map { it.playbackSpeed }
                    .distinctUntilChanged()
                    .collect { speed -> safely { it.setRate(speed) } }
            }
            Timber.i("Windows media session initialized")
            true
        } catch (e: Throwable) {
            lastFailure = e.message ?: e::class.simpleName
            Timber.w("Windows media session unavailable: ${e.message}")
            release()
            false
        }
    }

    private suspend fun observe(player: DesktopPlayer) {
        var lastState: PlaybackState? = null
        var lastTimelineAt = 0L

        player.state.collect { state ->
            val previous = lastState
            val song = state.currentSong

            if (song == null) {
                if (previous?.currentSong != null) {
                    safely {
                        it.setPlaybackStatus(MediaSessionPlaybackStatus.STOPPED)
                        it.setEnabled(false)
                    }
                }
                lastState = state
                return@collect
            }

            safely { if (!it.enabled) it.setEnabled(true) }

            val songChanged = song.id != previous?.currentSong?.id
            val durationChanged = state.duration != previous?.duration
            val playbackChanged = state.isPlaying != previous?.isPlaying
            val shuffleChanged = state.shuffleEnabled != previous?.shuffleEnabled
            val repeatChanged = state.repeatMode != previous?.repeatMode

            if (songChanged) {
                currentSongId = song.id
                artworkJob?.cancel()
                currentArtwork = fallbackArtwork()
                publishMetadata(state)
                artworkJob = scope?.launch {
                    val artwork = song.thumbnailUrl?.let { cacheArtwork(it) } ?: return@launch
                    if (currentSongId == song.id) {
                        currentArtwork = artwork
                        publishMetadata(player.state.value)
                    }
                }
            } else if (durationChanged) {
                publishMetadata(state)
            }

            if (playbackChanged || songChanged) {
                safely {
                    it.setPlaybackStatus(
                        if (state.isPlaying) MediaSessionPlaybackStatus.PLAYING
                        else MediaSessionPlaybackStatus.PAUSED
                    )
                }
            }
            if (shuffleChanged || songChanged) safely { it.setShuffle(state.shuffleEnabled) }
            if (repeatChanged || songChanged) safely { it.setLoopMode(state.repeatMode.toMediaLoopMode()) }

            val now = System.currentTimeMillis()
            val seeked = previous != null &&
                kotlin.math.abs(state.position - previous.position) > TIMELINE_UPDATE_MS * 2
            if (songChanged || durationChanged || playbackChanged || seeked || now - lastTimelineAt >= TIMELINE_UPDATE_MS) {
                safely { it.onPositionChanged() }
                lastTimelineAt = now
            }

            lastState = state
        }
    }

    private fun publishMetadata(state: PlaybackState) {
        val song = state.currentSong ?: return
        val artwork = currentArtwork ?: fallbackArtwork() ?: return
        val duration = state.duration.takeIf { it > 0 }

        safely {
            it.setMetadata(
                MediaSessionMetadata(
                    track_id = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    album_artists = listOf(song.artist),
                    length_ms = duration,
                    art_url = artwork.absolutePath
                )
            )
        }
    }

    private fun fallbackArtwork(): File? = try {
        val directory = artworkDirectory()
        val target = File(directory, "lyrenne.png")
        if (!target.exists()) {
            WindowsMediaSession::class.java.getResourceAsStream("/icon.png")?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        target.takeIf { it.isFile && it.length() > 0 }
    } catch (e: Exception) {
        Timber.w("Could not prepare media-session fallback artwork: ${e.message}")
        null
    }

    private suspend fun cacheArtwork(url: String): File? = withContext(Dispatchers.IO) {
        var partial: File? = null
        try {
            val key = MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val directory = artworkDirectory()
            directory.listFiles { file -> file.name.startsWith(key) && file.extension != "part" }
                ?.firstOrNull { it.isFile && it.length() > 0 }
                ?.let { return@withContext it }

            val response = client.newCall(
                Request.Builder().url(url).header("User-Agent", "Lyrenne").build()
            ).execute()
            response.use {
                if (!it.isSuccessful) {
                    Timber.w("Media-session artwork request failed: HTTP ${it.code}")
                    return@withContext null
                }
                val body = it.body
                val contentLength = body.contentLength()
                if (contentLength > MAX_ARTWORK_BYTES) {
                    Timber.w("Media-session artwork is too large: $contentLength bytes")
                    return@withContext null
                }
                val extension = when (body.contentType()?.subtype?.lowercase()) {
                    "png" -> "png"
                    "webp" -> "webp"
                    "jpeg", "jpg" -> "jpg"
                    else -> {
                        Timber.w("Unsupported media-session artwork type: ${body.contentType()}")
                        return@withContext null
                    }
                }
                val target = File(directory, "$key.$extension")
                val partialFile = File(directory, "$key.part").also { it.delete() }
                partial = partialFile
                partialFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_ARTWORK_BYTES) {
                                throw IllegalStateException("Media-session artwork exceeds 10 MB")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (partialFile.length() == 0L) {
                    partialFile.delete()
                    return@withContext null
                }
                partialFile.copyTo(target, overwrite = true)
                partialFile.delete()
                pruneArtworkCache(directory, keep = target)
                target
            }
        } catch (e: Exception) {
            partial?.delete()
            Timber.w("Could not cache media-session artwork: ${e.message}")
            null
        }
    }

    private fun artworkDirectory(): File =
        File(AppPaths.cacheDir, "media-session").also { it.mkdirs() }

    private fun pruneArtworkCache(directory: File, keep: File) {
        directory.listFiles { file ->
            file.isFile && file != keep && file.name != "lyrenne.png" && file.extension != "part"
        }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED_ARTWORK - 1)
            ?.forEach { it.delete() }
    }

    private inline fun safely(action: (MediaSession) -> Unit) {
        val current = session ?: return
        try {
            synchronized(lock) { action(current) }
        } catch (e: Throwable) {
            lastFailure = e.message ?: e::class.simpleName
            Timber.w("Windows media-session update failed: ${e.message}")
        }
    }

    fun release() {
        artworkJob?.cancel()
        observerJob?.cancel()
        preferencesJob?.cancel()
        safely {
            it.setEnabled(false)
            it.onPlay = null
            it.onPause = null
            it.onStop = null
            it.onNext = null
            it.onPrevious = null
            it.onSetPosition = null
            it.onSetShuffle = null
            it.onSetLoop = null
            it.onSetRate = null
        }
        scope?.cancel()
        artworkJob = null
        observerJob = null
        preferencesJob = null
        scope = null
        session = null
        currentSongId = null
        currentArtwork = null
    }
}

private fun RepeatMode.toMediaLoopMode(): MediaSessionLoopMode = when (this) {
    RepeatMode.OFF -> MediaSessionLoopMode.NONE
    RepeatMode.ONE -> MediaSessionLoopMode.ONE
    RepeatMode.ALL -> MediaSessionLoopMode.ALL
}

private fun MediaSessionLoopMode.toRepeatMode(): RepeatMode = when (this) {
    MediaSessionLoopMode.NONE -> RepeatMode.OFF
    MediaSessionLoopMode.ONE -> RepeatMode.ONE
    MediaSessionLoopMode.ALL -> RepeatMode.ALL
}
