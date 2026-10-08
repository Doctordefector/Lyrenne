package com.lyrenne.desktop.playback

import com.lyrenne.desktop.Platform
import com.metrolist.innertube.NewPipeUtils
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertube.strategy.ContentHints
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YouTubeClient
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.download.DownloadManager
import com.lyrenne.desktop.settings.PreferencesManager
import kotlinx.coroutines.*
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** Songs started this session, newest first, each once. Library shows it as Recently Played. */
    val recentlyPlayed: List<SongInfo> = emptyList()
)

data class SongInfo(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationMs: Long = 0L,
    val album: String? = null,
    val duration: Int = -1, // in seconds
    /**
     * Queued by autoplay rather than by the user. Songs the user adds to the queue go in front of
     * these, the way they would in front of Spotify's autoplay. Not persisted: a restored queue
     * treats every song as the user's.
     */
    val fromAutoplay: Boolean = false,
    /** Mixed into a playlist by Smart Shuffle: not in the playlist, picked to sound like it. */
    val suggested: Boolean = false
)

/** How many songs [PlaybackState.recentlyPlayed] keeps. */
private const val RECENTLY_PLAYED_MAX = 10

/**
 * Turns a page of YouTube's radio into songs to queue: drops anything already [queued] (a radio
 * opens with its own seed), repeats within the page, and explicit songs when those are hidden.
 */
internal fun similarSongsToQueue(
    page: List<SongItem>,
    queued: Set<String>,
    hideExplicit: Boolean,
    fromAutoplay: Boolean
): List<SongInfo> {
    val seen = HashSet(queued)
    return page
        .filterNot { hideExplicit && it.explicit }
        .filter { seen.add(it.id) }
        .map { it.toPlayerSongInfo().copy(fromAutoplay = fromAutoplay) }
}

/** Smart Shuffle puts one suggestion after every this many playlist songs. */
internal const val SMART_SHUFFLE_EVERY = 3

/** How many of the playlist's songs Smart Shuffle seeds radios from, fetched in parallel. */
private const val SMART_SHUFFLE_SEEDS = 3

/**
 * [songs] with one of [suggestions] after every [every] of them, in order. Suggestions left over
 * once the songs run out are dropped, so the playlist stays the bulk of what plays.
 */
