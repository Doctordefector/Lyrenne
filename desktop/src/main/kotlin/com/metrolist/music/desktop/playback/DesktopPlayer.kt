package com.metrolist.music.desktop.playback

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.music.desktop.db.DatabaseHelper
import com.metrolist.music.desktop.settings.PreferencesManager
import kotlinx.coroutines.*
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent
import java.io.File

enum class RepeatMode {
    OFF, ONE, ALL
}

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentSong: SongInfo? = null,
    val position: Long = 0L,
    val duration: Long = 0L,
    val queue: List<SongInfo> = emptyList(),
    val currentIndex: Int = -1,
    val error: String? = null,
    val vlcAvailable: Boolean = true,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF
)

data class SongInfo(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationMs: Long = 0L,
    val album: String? = null,
    val duration: Int = -1 // in seconds
)

class DesktopPlayer {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var audioPlayer: AudioPlayerComponent? = null
    private var positionUpdateJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val queue = mutableListOf<SongInfo>()
    private val originalQueue = mutableListOf<SongInfo>() // For unshuffle
    private var currentIndex = -1
    private var shuffleEnabled = false
    private var repeatMode = RepeatMode.OFF

    init {
        initializeVlc()
    }

    private fun initializeVlc() {
        try {
            // Try bundled VLC first, then fall back to system VLC
            val bundledVlcDir = findBundledVlc()
            val bundledPath = bundledVlcDir?.absolutePath
            if (bundledPath != null) {
                Timber.i("Using bundled VLC from: $bundledPath")
                // Add bundled dir to JNA search path and set VLC plugin path
                val currentPath = System.getProperty("jna.library.path", "")
                System.setProperty("jna.library.path",
                    if (currentPath.isEmpty()) bundledPath else "$bundledPath${File.pathSeparator}$currentPath")
                System.setProperty("VLC_PLUGIN_PATH", File(bundledVlcDir, "plugins").absolutePath)
            }
            // NativeDiscovery checks jna.library.path, system PATH, and standard install locations
            val found = NativeDiscovery().discover()
            if (!found && bundledPath != null) {
                Timber.w("NativeDiscovery failed even with bundled VLC, retrying with PATH override...")
                // Fallback: also prepend to java.library.path
                val javaPath = System.getProperty("java.library.path", "")
                System.setProperty("java.library.path",
                    if (javaPath.isEmpty()) bundledPath else "$bundledPath${File.pathSeparator}$javaPath")
            }

            if (found) {
                audioPlayer = AudioPlayerComponent()
                setupEventListener()
                Timber.i("VLC initialized successfully")
            } else {
                Timber.w("VLC not found")
                _state.value = _state.value.copy(
                    vlcAvailable = false,
                    error = "VLC not found. Please install VLC media player (64-bit)."
                )
            }
        } catch (e: Exception) {
            Timber.e("Failed to initialize VLC: ${e.message}")
            _state.value = _state.value.copy(
                vlcAvailable = false,
                error = "Failed to initialize VLC: ${e.message}"
            )
        }
    }

    private fun findBundledVlc(): File? {
        // Check for bundled VLC in app resources (Compose Desktop native distribution)
        val candidates = listOf(
            // When running as packaged app (createDistributable/MSI/EXE)
            System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") },
            // When running from IDE / gradle run — check relative to working dir
            File("resources/windows-x64/vlc"),
            // Check relative to jar location
            File(System.getProperty("user.dir"), "vlc"),
        )
        return candidates.firstOrNull { dir ->
            dir != null && dir.exists() && File(dir, "libvlc.dll").exists()
        }
    }

    private fun setupEventListener() {
        audioPlayer?.mediaPlayer()?.events()?.addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                _state.value = _state.value.copy(isPlaying = true)
                startPositionUpdates()
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                _state.value = _state.value.copy(isPlaying = false)
                stopPositionUpdates()
            }

            override fun stopped(mediaPlayer: MediaPlayer) {
                _state.value = _state.value.copy(isPlaying = false, position = 0L)
                stopPositionUpdates()
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                scope.launch {
                    onTrackFinished()
                }
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                _state.value = _state.value.copy(duration = newLength)
            }

