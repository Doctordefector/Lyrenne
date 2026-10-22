package com.lyrenne.desktop.integration

import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.PlaybackState
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import com.lyrenne.desktop.Platform
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Discord Rich Presence via local IPC (named pipe).
 *
 * Connects to the Discord client running on the same machine using
 * `\\.\pipe\discord-ipc-N` (Windows) or a Unix socket `discord-ipc-N` under `$XDG_RUNTIME_DIR`
 * (native, Flatpak, Snap), `$TMPDIR` or `/tmp`.
 * No user token required — only the application ID.
 *
 * Protocol:
 *   Frame = [opcode: u32 LE] [length: u32 LE] [JSON payload]
 *   Opcodes: 0=HANDSHAKE, 1=FRAME, 2=CLOSE, 3=PING, 4=PONG
 */
object DiscordRPC {
    /**
     * The Discord application this presence is published under.
     *
     * **The name Discord shows above the presence is the application's name, not anything sent
     * from here.** Until 2.9.7 this was 1411019391843172514, which came across with the port and
     * belongs to upstream's Android app: every Lyrenne user's Discord was announcing them as
     * running Metrolist, under an application this project does not control and could not rename.
     *
     * This is Lyrenne's own application. If it ever needs replacing, the name and the art assets
     * are managed at <https://discord.com/developers/applications>; neither can be set from here.
     */
    private const val APPLICATION_ID = "1532842629308354801"

    /**
     * Art asset key for the small badge, uploaded under Rich Presence → Art Assets on the
     * application above.
     *
     * Deliberately a key, not a URL. This previously pointed at a raw.githubusercontent.com link;
     * Discord does not reliably render arbitrary external images in activity assets, which is why
     * the badge came up blank. Assets uploaded to the application and referenced by name always
     * render. If no asset with this key exists the badge is simply omitted, same as today.
     */
    private const val SMALL_IMAGE_KEY = "lyrenne"

    /** How long Discord gets to answer the handshake, and then each update. */
    private const val HANDSHAKE_TIMEOUT_MS = 5000L
    private const val REPLY_TIMEOUT_MS = 2000L

    /** Retry backoff after a failed delivery, doubling from the first to the last. */
    internal var retryFirstMs = 5_000L
    internal var retryMaxMs = 60_000L

    private var pipe: IpcPipe? = null
    private var updateJob: Job? = null
    private var settingsJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var connected = false
    // Off once updates are switched off. Checked under the lock, so a send that was already
    // waiting for it cannot reconnect after the presence has been cleared.
    @Volatile
    private var enabled = false
    // Set while Discord is known to be showing something out of date because a send failed.
    @Volatile
    private var deliveryFailed = false
    private var lastSongId: String? = null
    // Last observed position + the wall-clock time we observed it — used to tell a real
    // seek (position jumps out of step with elapsed time) from normal playback drift.
    private var lastPosition = 0L
    private var lastPositionWall = 0L
    private var lastDuration = 0L
    // Whether the presence currently published to Discord is the playing one or the paused one.
    // The two differ by their timestamps, so a transition either way needs a re-send.
    private var lastWasPlaying = false
    private var seekJob: Job? = null
    private var pauseJob: Job? = null
    private var retryJob: Job? = null
    private val presenceMutex = Mutex()

    /** Where Windows pipe [index] lives. Tests point it at a fake Discord. */
    internal var windowsPipePathFor: (index: Int) -> String = { i -> "\\\\.\\pipe\\discord-ipc-$i" }

    // IPC opcodes
    private const val OP_HANDSHAKE = 0
    private const val OP_FRAME = 1
    private const val OP_CLOSE = 2

