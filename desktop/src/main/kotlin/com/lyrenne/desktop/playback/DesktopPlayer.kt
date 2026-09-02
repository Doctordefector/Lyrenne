package com.lyrenne.desktop.playback

import com.metrolist.innertube.NewPipeUtils
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertube.strategy.ContentHints
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YouTubeClient
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.settings.PreferencesManager
import kotlinx.coroutines.*
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.log.LogLevel
import uk.co.caprica.vlcj.log.NativeLog
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent
import uk.co.caprica.vlcj.player.base.Equalizer
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

enum class RepeatMode {
    OFF, ONE, ALL
}

/** Below this the fader snaps to silence, so the bottom of the track is a true mute. */
internal const val FADER_MUTE_BELOW = 0.02f

/**
 * How hard the fader leans loud. The one knob worth turning in [vlcVolume].
 *
 * 1.66 is perceptually neutral: slider fraction and loudness fraction are the same number.
 * 1.0 is linear amplitude, which reads loud because it lifts the bottom of the travel a long
 * way. 1.43 sits between them and puts the midpoint of the travel 10% louder than it reads.
 *
 * The lean is not a flat percentage, because a power curve cannot be: it is 10% at half
 * travel, tapering to nothing at the top, and widening below. Lower the number to lean
 * louder still.
 */
internal const val FADER_LOUDNESS = 1.43

/**
 * Map the 0-1 slider to VLC's 0-100 volume.
 *
 * VLC CUBES this number before the mixer sees it. That is measured, not assumed: at slider
 * 0.633 the app sent 47 and Windows reported a session amplitude of 0.103823, which is
 * 0.47^3 to six decimals. Every taper this file has carried was written against a comment
 * claiming the scale was linear amplitude, so each one cubed a curve that was already a
 * curve. The 30 dB fader put half travel at -45 dB, i.e. silence, and a plain v^1.66 taper
 * still landed at v^5.
 *
 * So the amplitude we actually want is v^[FADER_LOUDNESS], and undoing VLC's cube leaves
 * v^(FADER_LOUDNESS / 3) as what we owe it.
 *
 *   v:      0.00  0.25  0.50  0.75  1.00
 *   VLC:       0    52    72    87   100
 *   dB:     -inf   -17    -9    -4     0
 *   loud:      0  0.31  0.55  0.78     1
 */
internal fun vlcVolume(volume: Float): Int {
    val v = volume.coerceIn(0f, 1f)
    if (v <= FADER_MUTE_BELOW) return 0
    return (v.toDouble().pow(FADER_LOUDNESS / 3) * 100).roundToInt().coerceIn(1, 100)
}

/**
 * How much of the fader a crossfading track still gets: all of it until the window opens,
 * then straight down to silence at the end of the track. Applied to the VLC number rather
 * than the slider fraction, so it matches the ramp [DesktopPlayer.fadeIn] uses coming back up.
 */