internal fun interleaveSuggestions(songs: List<SongInfo>, suggestions: List<SongInfo>, every: Int): List<SongInfo> {
    val extra = suggestions.iterator()
    return buildList {
        songs.forEachIndexed { i, song ->
            add(song)
            if ((i + 1) % every == 0 && extra.hasNext()) add(extra.next())
        }
    }
}

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

    // Autoplay: similar songs from YouTube's radio, added once the queue is down to its last song.
    // radioStarted means "Start Radio" built this queue, which keeps going whatever the setting says.
    private var radioStarted = false
    private var autoplayFetch: Deferred<Boolean>? = null
    // Bumped whenever the queue is replaced, so a fetch still in flight for the old one is dropped
    private var queueGeneration = 0

    /**
     * Whether this client may pick what plays after the queue. Listen Together turns it off for
     * guests: host and guests all announce track changes, so a guest that autoplayed on its own
     * would move the whole room onto a song nobody chose.
     */
    var autoplayAllowed: () -> Boolean = { true }

    /** YouTube's radio for a song. Swapped out by tests; nothing else touches it. */
    internal var radioFor: suspend (videoId: String) -> Result<List<SongItem>> = { id ->
        YouTube.next(WatchEndpoint(videoId = id, playlistId = "RDAMVM$id")).map { it.items }
    }

    /**
     * A song's downloaded file, or null when it has none. Swapped out by tests; nothing else
     * touches it. A lookup that fails costs the song its offline copy, not its playback: it
     * streams instead.
     */
    internal var downloadedFileFor: suspend (songId: String) -> File? = { id ->
        withContext(Dispatchers.IO) {
            try {
                DownloadManager.getDownloadPathById(id)
            } catch (e: Exception) {
                Timber.w("Download lookup failed for $id: ${e.message}")
                null
            }
        }
    }

    /** A YouTube stream for a song. Swapped out by tests; nothing else touches it. */
    internal var streamFor: suspend (videoId: String) -> String? = { id -> getStreamUrl(id) }

    init {
        // Switching autoplay off takes back the songs it already queued. Otherwise a queue it had
        // topped up would keep playing its picks for hours after the user said stop.
        scope.launch {
            PreferencesManager.preferences.map { it.autoplay }.distinctUntilChanged().drop(1)
                .collect { enabled -> if (!enabled) dropQueuedAutoplay() }
        }
    }

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
            // Windows: try the VLC bundled in the app folder first. Linux: never bundled, the
            // distro's libvlc is found by NativeDiscovery in /usr/lib and friends.
            val bundledVlcDir = findBundledVlc()
            val bundledPath = bundledVlcDir?.absolutePath
            if (bundledPath != null) {
                Timber.i("Using bundled VLC from: $bundledPath")
                // libvlc finds plugins/ next to libvlc.dll by itself; only the library path is needed.
                val currentPath = System.getProperty("jna.library.path", "")
                System.setProperty("jna.library.path",
                    if (currentPath.isEmpty()) bundledPath else "$bundledPath${File.pathSeparator}$currentPath")
            }
            // NativeDiscovery checks jna.library.path, system PATH, and standard install locations
            val found = NativeDiscovery().discover()

            if (found) {
                audioPlayer = AudioPlayerComponent(LoggingMediaPlayerFactory())
                setupEventListener()
                Timber.i("VLC initialized successfully")
            } else {
                Timber.w("VLC not found")
                _state.value = _state.value.copy(
                    vlcAvailable = false,
                    error = vlcMissingMessage(bundled = bundledPath != null)
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

    private fun vlcMissingMessage(bundled: Boolean): String = when {
        Platform.isLinux -> "Lyrenne needs VLC. Install it with your package manager, e.g. " +
            "sudo apt install vlc / sudo dnf install vlc / sudo pacman -S vlc. " +
            "The Snap and Flatpak versions of VLC can't be used."
        bundled -> "VLC failed to load from the app folder. Re-extract the ZIP."
        else -> "VLC not found. Please install VLC media player (64-bit)."
    }

    private fun findBundledVlc(): File? {
        if (!Platform.isWindows) return null
        // Check for bundled VLC in app resources (Compose Desktop native distribution)
        val candidates = listOf(
            // When running as packaged app (createDistributable)
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
     * silence in [playMedia], so the transition is a fade out into a fade in.
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

    /**
     * Plays [song] on its own, replacing the queue. Autoplay carries on after it when that is on.
     * False when it has neither a download nor a stream, in which case the old queue is left alone.
     */
    suspend fun playSong(song: SongInfo): Boolean = playSingle(song, radio = false)

    private suspend fun playSingle(song: SongInfo, radio: Boolean): Boolean {
        val media = mediaFor(song) ?: return false
        resetAutoplay(radio)
        queue.clear()
        queue.add(song)
        syncOriginalQueue()
        currentIndex = 0
        playMedia(media, song)
        // Emit queue state so Listen Together and other observers see the updated queue
        _state.value = _state.value.copy(
            queue = queue.toList(),
            currentIndex = currentIndex
        )
        return true
    }

    suspend fun playQueue(songs: List<SongInfo>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        resetAutoplay()

        queue.clear()
        queue.addAll(songs)
        syncOriginalQueue()
        currentIndex = startIndex

        val song = songs[startIndex]
        mediaFor(song)?.let { playMedia(it, song) }

        _state.value = _state.value.copy(
            queue = queue.toList(),
            currentIndex = currentIndex
        )
    }

    /**
     * Where [song] plays from: its downloaded file when it has one, otherwise a YouTube stream, and
     * null when it has neither. Every path that starts a song comes through here. They all used to
     * stream, and only a click in the Downloads tab played the file, so a downloaded song reached
     * any other way (an album, a playlist, Next, a restored queue) was streamed anyway, which
     * fails offline.
     */
    private suspend fun mediaFor(song: SongInfo): String? {
        val file = downloadedFileFor(song.id) ?: return streamFor(song.id)
        Timber.d("Playing ${song.id} from its download")
        return file.absolutePath
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
                        val allAudio = playerResponse.streamingData?.adaptiveFormats
                            ?.filter { it.isAudio }
                        // Linux: distro VLC builds (Fedora's stock one) may ship without an AAC
                        // decoder, while Opus is decodable everywhere. Prefer it when offered.
                        val audioFormats = if (Platform.isLinux) {
                            allAudio?.filter { "opus" in it.mimeType }?.ifEmpty { null } ?: allAudio
                        } else allAudio

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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // cancellation must propagate, not be treated as a failure
                } catch (e: Exception) {
                    Timber.w("Client ${client.clientName} failed: ${e.message}")
                    continue
                }
            }

            Timber.w("Could not get playable stream for $videoId")
            _state.value = _state.value.copy(error = "Could not load audio stream")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // cancellation must propagate, not be treated as a failure
        } catch (e: Exception) {
            Timber.e("Failed to get stream URL: ${e.message}")
            _state.value = _state.value.copy(error = "Failed to load: ${e.message}")
            null
        }
    }

    /**
     * Starts [song] from [media], a stream URL or a downloaded file's path. VLC opens either, and
     * all of this applies to both. Files used to start through a path of their own, which skipped
     * the previous song's play event, the crossfade, the EQ, the speed and the audio filters.
     */
    private fun playMedia(media: String, song: SongInfo) {
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
            audioPlayer?.mediaPlayer()?.media()?.play(media, *options.toTypedArray())
        } else {
            audioPlayer?.mediaPlayer()?.media()?.play(media)
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
            currentIndex = currentIndex,
            recentlyPlayed = recentlyPlayedWith(song)
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

        prefetchSimilarSongs()
    }

    private fun recentlyPlayedWith(song: SongInfo): List<SongInfo> =
        (listOf(song.copy(fromAutoplay = false)) + _state.value.recentlyPlayed.filter { it.id != song.id })
            .take(RECENTLY_PLAYED_MAX)

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
                // No media loaded (e.g. restored queue): start it from its download or a stream
                val song = queue[currentIndex]
                scope.launch {
                    mediaFor(song)?.let { playMedia(it, song) }
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

    /** VLC's finished event. Internal so the smoke test can end a track without VLC. */
    internal suspend fun onTrackFinished() {
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
                    mediaFor(song)?.let { playMedia(it, song) }
                }
            }
            RepeatMode.OFF -> {
                // A song played on its own is a one-song queue, so this used to be the end of
                // everything played from search, home or the library (issues #11 and #12).
                // Autoplay's songs are normally queued by now; this waits for them if not.
                if (currentIndex < queue.size - 1 || awaitSimilarSongs()) {
                    playNext()
                } else {
                    // End of queue
                    _state.value = _state.value.copy(isPlaying = false)
                }
            }
        }
    }

    suspend fun playNext() {
        // Next on the last song: give autoplay the chance to add something to skip to
        if (currentIndex >= queue.size - 1) awaitSimilarSongs()
        if (currentIndex < queue.size - 1) {
            currentIndex++
            val song = queue[currentIndex]
            mediaFor(song)?.let { playMedia(it, song) }
            updateQueueState()
        } else if (repeatMode == RepeatMode.ALL && queue.isNotEmpty()) {
            currentIndex = 0
            val song = queue[0]
            mediaFor(song)?.let { playMedia(it, song) }
            updateQueueState()
        } else {
            // Nothing to skip to. Report what VLC is doing instead of claiming a stop: this is
            // reached by pressing Next on the last song, which is usually still playing, and
            // saying otherwise left the play button showing paused over audible music.
            _state.value = _state.value.copy(
                isPlaying = audioPlayer?.mediaPlayer()?.status()?.isPlaying == true
            )
        }
    }

    suspend fun playPrevious() {
        if (currentIndex > 0) {
            currentIndex--
            val song = queue[currentIndex]
            mediaFor(song)?.let { playMedia(it, song) }
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
            mediaFor(song)?.let { playMedia(it, song) }
            updateQueueState()
        }
    }

    /**
     * Mirrors [queue] into [originalQueue] while shuffle is off. Without this the unshuffle order was
     * only ever captured when shuffle was switched on, so [originalQueue] sat empty through every
     * normal flow and "play next" inserting into it by index went out of range (issue #9). While
     * shuffle is on, songs added to the queue go into it by hand: see [hasOrderToRestore].
     */
    private fun syncOriginalQueue() {
        if (shuffleEnabled) return
        originalQueue.clear()
        originalQueue.addAll(queue)
    }

    /**
     * Whether turning shuffle off will go back to [originalQueue], so that a song added to the queue
     * now has to go into it too. Left out, the song disappeared when shuffle went off, and if it was
     * the one playing, the current index fell back to 0 while it played on.
     *
     * False when no order was saved, which a restart with shuffle on and clearing the queue while
     * shuffled both leave behind. Turning shuffle off keeps the queue as it is then, and adding to
     * the empty order would make it just the songs added since, which is all that turning shuffle
     * off would leave.
     */
    private fun hasOrderToRestore(): Boolean = shuffleEnabled && originalQueue.isNotEmpty()

    /**
     * Where the playing song sits in [originalQueue]: -1 while nothing is playing, the same as
     * [currentIndex], and the last position when the song is not in there, so that anything placed
     * after it lands at the end.
     */
    private fun currentInOriginalQueue(): Int {
        val current = queue.getOrNull(currentIndex) ?: return -1
        return originalQueue.indexOf(current).takeIf { it >= 0 } ?: originalQueue.lastIndex
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
        } else if (originalQueue.isNotEmpty()) {
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
            syncOriginalQueue()
            if (index < currentIndex) {
                currentIndex--
            }
            updateQueueState()
        }
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex in 0 until queue.size && toIndex in 0 until queue.size) {
            val item = queue.removeAt(fromIndex)
            // Placed by hand, so it is the user's pick now: songs added later must not jump it
            val placed = if (item.fromAutoplay && fromIndex != currentIndex) item.copy(fromAutoplay = false) else item
            queue.add(toIndex, placed)
            syncOriginalQueue()
            // Adjust current index
            when {
                fromIndex == currentIndex -> currentIndex = toIndex
                fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex--
                fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex++
            }
            updateQueueState()
        }
    }

    /** Where a song the user queues goes: in front of the first of autoplay's picks after [after], else at the end. */
    private fun List<SongInfo>.slotAheadOfAutoplay(after: Int): Int =
        (after + 1 until size).firstOrNull { this[it].fromAutoplay } ?: size

    fun addToQueue(song: SongInfo) {
        // In front of autoplay's picks, which only fill in after what the user chose. Otherwise
        // a song queued during a search result would wait behind fifty suggestions. The order
        // shuffle goes back to follows the same rule, from wherever the playing song sits in it.
        if (hasOrderToRestore()) originalQueue.add(originalQueue.slotAheadOfAutoplay(currentInOriginalQueue()), song)
        queue.add(queue.slotAheadOfAutoplay(currentIndex), song)
        syncOriginalQueue()
        updateQueueState()
    }

    fun addToQueueNext(song: SongInfo) {
        // Straight after the playing song, in the order shuffle goes back to as well
        if (hasOrderToRestore()) originalQueue.add(currentInOriginalQueue() + 1, song)
        queue.add((currentIndex + 1).coerceIn(0, queue.size), song)
        syncOriginalQueue()
        updateQueueState()
    }

    fun clearQueue() {
        val currentSong = if (currentIndex >= 0 && currentIndex < queue.size) queue[currentIndex] else null
        resetAutoplay()
        queue.clear()
        originalQueue.clear()
        if (currentSong != null) {
            queue.add(currentSong)
            currentIndex = 0
        } else {
            currentIndex = -1
        }
        syncOriginalQueue()
        updateQueueState()
    }

    fun seekTo(positionMs: Long) {
        audioPlayer?.mediaPlayer()?.controls()?.setTime(positionMs)
        _state.value = _state.value.copy(position = positionMs)
    }

    fun setVolume(volume: Float) {
        audioPlayer?.mediaPlayer()?.audio()?.setVolume(vlcVolume(volume))
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

            resetAutoplay()
            queue.clear()
            queue.addAll(restoredQueue)
            currentIndex = queueState?.currentIndex ?: 0
            shuffleEnabled = queueState?.shuffleEnabled ?: false
            repeatMode = queueState?.repeatMode?.let {
                try { RepeatMode.valueOf(it) } catch (_: Exception) { RepeatMode.OFF }
            } ?: RepeatMode.OFF
            // After shuffleEnabled is known: a queue restored unshuffled is its own original order.
            syncOriginalQueue()

            if (currentIndex in 0 until queue.size) {
                val song = queue[currentIndex]
                _state.value = _state.value.copy(
                    currentSong = song,
                    queue = queue.toList(),
                    currentIndex = currentIndex,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    position = queueState?.positionMs ?: 0L,
                    // playMedia normally seeds this; a restored song is not played yet, so seed it
                    // here or the bar reads 0:00 / 0:00 until it is. VLC corrects it on play.
                    duration = song.knownDurationMs()
                )
                // Download or stream found lazily on first play: no network call at startup
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // cancellation must propagate, not be treated as a failure
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

    // ============ Radio and autoplay ============

    /**
     * Start a radio queue seeded from [song]: plays the song straight away, then fills the queue
     * with YouTube Music's mix of related tracks. The song used to wait for the mix, which takes
     * between half a second and four seconds to arrive.
     */
    suspend fun startRadio(song: SongInfo) {
        if (!playSingle(song, radio = true)) return
        awaitSimilarSongs(force = true)
    }

    private fun autoplayWanted(): Boolean =
        (radioStarted || PreferencesManager.preferences.value.autoplay) &&
            repeatMode == RepeatMode.OFF && autoplayAllowed()

    /** A replaced queue starts over: nothing still in flight for the old one may land in it. */
    private fun resetAutoplay(radio: Boolean = false) {
        queueGeneration++
        radioStarted = radio
        autoplayFetch?.cancel()
        autoplayFetch = null
    }

    /**
     * Once the queue is down to its last song, fetches what autoplay will play after it, so the
     * songs are queued before it ends. A fetch takes between half a second and four seconds,
     * too long a silence to leave until the song has finished.
     */
    private fun prefetchSimilarSongs() {
        if (currentIndex != queue.size - 1 || !autoplayWanted()) return
        if (autoplayFetch?.isActive == true) return
        fetchSimilarSongs()
    }

    private fun fetchSimilarSongs(force: Boolean = false): Deferred<Boolean> {
        val generation = queueGeneration
        return scope.async { appendSimilarSongs(generation, force) }.also { autoplayFetch = it }
    }

    /**
     * The queue has run out: waits for the fetch [prefetchSimilarSongs] started, or starts one.
     * True if songs were added. [force] is Start Radio, which fills the queue whatever the
     * setting, the repeat mode or the Listen Together role.
     */
    internal suspend fun awaitSimilarSongs(force: Boolean = false): Boolean {
        if (queue.isEmpty() || (!force && !autoplayWanted())) return false
        val fetch = autoplayFetch?.takeIf { it.isActive } ?: fetchSimilarSongs(force)
        return try {
            fetch.await()
        } catch (e: CancellationException) {
            // The fetch was dropped because the queue was replaced. Only rethrow if it is this
            // caller that is being cancelled.
            currentCoroutineContext().ensureActive()
            false
        }
    }

    /**
     * Appends YouTube's radio for the last song in the queue, minus anything already queued.
     *
     * Seeded from the tail every time rather than paging through one radio. Measured on three
     * seeds: a continuation page brought 5 to 14 new songs out of 49, because each page mostly
     * repeats the one before, while a fresh radio from the tail brought 15 to 48.
     */
    private suspend fun appendSimilarSongs(generation: Int, force: Boolean): Boolean {
        val seed = queue.lastOrNull() ?: return false
        val page = radioFor(seed.id).getOrElse {
            Timber.w("Autoplay: no radio for ${seed.id}: ${it.message}")
            return false
        }
        // The queue was replaced while this was in flight; these belong to the old one
        if (generation != queueGeneration) return false
        // Checked again: the setting or the Listen Together role can change during the fetch
        if (!force && !autoplayWanted()) return false
        val songs = similarSongsToQueue(
            page = page,
            queued = queue.mapTo(HashSet()) { it.id },
            hideExplicit = PreferencesManager.preferences.value.hideExplicit,
            // A radio the user started is the queue itself, like an album, so its songs are theirs
            fromAutoplay = !radioStarted
        )
        if (songs.isEmpty()) return false
        queue.addAll(songs)
        // Into the unshuffle order as well, even while shuffled. Left out, turning shuffle off
        // would drop them, and with them the song playing if it was one.
        if (hasOrderToRestore()) originalQueue.addAll(songs)
        syncOriginalQueue()
        updateQueueState()
        Timber.d("Autoplay: queued ${songs.size} songs after ${seed.id}")
        return true
    }

    /**
     * Plays [songs] shuffled with songs like them mixed in, one after every [SMART_SHUFFLE_EVERY]
     * (issue #15), the way Spotify's Smart Shuffle does. The playlist starts at once: suggestions
     * come from YouTube's radio for a few of its songs, which takes up to four seconds, and are then
     * woven into what has not played yet. Each carries [SongInfo.suggested] so the queue can mark it.
     */
    suspend fun smartShuffle(songs: List<SongInfo>) {
        if (songs.isEmpty()) return
        playQueue(songs.shuffled())
        val generation = queueGeneration
        val pages = coroutineScope {
            songs.shuffled().take(SMART_SHUFFLE_SEEDS)
                .map { seed -> async { radioFor(seed.id).getOrNull().orEmpty() } }
                .awaitAll()
        }
        // The queue was replaced while the radios loaded; these belong to the old one
        if (generation != queueGeneration) return
        // Round robin across the radios, so the first suggestions do not all come from one seed
        val merged = (0 until (pages.maxOfOrNull { it.size } ?: 0)).flatMap { i -> pages.mapNotNull { it.getOrNull(i) } }
        val suggestions = similarSongsToQueue(
            page = merged,
            queued = queue.mapTo(HashSet()) { it.id },
            hideExplicit = PreferencesManager.preferences.value.hideExplicit,
            fromAutoplay = false
        ).map { it.copy(suggested = true) }
        val upcoming = queue.subList(currentIndex + 1, queue.size)
        val woven = interleaveSuggestions(upcoming.toList(), suggestions, SMART_SHUFFLE_EVERY)
        val added = suggestions.take(woven.size - upcoming.size)
        if (added.isEmpty()) return
        upcoming.clear()
        upcoming.addAll(woven)
        // Into the unshuffle order too, as autoplay does, or turning shuffle off would drop them
        if (hasOrderToRestore()) originalQueue.addAll(added)
        syncOriginalQueue()
        updateQueueState()
        Timber.d("Smart Shuffle: mixed ${added.size} suggestions into ${songs.size} songs")
    }

    /** Takes out the autoplay songs still to come. Ones already played stay, as history. */
    internal fun dropQueuedAutoplay() {
        val pending = queue.filterIndexed { index, song -> index > currentIndex && song.fromAutoplay }
        if (pending.isEmpty()) return
        queue.removeAll { song -> pending.any { it === song } }
        originalQueue.removeAll { song -> pending.any { it === song } }
        updateQueueState()
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