    fun initialize(player: DesktopPlayer) {
        // Watch settings changes to connect/disconnect dynamically
        settingsJob?.cancel()
        settingsJob = scope.launch {
            PreferencesManager.preferences
                .map { it.discordRpcEnabled }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    if (enabled) {
                        startPresenceUpdates(player.state)
                    } else {
                        stopPresenceUpdates()
                    }
                }
        }
    }

    internal fun startPresenceUpdates(playerState: StateFlow<PlaybackState>) {
        enabled = true
        updateJob?.cancel()
        updateJob = scope.launch {
            // collectLatest handles one state at a time. A send in flight is not cancelled by the
            // next state (see publish), it is waited for, so pipe writes never overlap, and a
            // scrub debounces to a single send. Presence updates on song change and on a
            // real seek — detected by the position jumping out of step with elapsed
            // wall time, which ignores the ±sub-second jitter of VLC's position that
            // made the previous absolute-threshold approach spam Discord's rate limit.
            playerState.collectLatest { state ->
                val song = state.currentSong
                // Pausing is not the same as having nothing loaded, and it used to be treated
                // that way: !isPlaying fell into the else below and wiped the presence outright,
                // so pausing looked to everyone else like Lyrenne had been closed. Discord keeps
                // whatever was last sent, so the paused track stays up until it is replaced or
                // the track is genuinely cleared.
                if (song != null && !state.isPlaying) {
                    if (lastSongId != song.id || lastWasPlaying) {
                        lastWasPlaying = false
                        schedulePausedPresence(playerState)
                    }
                    return@collectLatest
                }

                if (song != null && state.isPlaying) {
                    // Playback resumed (or never really stopped), so drop any pending pause.
                    pauseJob?.cancel()
                    val pos = state.position
                    val wall = System.currentTimeMillis()
                    // Resuming has to re-send too: the paused presence carries no timestamps, so
                    // without this the progress bar never comes back until the track changes.
                    val songChanged = song.id != lastSongId || !lastWasPlaying
                    lastWasPlaying = true
                    val expectedPos = lastPosition + (wall - lastPositionWall)
                    val seeked = !songChanged && lastSongId != null &&
                        kotlin.math.abs(pos - expectedPos) > 3000
                    lastPosition = pos
                    lastPositionWall = wall

                    // VLC reports the real length asynchronously, so the value present at song
                    // change can still be a metadata estimate or, when metadata was missing,
                    // zero. Correcting it has to reach Discord, otherwise the progress bar stays
                    // wrong for the whole track. This is NOT the banned position-tick re-send:
                    // lengthChanged fires about once per track, and the send is debounced below.
                    val durationCorrected = !songChanged && lastSongId != null &&
                        state.duration > 0 && state.duration != lastDuration
                    lastDuration = state.duration

                    if (songChanged) {
                        lastSongId = song.id
                        publish(playerState)
                    } else if (seeked || durationCorrected) {
                        // The debounce MUST live outside this collectLatest block. Playback
                        // emits a new position every ~200ms, and collectLatest cancels the
                        // block on every emission — so an inline `delay(700)` was killed by
                        // the very next tick and the seek was never sent. By then the jump
                        // is no longer detectable, so the update was simply lost.
                        // Rewinding showed this every time; seeking forward only appeared
                        // to work because re-buffering paused emissions long enough for the
                        // delay to survive.
                        scheduleSeekPresence(playerState)
                    }
                } else {
                    // Reached only when there is genuinely no track loaded.
                    if (lastSongId != null) {
                        lastSongId = null
                        lastDuration = 0L
                        lastWasPlaying = false
                        publish(playerState)
                    }
                }
            }
        }
    }

    /**
     * Debounced presence resend, running in [scope] so routine position ticks can't cancel it.
     * Triggered by a seek or by VLC correcting the track length. A further trigger restarts the
     * timer; once things settle the CURRENT state is read and sent, so the anchor is right no
     * matter how long the scrub took or how late the length arrived.
     */
    private fun scheduleSeekPresence(playerState: StateFlow<PlaybackState>) {
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(700)
            if (!playerState.value.isPlaying) return@launch
            publish(playerState)
        }
    }

    /**
     * Debounced paused presence.
     *
     * VLC reports `stopped` between tracks with the song still loaded, so an undebounced version
     * would publish a paused entry and then immediately a playing one on every single skip. That
     * is two pipe writes per track change, and rapid skipping is precisely what tripped Discord's
     * activity rate limit before. Waiting out the gap collapses a skip back to one write, while a
     * genuine pause is still reflected within half a second.
     */
    private fun schedulePausedPresence(playerState: StateFlow<PlaybackState>) {
        pauseJob?.cancel()
        pauseJob = scope.launch {
            delay(500)
            val state = playerState.value
            if (state.isPlaying) return@launch
            val song = state.currentSong ?: return@launch
            lastSongId = song.id
            lastDuration = state.duration
            publish(playerState)
        }
    }

    /**
     * Publish what the player is doing right now: the playing view, the paused view, or nothing
     * when no track is loaded. Every send goes through here. The state is read inside the lock, so
     * a send that queued behind another cannot publish something older than what went out first.
     *
     * Not cancellable, deliberately. The collector calls this inline, and collectLatest cancels it
     * on the next position tick, 200 ms later. A send cancelled while waiting for Discord's reply
     * used to look like a broken pipe: the connection was closed, Discord drops an activity when
     * its connection closes, and the presence vanished for the rest of the track. Every wait in
     * here is bounded (see [awaitReply]), so holding off cancellation costs seconds at worst.
     *
     * A failed send leaves [deliveryFailed] set and starts [retryUntilDelivered]. Without that,
     * a send that found Discord closed or restarted was lost until the next track.
     */
    private suspend fun publish(playerState: StateFlow<PlaybackState>) = withContext(NonCancellable) {
        presenceMutex.withLock {
            if (!enabled) return@withLock
            val state = playerState.value
            val song = state.currentSong
            val delivered = when {
                song == null -> { clearPresence(); true }
                state.isPlaying -> setPresence(
                    song,
                    System.currentTimeMillis() / 1000 - state.position / 1000,
                    state.duration
                )
                // The paused view: same track, no timestamps. Timestamps are what make Discord
                // animate. Leaving them in place while paused would leave a progress bar advancing
                // through a track that is not moving, which reads worse than showing nothing.
                // Dropping them freezes the entry, and the small badge says Paused so it is
                // legible rather than just stalled.
                else -> setPresence(song, startEpoch = 0L, durationMs = 0L, paused = true)
            }
            deliveryFailed = !delivered
            if (!delivered) retryUntilDelivered(playerState)
        }
    }

    /**
     * Keeps trying to deliver the current presence after a failed send, backing off from
     * [retryFirstMs] to [retryMaxMs]. Stops at the first delivery, including one made by an
     * ordinary update in the meantime.
     *
     * This is not the banned periodic resend (rule 6 in AGENTS.md). Nothing is written while
     * delivery works, and while Discord is closed every attempt fails at opening the pipe, before
     * anything reaches Discord. Each retry sends one update, the one the failed send was carrying.
     */
    private fun retryUntilDelivered(playerState: StateFlow<PlaybackState>) {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            var wait = retryFirstMs
            while (deliveryFailed) {
                delay(wait)
                if (deliveryFailed) publish(playerState)
                wait = (wait * 2).coerceAtMost(retryMaxMs)
            }
        }
    }

    internal suspend fun stopPresenceUpdates() {
        enabled = false
        seekJob?.cancel()
        seekJob = null
        pauseJob?.cancel()
        pauseJob = null
        retryJob?.cancel()
        retryJob = null
        deliveryFailed = false
        updateJob?.cancel()
        updateJob = null
        lastSongId = null
        lastDuration = 0L
        // Under the lock, so the CLOSE frame cannot land in the middle of a send in flight
        withContext(NonCancellable) {
            presenceMutex.withLock {
                clearPresence()
                disconnect()
            }
        }
    }

    /** Caller holds [presenceMutex]. */
    private suspend fun connect(): Boolean {
        if (connected && pipe != null) return true

        // Clean up any stale connection first
        dropConnection()

        // Try pipes 0-9
        for (i in 0..9) {
            val raf = try {
                IpcPipe.open(i)
            } catch (_: Exception) {
                null
            } ?: continue // No pipe at this index
            pipe = raf
            try {
                val handshake = """{"v":1,"client_id":"$APPLICATION_ID"}"""
                sendFrame(OP_HANDSHAKE, handshake)
                awaitReply(HANDSHAKE_TIMEOUT_MS) // The READY event
                connected = true
                Timber.i("Discord IPC connected on pipe $i")
                return true
            } catch (e: CancellationException) {
                throw e // cancellation must propagate, not be treated as a failure
            } catch (e: Exception) {
                // No answer on this one: close it and try the next
                Timber.d("Discord IPC pipe $i: ${e.message}")
                dropConnection()
            }
        }

        Timber.d("Discord IPC: no pipe available (Discord not running?)")
        return false
    }

    private fun disconnect() {
        try {
            if (connected) {
                sendFrame(OP_CLOSE, "{}")
            }
        } catch (_: Exception) {}

        try { pipe?.close() } catch (_: Exception) {}
        pipe = null
        connected = false
    }

    /**
     * Forget the current connection, and close it off the calling thread: closing a pipe handle
     * can block behind a read still pending on it (see [awaitReply]), which is exactly the state
     * a timed-out connection is in.
     */
    private fun dropConnection() {
        val raf = pipe
        pipe = null
        connected = false
        if (raf != null) scope.launch { try { raf.close() } catch (_: Exception) {} }
    }

    private fun sendFrame(opcode: Int, json: String) {
        val raf = pipe ?: throw IOException("Discord IPC not connected")
        val payload = json.toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(opcode)
        header.putInt(payload.size)
        raf.write(header.array())
        raf.write(payload)
    }

    /**
     * Wait at most [timeoutMs] for Discord's answer to the frame just sent, or throw.
     *
     * The read is a blocking native call and nothing can interrupt it. It used to run in
     * withContext under withTimeoutOrNull, which never fired: withContext waits for its block to
     * return before it honours cancellation, so a Discord that stopped answering held the lock,
     * and with it every later update, forever. The read now runs detached and only the wait for it
     * is bounded. On a timeout the caller drops the connection, and the stuck read ends whenever
     * its handle does.
     */
    private suspend fun awaitReply(timeoutMs: Long): String {
        val raf = pipe ?: throw IOException("Discord IPC not connected")
        val reply = scope.async { readFrame(raf) }
        return withTimeoutOrNull(timeoutMs) { reply.await() }
            ?: throw IOException("Discord did not answer within $timeoutMs ms")
    }

    /** One frame's payload. Throws on a broken pipe or a header that cannot be a real frame. */
    private fun readFrame(raf: IpcPipe): String {
        val header = ByteArray(8)
        raf.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.getInt() // opcode
        val length = buf.getInt()
        if (length !in 0 until 65536) throw IOException("Discord frame length $length")
        val payload = ByteArray(length)
        raf.readFully(payload)
        return String(payload, Charsets.UTF_8)
    }

    /** Caller holds [presenceMutex]. True once Discord has answered the update. */
    private suspend fun setPresence(
        song: SongInfo,
        startEpoch: Long,
        durationMs: Long,
        paused: Boolean = false
    ): Boolean {
            try {
                if (!connect()) return false

                val title = escapeJson(song.title)
                val artist = escapeJson(song.artist)
                val album = escapeJson(song.album ?: song.title)
                val thumbnailUrl = song.thumbnailUrl
                    ?.replace("w60-h60", "w512-h512")
                    ?.replace("w120-h120", "w512-h512")
                    ?.let { escapeJson(it) }
                val ytUrl = escapeJson("https://music.youtube.com/watch?v=${song.id}")
                // With an end timestamp Discord renders a progress bar instead of a count-up
                val timestamps = when {
                    paused -> null
                    durationMs > 0 -> """{"start":$startEpoch,"end":${startEpoch + durationMs / 1000}}"""
                    else -> """{"start":$startEpoch}"""
                }

                val activity = buildString {
                    append("""{"cmd":"SET_ACTIVITY","args":{"pid":${ProcessHandle.current().pid()},"activity":{""")
                    append(""""type":2,""") // LISTENING
                    append(""""details":"$title",""")
                    append(""""state":"$artist",""")
                    if (timestamps != null) append(""""timestamps":$timestamps,""")
                    append(""""assets":{""")
                    if (thumbnailUrl != null) {
                        append(""""large_image":"$thumbnailUrl",""")
                    }
                    append(""""large_text":"$album",""")
                    append(""""small_image":"$SMALL_IMAGE_KEY",""")
                    append(""""small_text":"${if (paused) "Paused" else "Lyrenne"}"""")
                    append("""},""")
                    append(""""buttons":[{"label":"Listen on YouTube Music","url":"$ytUrl"}]""")
                    append("""}},"nonce":"${System.nanoTime()}"}""")
                }

                sendFrame(OP_FRAME, activity)
                awaitReply(REPLY_TIMEOUT_MS)
                return true
            } catch (e: CancellationException) {
                throw e // cancellation must propagate, not be treated as a failure
            } catch (e: Exception) {
                Timber.w("Discord presence update failed: ${e.message}")
                dropConnection()
                return false
            }
    }

    /**
     * Caller holds [presenceMutex]. A failure needs no retry: it drops the connection, and Discord
     * clears an activity when its connection closes.
     */
    private suspend fun clearPresence() {
        if (!connected) return
            try {
                val clear = """{"cmd":"SET_ACTIVITY","args":{"pid":${ProcessHandle.current().pid()},"activity":null},"nonce":"${System.nanoTime()}"}"""
                sendFrame(OP_FRAME, clear)
                awaitReply(REPLY_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e // cancellation must propagate, not be treated as a failure
            } catch (_: Exception) {
                dropConnection()
            }
    }

    /**
     * Discord's IPC endpoint: a named pipe on Windows (which RandomAccessFile opens), a Unix domain
     * socket elsewhere (which it cannot, so that goes through SocketChannel). Raw channel reads and
     * writes rather than streams: a timed-out read is still blocked on an IO thread when the next
     * write arrives, and the channel's own read and write locks let both proceed.
     */
    private class IpcPipe(
        val write: (ByteArray) -> Unit,
        val readFully: (ByteArray) -> Unit,
        private val onClose: () -> Unit
    ) : java.io.Closeable {
        override fun close() = onClose()

        companion object {
            fun open(i: Int): IpcPipe? {
                if (Platform.isWindows) {
                    val raf = RandomAccessFile(windowsPipePathFor(i), "rw")
                    return IpcPipe({ raf.write(it) }, { raf.readFully(it) }, { raf.close() })
                }
                val runtime = System.getenv("XDG_RUNTIME_DIR")
                val dirs = listOfNotNull(
                    runtime,
                    runtime?.let { "$it/app/com.discordapp.Discord" }, // Flatpak Discord
                    runtime?.let { "$it/snap.discord" },               // Snap Discord
                    System.getenv("TMPDIR"),
                    "/tmp"
                )
                val socket = dirs.map { File(it, "discord-ipc-$i") }.firstOrNull { it.exists() } ?: return null
                val ch = SocketChannel.open(UnixDomainSocketAddress.of(socket.toPath()))
                return IpcPipe(
                    { bytes -> val buf = ByteBuffer.wrap(bytes); while (buf.hasRemaining()) ch.write(buf) },
                    { bytes ->
                        val buf = ByteBuffer.wrap(bytes)
                        while (buf.hasRemaining()) if (ch.read(buf) < 0) throw java.io.EOFException()
                    },
                    { ch.close() }
                )
            }
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    fun release() {
        enabled = false
        settingsJob?.cancel()
        updateJob?.cancel()
        seekJob?.cancel()
        pauseJob?.cancel()
        retryJob?.cancel()
        lastSongId = null
        lastDuration = 0L
        lastWasPlaying = false
        disconnect()
        scope.cancel()
    }

    fun isConnected(): Boolean = connected
}