internal fun crossfadeGain(remainingMs: Long, crossfadeMs: Long): Float {
    if (crossfadeMs <= 0L) return 1f
    return (remainingMs.toFloat() / crossfadeMs).coerceIn(0f, 1f)
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

/**
 * Metadata duration in milliseconds, or 0 when it is not known.
 *
 * SongInfo carries the same fact twice and which copy is filled depends entirely on where the
 * song came from. Rows read from the database set `durationMs`; anything built by
 * `toPlayerSongInfo` from an InnerTube result sets only `duration`, in whole seconds, and leaves
 * `durationMs` at 0. Reading one field directly therefore works for library playback and silently
 * returns 0 for search, home, radio and explore, which is most of what actually gets played.
 *
 * Always prefer the live `PlaybackState.duration` when it is available: that comes from VLC and is
 * authoritative. This is the seed to use before VLC has reported anything.
 */
fun SongInfo.knownDurationMs(): Long = when {
    durationMs > 0 -> durationMs
    duration > 0 -> duration * 1000L
    else -> 0L
}

/** Sleep timer state: either a wall-clock deadline or end-of-current-track */
data class SleepTimerState(
    val endsAtMillis: Long? = null,
    val endOfTrack: Boolean = false
)

fun SongItem.toPlayerSongInfo() = SongInfo(
    id = id,
    title = title,
    artist = artists.joinToString { it.name },
    thumbnailUrl = thumbnail,
    album = album?.name,
    duration = duration ?: -1
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

    // Equalizer (vlcj programmatic API — real-time, no restart needed)
    private var vlcEqualizer: Equalizer? = null

    // Sleep timer
    private var sleepJob: Job? = null
    private val _sleepTimer = MutableStateFlow<SleepTimerState?>(null)
    val sleepTimer: StateFlow<SleepTimerState?> = _sleepTimer.asStateFlow()

    // Crossfade: set while the tail of the current track is being faded out
    private var crossfadeFading = false
    private var fadeJob: Job? = null

    // Radio: id of the song the current radio queue was seeded from (null = not radio)
    private var radioSeedId: String? = null
    private var radioLoading = false

    // Play event tracking
    private var trackStartTime: Long = 0L       // System.currentTimeMillis when track started playing
    private var accumulatedPlayTime: Long = 0L   // ms accumulated while playing (pauses excluded)
    private var lastPlayStateTime: Long = 0L     // timestamp of last play/pause state change
    private var wasPlaying: Boolean = false

    private var vlcInitialized = false

    // Last libvlc error line, captured from the native log. The media player `error` event
    // carries no reason at all, which made "playback fails" reports undiagnosable from logs.
    @Volatile
    private var lastVlcError: String? = null

    /**
     * Factory that pipes libvlc's own log into Timber. VLC warnings and errors name the actual
     * failure (TLS rejection, missing codec, blocked socket) that the plain `error` media event
     * hides, so a failed track leaves its cause in the log instead of a fixed string.
     */
    private inner class LoggingMediaPlayerFactory : MediaPlayerFactory() {
        // Strong ref for the factory's lifetime: the native log callback points into this
        // object, so collecting it would leave libvlc calling freed memory.
        @Suppress("unused")
        private val nativeLog = NativeLog(libvlcInstance).apply {
            setLevel(LogLevel.WARNING)
            addLogListener { level, module, _, _, _, _, _, message ->
                if (level == LogLevel.ERROR) {
                    // vlcj raises this sentinel when its own vsnprintf pass fails; it carries
                    // nothing and would displace the real reason. First real error per attempt
                    // wins for the banner: it is the root cause (e.g. the HTTP 403), every later
                    // line ("Your input can't be opened") is a consequence of it.
                    if (message != "Failed to format native log message" && lastVlcError == null) {
                        lastVlcError = "[$module] $message"
                    }
                    Timber.e("VLC [$module] $message")
                } else {
                    Timber.w("VLC [$module] $message")
                }
            }
        }
    }

    /** Call from a coroutine to initialize VLC off the main thread */
    fun ensureVlcInitialized() {
        if (!vlcInitialized) {
            vlcInitialized = true
            initializeVlc()
        }
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
                audioPlayer = AudioPlayerComponent(LoggingMediaPlayerFactory())
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
                onPlayStateChanged(true)
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                _state.value = _state.value.copy(isPlaying = false)
                stopPositionUpdates()
                onPlayStateChanged(false)
            }

            override fun stopped(mediaPlayer: MediaPlayer) {
                _state.value = _state.value.copy(isPlaying = false, position = 0L)
                stopPositionUpdates()
                onPlayStateChanged(false)
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                onPlayStateChanged(false)
                scope.launch {
                    onTrackFinished()
                }
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                _state.value = _state.value.copy(duration = newLength)
            }

            override fun error(mediaPlayer: MediaPlayer) {
                val detail = lastVlcError
                Timber.e("Playback error occurred${detail?.let { ": $it" } ?: ""}")
                _state.value = _state.value.copy(
                    isPlaying = false,
                    error = detail?.let { "Playback failed: $it" } ?: "Playback failed"
                )
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
                    applyCrossfadeTail(position)
                }
                delay(200)
            }
        }
    }

    /**
     * Crossfade: VLC has a single decoder, so two tracks cannot overlap. The tail of a track
     * fades to silence over the crossfade window instead, and the next one fades up from
     * silence in [playUrl], so the transition is a fade out into a fade in.
     *
     * This used to jump to the next track the moment the window opened and fade in only the
     * incoming one, so the setting did the opposite of its name: the outgoing song was cut
     * off mid-bar, losing its last crossfadeSec seconds outright (issue #5).
     *
     * Driven by the position tick rather than a timer, so a pause holds the fade where it
     * is, and seeking back out of the window restores full volume on the next tick.
     */
    private fun applyCrossfadeTail(position: Long) {
        val crossfadeMs = PreferencesManager.preferences.value.crossfadeSec * 1000L
        val duration = _state.value.duration
        val hasNext = currentIndex < queue.size - 1 || (repeatMode == RepeatMode.ALL && queue.size > 1)
        val inTail = crossfadeMs > 0 && duration > 0 && position > 0 && hasNext &&
            repeatMode != RepeatMode.ONE && duration - position <= crossfadeMs
        if (!inTail) {
            if (crossfadeFading) restoreVolume()
            return
        }
        if (!crossfadeFading) {
            crossfadeFading = true
            // A short track can still be fading in when its own tail starts; two ramps
            // writing the same volume would fight each other.
            fadeJob?.cancel()
        }
        val gain = crossfadeGain(duration - position, crossfadeMs)
        val target = vlcVolume(PreferencesManager.preferences.value.volume)
        audioPlayer?.mediaPlayer()?.audio()?.setVolume((target * gain).roundToInt())
    }

    /** Hand the volume back to the user's setting after a crossfade tail was interrupted. */
    private fun restoreVolume() {
        crossfadeFading = false
        audioPlayer?.mediaPlayer()?.audio()
            ?.setVolume(vlcVolume(PreferencesManager.preferences.value.volume))
    }

    /** Ramp VLC volume from 0 to the user's volume over [durationMs]. */
    private fun fadeIn(durationMs: Long) {
        fadeJob?.cancel()
        val target = vlcVolume(PreferencesManager.preferences.value.volume)
        fadeJob = scope.launch {
            val steps = 20
            val stepDelay = durationMs / steps
            for (i in 0..steps) {
                if (!isActive) return@launch
                audioPlayer?.mediaPlayer()?.audio()?.setVolume(target * i / steps)
                delay(stepDelay)
            }
            audioPlayer?.mediaPlayer()?.audio()?.setVolume(target)
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    suspend fun playSong(song: SongInfo) {
        radioSeedId = null
        // Get the stream URL from YouTube
        val streamUrl = getStreamUrl(song.id)
        if (streamUrl != null) {
            queue.clear()
            queue.add(song)
            currentIndex = 0
            playUrl(streamUrl, song)
            // Emit queue state so Listen Together and other observers see the updated queue
            _state.value = _state.value.copy(
                queue = queue.toList(),
                currentIndex = currentIndex
            )
        }
    }

    suspend fun playQueue(songs: List<SongInfo>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        radioSeedId = null

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

    // ponytail: upstream's client chooser; kept in innertube so syncs update it for free
    private val fallbackStrategy = ContentAwareFallbackStrategy()

    private suspend fun getStreamUrl(videoId: String): String? {
        return try {
            for (client in fallbackStrategy.resolveClients(ContentHints())) {
                // JVM has no BotGuard for PoTokens, and no login for login-only clients
                if (client.requirePoToken) continue
                if (client.loginRequired && YouTube.cookie == null) continue
                try {
                    val signatureTimestamp = if (client.useSignatureTimestamp) {
                        withContext(Dispatchers.IO) { NewPipeUtils.getSignatureTimestamp(videoId).getOrNull() }
                    } else null
                    val playerResponse = YouTube.player(
                        videoId,
                        client = client,
                        signatureTimestamp = signatureTimestamp
                    ).getOrNull()

                    if (playerResponse?.playabilityStatus?.status == "OK") {
                        // Get audio stream matching quality preference
                        val targetBitrate = PreferencesManager.preferences.value.audioQuality.bitrate * 1000 // kbps to bps
                        val audioFormats = playerResponse.streamingData?.adaptiveFormats
                            ?.filter { it.isAudio }

                        // Pick closest to target bitrate (prefer not exceeding it)
                        val audioFormat = audioFormats
                            ?.minByOrNull { kotlin.math.abs(it.bitrate - targetBitrate) }
                            ?: audioFormats?.maxByOrNull { it.bitrate }

                        if (audioFormat != null) {
                            // Deobfuscates signatureCipher and the n throttle param (web clients)
                            val streamUrl = withContext(Dispatchers.IO) {
                                NewPipeUtils.getStreamUrl(audioFormat, videoId).getOrNull()
                            }
                            if (!streamUrl.isNullOrEmpty()) {
                                Timber.d("Stream resolved for $videoId via ${client.clientName}")
                                _state.value = _state.value.copy(error = null)
                                return streamUrl
                            }
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
        // Record play event for the previous track before switching
        if (_state.value.currentSong != null) {
            recordPlayEvent()
        }

        val crossfadeMs = PreferencesManager.preferences.value.crossfadeSec * 1000L
        val shouldFadeIn = crossfadeMs > 0 && _state.value.currentSong != null

        // A stale error from the previous track would otherwise be reported for this one.
        lastVlcError = null
        _state.value = _state.value.copy(error = null)

        val options = buildMediaOptions()
        if (options.isNotEmpty()) {
            audioPlayer?.mediaPlayer()?.media()?.play(url, *options.toTypedArray())
        } else {
            audioPlayer?.mediaPlayer()?.media()?.play(url)
        }
        _state.value = _state.value.copy(
            currentSong = song,
            position = 0L,
            // Seed from metadata, because `duration` is otherwise only ever assigned by VLC's
            // async lengthChanged callback. Without this it kept the PREVIOUS track's value for
            // the whole gap between starting playback and VLC parsing the stream, and everything
            // reading it in that window was wrong: the progress bar was mis-scaled, and Discord
            // published an end timestamp computed from the wrong track. Discord never recovered,
            // because presence only re-fires on song change or seek. The longer the track, the
            // later lengthChanged lands and the wider that window gets.
            duration = song.knownDurationMs(),
            currentIndex = currentIndex
        )
        resetPlayTracking()
        crossfadeFading = false

        // Re-apply EQ after starting new media (VLC resets filters on new media)
        applyEqualizer()

        // Apply playback speed (VLC resets rate on new media)
        val speed = PreferencesManager.preferences.value.playbackSpeed
        if (speed != 1f) {
            audioPlayer?.mediaPlayer()?.controls()?.setRate(speed)
        }

        if (shouldFadeIn) {
            fadeIn(crossfadeMs.coerceAtMost(4000L))
        }
    }

    /**
     * Build VLC media options based on user preferences.
     * These are passed as per-media options when starting playback.
     */
    private fun buildMediaOptions(): List<String> {
        val prefs = PreferencesManager.preferences.value
        val options = mutableListOf<String>()

        val filters = mutableListOf<String>()

        // Normalize Audio: VLC's normvol filter keeps volume consistent across tracks
        if (prefs.normalizeAudio) {
            filters.add("normvol")
            options.add(":norm-buff-size=10")
            options.add(":norm-max-level=2.0")
        }

        // Skip Silence: VLC's compressor filter with aggressive settings
        // reduces dynamic range, making silent sections much shorter perceptually
        if (prefs.skipSilence) {
            filters.add("compressor")
            options.add(":compressor-rms-peak=0.0")
            options.add(":compressor-attack=1.5")
            options.add(":compressor-release=20.0")
            options.add(":compressor-threshold=-30.0")
            options.add(":compressor-ratio=20.0")
            options.add(":compressor-knee=1.0")
            options.add(":compressor-makeup-gain=12.0")
        }

        if (filters.isNotEmpty()) {
            options.add(0, ":audio-filter=${filters.joinToString(":")}")
        }

        return options
    }

    fun togglePlayPause() {
        audioPlayer?.mediaPlayer()?.let { player ->
            if (player.status().isPlaying) {
                player.controls().pause()
            } else if (player.media().info() != null) {
                // Media is loaded, just resume
                player.controls().play()
            } else if (currentIndex in 0 until queue.size) {
                // No media loaded (e.g. restored queue) — resolve stream and play
                val song = queue[currentIndex]
                scope.launch {
                    val streamUrl = getStreamUrl(song.id)
                    if (streamUrl != null) {
                        playUrl(streamUrl, song)
                    }
                }
            }
        }
    }

    fun pause() {
        audioPlayer?.mediaPlayer()?.controls()?.pause()
    }

    /** Dismiss a surfaced playback error (banner auto-hide and close actions). */
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun play() {
        audioPlayer?.mediaPlayer()?.controls()?.play()
    }

    private suspend fun onTrackFinished() {
        // Sleep timer set to "end of track": stop here instead of advancing
        if (_sleepTimer.value?.endOfTrack == true) {
            _sleepTimer.value = null
            _state.value = _state.value.copy(isPlaying = false)
            // Nothing follows to fade in, so the crossfade tail's silence would be permanent.
            if (crossfadeFading) restoreVolume()
            return
        }
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
        maybeLoadMoreRadio()
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
        // Auto-save queue on every state change
        saveQueue()
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
        audioPlayer?.mediaPlayer()?.audio()?.setVolume(vlcVolume(volume))
    }

    fun playLocalFile(filePath: String, song: SongInfo) {
        lastVlcError = null
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
                // Stream URL resolved lazily on first play — no network call at startup
            }
        } catch (e: Exception) {
            Timber.e("Failed to restore queue: ${e.message}")
        }
    }

    // ============ Play Event Tracking ============

    private fun onPlayStateChanged(playing: Boolean) {
        val now = System.currentTimeMillis()
        if (wasPlaying && !playing) {
            // Was playing, now paused/stopped — accumulate time
            accumulatedPlayTime += now - lastPlayStateTime
        }
        lastPlayStateTime = now
        wasPlaying = playing
    }

    private fun resetPlayTracking() {
        val now = System.currentTimeMillis()
        trackStartTime = now
        lastPlayStateTime = now
        accumulatedPlayTime = 0L
        wasPlaying = false
    }

    private fun recordPlayEvent() {
        // Finalize accumulated time if currently playing
        if (wasPlaying) {
            accumulatedPlayTime += System.currentTimeMillis() - lastPlayStateTime
        }
        // Privacy: listen history paused
        if (PreferencesManager.preferences.value.pauseListenHistory) {
            resetPlayTracking()
            return
        }
        val songId = _state.value.currentSong?.id ?: return
        val playTimeMs = accumulatedPlayTime
        // Only record if played for at least 10 seconds
        if (playTimeMs >= 10_000) {
            scope.launch(Dispatchers.IO) {
                try {
                    DatabaseHelper.recordEvent(songId, playTimeMs)
                    Timber.d("Recorded play event: $songId, ${playTimeMs / 1000}s")
                } catch (e: Exception) {
                    Timber.e("Failed to record play event: ${e.message}")
                }
            }
        }
        resetPlayTracking()
    }

    // ============ Playback Speed ============

    /** Set playback rate (0.25x–3x). Applied immediately and persisted for future tracks. */
    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 3f)
        PreferencesManager.setPlaybackSpeed(clamped)
        audioPlayer?.mediaPlayer()?.controls()?.setRate(clamped)
    }

    // ============ Sleep Timer ============

    /** Start a sleep timer that pauses playback after [minutes]. */
    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _sleepTimer.value = SleepTimerState(endsAtMillis = endsAt)
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            _sleepTimer.value = null
        }
    }

    /** Pause playback when the current track ends. */
    fun setSleepEndOfTrack() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepTimer.value = SleepTimerState(endOfTrack = true)
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepTimer.value = null
    }

    // ============ Radio ============

    /**
     * Start a radio queue seeded from [song]: plays the song followed by
     * YouTube Music's auto-generated mix of related tracks.
     */
    suspend fun startRadio(song: SongInfo) {
        val result = YouTube.next(WatchEndpoint(videoId = song.id, playlistId = "RDAMVM${song.id}"))
        result.onSuccess { next ->
            val related = next.items
                .map { it.toPlayerSongInfo() }
                .filter { it.id != song.id }
            playQueue(listOf(song) + related, 0)
            radioSeedId = song.id // after playQueue (which clears it)
        }.onFailure { e ->
            Timber.w("Radio failed for ${song.id}: ${e.message}")
            // Fall back to just playing the song
            playSong(song)
        }
    }

    /**
     * Auto-queue: when enabled and the queue is nearly exhausted, append more
     * related tracks seeded from the last queue item.
     */
    private suspend fun maybeLoadMoreRadio() {
        val prefs = PreferencesManager.preferences.value
        val radioActive = radioSeedId != null
        if (!prefs.autoLoadRadio && !radioActive) return
        if (radioLoading) return
        if (queue.isEmpty() || currentIndex < queue.size - 3) return
        if (repeatMode != RepeatMode.OFF) return

        radioLoading = true
        try {
            val seed = queue.last()
            val result = YouTube.next(WatchEndpoint(videoId = seed.id, playlistId = "RDAMVM${seed.id}"))
            result.onSuccess { next ->
                val existing = queue.map { it.id }.toSet()
                val newSongs = next.items
                    .map { it.toPlayerSongInfo() }
                    .filter { it.id !in existing }
                    .take(20)
                if (newSongs.isNotEmpty()) {
                    queue.addAll(newSongs)
                    if (!shuffleEnabled) originalQueue.addAll(newSongs)
                    updateQueueState()
                    Timber.d("Auto-queued ${newSongs.size} related tracks")
                }
            }
        } catch (e: Exception) {
            Timber.w("Auto-queue failed: ${e.message}")
        } finally {
            radioLoading = false
        }
    }

    // ============ Equalizer ============

    /** Available EQ preset names from VLC */
    fun getEqualizerPresets(): List<String> {
        val factory = audioPlayer?.mediaPlayerFactory() ?: return emptyList()
        return factory.equalizer().presets()
    }

    /** EQ band center frequencies in Hz */
    fun getEqualizerBands(): List<Float> {
        val factory = audioPlayer?.mediaPlayerFactory() ?: return emptyList()
        return factory.equalizer().bands()
    }

    /** Apply EQ settings from preferences (call on init and when prefs change) */
    fun applyEqualizer() {
        val player = audioPlayer?.mediaPlayer() ?: return
        val prefs = PreferencesManager.preferences.value

        if (!prefs.eqEnabled) {
            player.audio().setEqualizer(null)
            vlcEqualizer = null
            return
        }

        val factory = audioPlayer?.mediaPlayerFactory() ?: return
        val eq = if (prefs.eqPreset != null) {
            factory.equalizer().newEqualizer(prefs.eqPreset)
        } else {
            factory.equalizer().newEqualizer()
        }

        if (eq != null) {
            eq.setPreamp(prefs.eqPreamp)
            if (prefs.eqPreset == null) {
                // Apply custom band values
                prefs.eqBands.forEachIndexed { i, gain ->
                    eq.setAmp(i, gain)
                }
            }
            player.audio().setEqualizer(eq)
            vlcEqualizer = eq
        }
    }

    /** Update a single EQ band in real-time */
    fun setEqualizerBand(index: Int, gain: Float) {
        vlcEqualizer?.setAmp(index, gain)
        PreferencesManager.setEqBand(index, gain)
    }

    /** Update preamp in real-time */
    fun setEqualizerPreamp(preamp: Float) {
        vlcEqualizer?.setPreamp(preamp)
        PreferencesManager.setEqPreamp(preamp)
    }

    /** Switch to a named preset */
    fun setEqualizerPreset(presetName: String) {
        val factory = audioPlayer?.mediaPlayerFactory() ?: return
        val eq = factory.equalizer().newEqualizer(presetName) ?: return
        audioPlayer?.mediaPlayer()?.audio()?.setEqualizer(eq)
        vlcEqualizer = eq

        // Save preset and its band values to prefs
        val bands = (0 until 10).map { eq.amp(it) }
        PreferencesManager.setEqPreset(presetName)
        PreferencesManager.setEqPreamp(eq.preamp())
        val current = PreferencesManager.preferences.value
        // Update bands without clearing preset (setEqBands clears preset, so update directly)
        val updatedPrefs = current.copy(eqBands = bands, eqPreset = presetName)
        // We need to save all at once — use internal update
        PreferencesManager.setEqPreset(presetName)
    }

    /** Enable/disable EQ */
    fun setEqualizerEnabled(enabled: Boolean) {
        PreferencesManager.setEqEnabled(enabled)
        applyEqualizer()
    }

    fun release() {
        // Record final play event before shutdown
        if (_state.value.currentSong != null) {
            recordPlayEvent()
        }
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
