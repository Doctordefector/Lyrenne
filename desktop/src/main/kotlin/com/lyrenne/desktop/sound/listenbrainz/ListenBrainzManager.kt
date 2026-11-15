package com.lyrenne.desktop.sound.listenbrainz

import com.lyrenne.desktop.AppPaths
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.sound.OkHttpTransport
import com.lyrenne.desktop.update.AutoUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Songs that are podcast episodes, marked where episodes become queue entries (the podcast page and
 * search). In memory only, so an episode restored from a saved queue is not recognised; ListenBrainz
 * is a record of music, and MusicBrainz has no podcast entities to match an episode to.
 */
object PodcastEpisodes {
    private val ids = ConcurrentHashMap.newKeySet<String>()
    fun mark(id: String) { ids += id }
    fun contains(id: String) = id in ids
}

/**
 * ListenBrainz scrobbling, beside Last.fm: "playing now" when a song starts, a listen once half of
 * it (or four minutes) has actually been heard. See [ScrobbleTracker] for the counting and
 * [ListenQueue] for why no listen is lost to the network.
 *
 * On while a token is saved: pasting one is the switch. Not sent: podcast episodes, and anything
 * while "Pause listen history" is on, which is the user saying "don't keep a record of this".
 */
object ListenBrainzManager {
    data class Status(val pending: Int = 0, val problem: String? = null)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val api = ListenBrainzApi(OkHttpTransport)
    private val queue by lazy { ListenQueue(File(AppPaths.dataDir, "listenbrainz-queue.json")) }
    private val sender by lazy { ListenSender(api, queue, AutoUpdater.CURRENT_VERSION) }
    private val flushLock = Mutex()
    private var retryJob: Job? = null
    private var failures = 0
    /** The token ListenBrainz last refused. Nothing is sent with it again until it changes. */
    @Volatile private var refusedToken: String? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val token: String? get() = PreferencesManager.preferences.value.listenBrainzToken?.takeIf { it.isNotBlank() }

    fun initialize(player: DesktopPlayer) {
        scope.launch {
            _status.value = Status(pending = queue.size)
            // Whatever the last run could not deliver
            flushSoon()
            val tracker = ScrobbleTracker()
            player.state.collect { s ->
                val events = tracker.onState(s.currentSong, s.isPlaying, s.position, s.duration, System.currentTimeMillis())
                events.forEach(::onEvent)
            }
        }
        // A new token gets a fresh chance, and sends what waited for it
        scope.launch {
            PreferencesManager.preferences.map { it.listenBrainzToken }.distinctUntilChanged().drop(1).collect {
                refusedToken = null
                failures = 0
                flushSoon()
            }
        }
    }

    private fun onEvent(event: ScrobbleTracker.Event) {
        val token = token ?: return
        val song = when (event) {
            is ScrobbleTracker.Event.NowPlaying -> event.song
            is ScrobbleTracker.Event.Listen -> event.song
        }
        if (PreferencesManager.preferences.value.pauseListenHistory || PodcastEpisodes.contains(song.id)) return
        val listen = PendingListen(
            listenedAt = (event as? ScrobbleTracker.Event.Listen)?.listenedAtSec ?: (System.currentTimeMillis() / 1000),
            artist = song.artist,
            track = song.title,
            release = song.album,
            durationMs = when (event) {
                is ScrobbleTracker.Event.NowPlaying -> event.durationMs
                is ScrobbleTracker.Event.Listen -> event.durationMs
            }.takeIf { it > 0 },
            videoId = song.id
        )
        when (event) {
            is ScrobbleTracker.Event.NowPlaying -> scope.launch {
                if (token == refusedToken) return@launch
                // Best effort and not queued: a "now playing" that arrives late is wrong, not late.
                val r = api.submit(token, ListenType.PLAYING_NOW, listOf(listen), AutoUpdater.CURRENT_VERSION)
                if (r != SubmitOutcome.Accepted) Timber.d("ListenBrainz playing_now not sent: $r")
            }
            is ScrobbleTracker.Event.Listen -> {
                // On disk before the first attempt, so it survives anything that follows
                queue.add(listen)
                Timber.i("ListenBrainz: listen queued, ${song.artist} - ${song.title}")
                flushSoon()
            }
        }
    }

    /** Sends the queue now, unless a retry is already waiting its turn. */
    private fun flushSoon(force: Boolean = false) {
        if (!force && retryJob?.isActive == true) {
            _status.value = Status(queue.size, _status.value.problem)
            return
        }
        retryJob?.cancel()
        retryJob = scope.launch { flushNow() }
    }

    private suspend fun flushNow() {
        val token = token
        if (token == null || queue.size == 0) {
            _status.value = Status(queue.size)
            return
        }
        if (token == refusedToken) {
            _status.value = Status(queue.size, "ListenBrainz no longer accepts your token. Paste a new one.")
            return
        }
        val result = flushLock.withLock { withContext(Dispatchers.IO) { sender.flush(token) } }
        when (result) {
            ListenSender.Result.Done -> {
                failures = 0
                _status.value = Status(queue.size)
            }
            ListenSender.Result.Unauthorized -> {
                refusedToken = token
                _status.value = Status(queue.size, "ListenBrainz no longer accepts your token. Paste a new one.")
                Timber.w("ListenBrainz refused the token; ${queue.size} listens wait for a new one")
            }
            is ListenSender.Result.Wait -> {
                failures++
                val wait = retryBackoffMs(failures, result.retryAfterMs)
                _status.value = Status(queue.size, "Couldn't reach ListenBrainz, trying again in ${wait / 1000} s")
                Timber.i("ListenBrainz: ${result.reason}, ${queue.size} waiting, retry in ${wait / 1000} s")
                retryJob = scope.launch {
                    delay(wait)
                    flushNow()
                }
            }
        }
    }

    /** For Settings: checks a pasted token and says whose it is. */
    suspend fun validate(token: String): TokenCheck = withContext(Dispatchers.IO) { api.validateToken(token) }

    /** For Settings: try the waiting listens now instead of at the next retry. */
    fun retryNow() = flushSoon(force = true)

    fun release() {
        retryJob?.cancel()
    }
}