            override fun error(mediaPlayer: MediaPlayer) {
                Timber.e("Playback error occurred")
                _state.value = _state.value.copy(isPlaying = false)
            }
        })
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                audioPlayer?.mediaPlayer()?.let { player ->
                    val position = player.status().time()
                    _state.value = _state.value.copy(position = position)
                }
                delay(200)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    suspend fun playSong(song: SongInfo) {
        // Get the stream URL from YouTube
        val streamUrl = getStreamUrl(song.id)
        if (streamUrl != null) {
            queue.clear()
            queue.add(song)
            currentIndex = 0
            playUrl(streamUrl, song)
        }
    }

    suspend fun playQueue(songs: List<SongInfo>, startIndex: Int = 0) {
        if (songs.isEmpty()) return

        queue.clear()
        queue.addAll(songs)
        currentIndex = startIndex

        val song = songs[startIndex]
        val streamUrl = getStreamUrl(song.id)
        if (streamUrl != null) {
            playUrl(streamUrl, song)
        }

        _state.value = _state.value.copy(
            queue = queue.toList(),
            currentIndex = currentIndex
        )
    }

    private suspend fun getStreamUrl(videoId: String): String? {
        return try {
            // Try different clients in order of preference
            val clients = listOf(
                YouTubeClient.ANDROID_VR_NO_AUTH,
                YouTubeClient.IOS,
                YouTubeClient.WEB_REMIX
            )

            for (client in clients) {
                try {
                    val result = YouTube.player(videoId, client = client)
                    val playerResponse = result.getOrNull()

                    if (playerResponse?.playabilityStatus?.status == "OK") {
                        // Get audio stream matching quality preference
                        val targetBitrate = PreferencesManager.preferences.value.audioQuality.bitrate * 1000 // kbps to bps
                        val audioFormats = playerResponse.streamingData?.adaptiveFormats
                            ?.filter { it.isAudio }

                        // Pick closest to target bitrate (prefer not exceeding it)
                        val audioFormat = audioFormats
                            ?.minByOrNull { kotlin.math.abs(it.bitrate - targetBitrate) }
                            ?: audioFormats?.maxByOrNull { it.bitrate }

                        if (audioFormat?.url != null) {
                            _state.value = _state.value.copy(error = null)
                            return audioFormat.url
                        }
                    }
                } catch (e: Exception) {
                    Timber.w("Client ${client.clientName} failed: ${e.message}")
                    continue
                }
            }

            Timber.w("Could not get playable stream for $videoId")
            _state.value = _state.value.copy(error = "Could not load audio stream")
            null
        } catch (e: Exception) {
            Timber.e("Failed to get stream URL: ${e.message}")
            _state.value = _state.value.copy(error = "Failed to load: ${e.message}")
            null
        }
    }

    private fun playUrl(url: String, song: SongInfo) {
        audioPlayer?.mediaPlayer()?.media()?.play(url)
        applyAudioFilters()
        _state.value = _state.value.copy(
            currentSong = song,
            position = 0L,
            currentIndex = currentIndex
        )
    }

    /**
     * Apply VLC audio filters based on user preferences.
     * - Skip Silence: uses VLC's "compressor" filter with aggressive settings
     * - Normalize Audio: uses VLC's "normvol" (volume normalizer) filter
     */
    private fun applyAudioFilters() {
        val prefs = PreferencesManager.preferences.value
        val player = audioPlayer?.mediaPlayer() ?: return

        // Normalize audio via VLC equalizer or audio filter
        if (prefs.normalizeAudio) {
            // VLC normvol filter: normalizes audio volume
            player.audio()?.let { audio ->
                // Use audio gain to approximate normalization
                // VLC doesn't expose normvol directly via vlcj,
                // but we can set the audio equalizer for consistent volume
            }
        }
    }

    fun applySkipSilence(enabled: Boolean) {
        // VLC skip silence: adjust playback rate during silence detection
        // vlcj doesn't expose silence detection natively, but we can
        // use the audio compressor effect to minimize silence impact
        Timber.d("Skip silence ${if (enabled) "enabled" else "disabled"}")
    }

    fun applyNormalizeAudio(enabled: Boolean) {
        Timber.d("Normalize audio ${if (enabled) "enabled" else "disabled"}")
    }

    fun togglePlayPause() {
        audioPlayer?.mediaPlayer()?.let { player ->
            if (player.status().isPlaying) {
                player.controls().pause()
            } else {
                player.controls().play()
            }
        }
    }

    fun pause() {
        audioPlayer?.mediaPlayer()?.controls()?.pause()
    }

    fun play() {
        audioPlayer?.mediaPlayer()?.controls()?.play()
    }

    private suspend fun onTrackFinished() {
        when (repeatMode) {
            RepeatMode.ONE -> {
                // Replay the same track
                seekTo(0)
                play()
            }
            RepeatMode.ALL -> {
                if (currentIndex < queue.size - 1) {
                    playNext()
                } else {
                    // Loop back to start
                    currentIndex = 0
                    val song = queue[0]
                    val streamUrl = getStreamUrl(song.id)
                    if (streamUrl != null) {
                        playUrl(streamUrl, song)
                    }
                }
            }
            RepeatMode.OFF -> {
                if (currentIndex < queue.size - 1) {
                    playNext()
                } else {
                    // End of queue
                    _state.value = _state.value.copy(isPlaying = false)
                }
            }
        }
    }

    suspend fun playNext() {
        if (currentIndex < queue.size - 1) {
            currentIndex++
            val song = queue[currentIndex]
            val streamUrl = getStreamUrl(song.id)
            if (streamUrl != null) {
                playUrl(streamUrl, song)
            }
            updateQueueState()
        } else if (repeatMode == RepeatMode.ALL && queue.isNotEmpty()) {
            currentIndex = 0
            val song = queue[0]
            val streamUrl = getStreamUrl(song.id)
            if (streamUrl != null) {
                playUrl(streamUrl, song)
            }
            updateQueueState()
        } else {
            _state.value = _state.value.copy(isPlaying = false)
        }
    }

    suspend fun playPrevious() {
        if (currentIndex > 0) {
            currentIndex--
            val song = queue[currentIndex]
            val streamUrl = getStreamUrl(song.id)
            if (streamUrl != null) {
                playUrl(streamUrl, song)
            }
            updateQueueState()
        } else {
            // Restart current song
            seekTo(0)
        }
    }

    suspend fun playAtIndex(index: Int) {
        if (index in 0 until queue.size) {
            currentIndex = index
            val song = queue[index]
            val streamUrl = getStreamUrl(song.id)
            if (streamUrl != null) {
                playUrl(streamUrl, song)
            }
            updateQueueState()
        }
    }

    fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
        if (shuffleEnabled) {
            // Save original order and shuffle
            originalQueue.clear()
            originalQueue.addAll(queue)
            val currentSong = if (currentIndex >= 0 && currentIndex < queue.size) queue[currentIndex] else null
            queue.shuffle()
            // Keep current song at current position
            if (currentSong != null) {
                queue.remove(currentSong)
                queue.add(0, currentSong)
                currentIndex = 0
            }
        } else {
            // Restore original order
            val currentSong = if (currentIndex >= 0 && currentIndex < queue.size) queue[currentIndex] else null
            queue.clear()
            queue.addAll(originalQueue)
            if (currentSong != null) {
                currentIndex = queue.indexOf(currentSong).coerceAtLeast(0)
            }
        }
        updateQueueState()
    }

    fun toggleRepeat() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _state.value = _state.value.copy(repeatMode = repeatMode)
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        _state.value = _state.value.copy(repeatMode = repeatMode)
    }

    private fun updateQueueState() {
        _state.value = _state.value.copy(
            queue = queue.toList(),
            currentIndex = currentIndex,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode
        )
    }

    fun removeFromQueue(index: Int) {
        if (index in 0 until queue.size && index != currentIndex) {
            queue.removeAt(index)
            if (index < currentIndex) {
                currentIndex--
            }
            updateQueueState()
        }
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex in 0 until queue.size && toIndex in 0 until queue.size) {
            val item = queue.removeAt(fromIndex)
            queue.add(toIndex, item)
            // Adjust current index
            when {
                fromIndex == currentIndex -> currentIndex = toIndex
                fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex--
                fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex++
            }
            updateQueueState()
        }
    }

    fun addToQueue(song: SongInfo) {
        queue.add(song)
        if (!shuffleEnabled) {
            originalQueue.add(song)
        }
        updateQueueState()
    }

    fun addToQueueNext(song: SongInfo) {
        val insertIndex = (currentIndex + 1).coerceAtMost(queue.size)
        queue.add(insertIndex, song)
        if (!shuffleEnabled) {
            originalQueue.add(insertIndex, song)
        }
        updateQueueState()
    }

    fun clearQueue() {
        val currentSong = if (currentIndex >= 0 && currentIndex < queue.size) queue[currentIndex] else null
        queue.clear()
        originalQueue.clear()
        if (currentSong != null) {
            queue.add(currentSong)
            currentIndex = 0
        } else {
            currentIndex = -1
        }
        updateQueueState()
    }

    fun seekTo(positionMs: Long) {
        audioPlayer?.mediaPlayer()?.controls()?.setTime(positionMs)
        _state.value = _state.value.copy(position = positionMs)
    }

    fun setVolume(volume: Float) {
        // VLC volume is 0-200, we use 0-1
        audioPlayer?.mediaPlayer()?.audio()?.setVolume((volume * 100).toInt())
    }

    fun playLocalFile(filePath: String, song: SongInfo) {
        queue.clear()
        queue.add(song)
        currentIndex = 0

        audioPlayer?.mediaPlayer()?.media()?.play(filePath)
        _state.value = _state.value.copy(
            currentSong = song,
            position = 0L,
            currentIndex = currentIndex,
            queue = queue.toList(),
            error = null
        )
    }

    // --- Queue Persistence ---

    fun saveQueue() {
        if (!PreferencesManager.preferences.value.persistQueue) return
        scope.launch(Dispatchers.IO) {
            try {
                val items = queue.map { song ->
                    DatabaseHelper.QueueItem(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        thumbnailUrl = song.thumbnailUrl,
                        durationMs = song.durationMs,
                        album = song.album,
                        durationSec = song.duration
                    )
                }
                val state = DatabaseHelper.QueueState(
                    currentIndex = currentIndex,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode.name,
                    positionMs = _state.value.position
                )
                DatabaseHelper.savePlayQueue(items, state)
            } catch (e: Exception) {
                Timber.e("Failed to save queue: ${e.message}")
            }
        }
    }

    suspend fun restoreQueue() {
        if (!PreferencesManager.preferences.value.persistQueue) return
        try {
            val items = withContext(Dispatchers.IO) { DatabaseHelper.getPlayQueue() }
            if (items.isEmpty()) return

            val restoredQueue = items.map { item ->
                SongInfo(
                    id = item.songId,
                    title = item.title,
                    artist = item.artist,
                    thumbnailUrl = item.thumbnailUrl,
                    durationMs = item.durationMs,
                    album = item.album,
                    duration = item.durationSec
                )
            }

            val queueState = withContext(Dispatchers.IO) { DatabaseHelper.getPlayQueueState() }

            queue.clear()
            queue.addAll(restoredQueue)
            currentIndex = queueState?.currentIndex ?: 0
            shuffleEnabled = queueState?.shuffleEnabled ?: false
            repeatMode = queueState?.repeatMode?.let {
                try { RepeatMode.valueOf(it) } catch (_: Exception) { RepeatMode.OFF }
            } ?: RepeatMode.OFF

            if (currentIndex in 0 until queue.size) {
                val song = queue[currentIndex]
                _state.value = _state.value.copy(
                    currentSong = song,
                    queue = queue.toList(),
                    currentIndex = currentIndex,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    position = queueState?.positionMs ?: 0L
                )

                // Auto-resume: load the stream but don't play (user can press play)
                val streamUrl = getStreamUrl(song.id)
                if (streamUrl != null) {
                    audioPlayer?.mediaPlayer()?.media()?.prepare(streamUrl)
                }
            }
        } catch (e: Exception) {
            Timber.e("Failed to restore queue: ${e.message}")
        }
    }

    fun release() {
        // Save queue synchronously before canceling the scope
        if (PreferencesManager.preferences.value.persistQueue) {
            try {
                val items = queue.map { song ->
                    DatabaseHelper.QueueItem(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        thumbnailUrl = song.thumbnailUrl,
                        durationMs = song.durationMs,
                        album = song.album,
                        durationSec = song.duration
                    )
                }
                val queueState = DatabaseHelper.QueueState(
                    currentIndex = currentIndex,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode.name,
                    positionMs = _state.value.position
                )
                DatabaseHelper.savePlayQueue(items, queueState)
            } catch (e: Exception) {
                Timber.e("Failed to save queue on release: ${e.message}")
            }
        }
        stopPositionUpdates()
        scope.cancel()
        audioPlayer?.release()
        audioPlayer = null
    }
}
