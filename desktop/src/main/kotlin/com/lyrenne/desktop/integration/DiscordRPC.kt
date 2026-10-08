package com.lyrenne.desktop.integration

import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.PreferencesManager
import kotlinx.coroutines.*
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

    private var pipe: IpcPipe? = null
    private var updateJob: Job? = null
    private var settingsJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var connected = false
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
    private val presenceMutex = Mutex()

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
                        startPresenceUpdates(player)
                    } else {
                        stopPresenceUpdates()
                    }
                }
        }
    }

    private fun startPresenceUpdates(player: DesktopPlayer) {
        updateJob?.cancel()
        updateJob = scope.launch {
            // collectLatest serializes sends and cancels an in-flight/pending update
            // when a newer state arrives, so pipe writes never overlap and a scrub
            // debounces to a single send. Presence updates on song change and on a
            // real seek — detected by the position jumping out of step with elapsed
            // wall time, which ignores the ±sub-second jitter of VLC's position that
            // made the previous absolute-threshold approach spam Discord's rate limit.
            player.state.collectLatest { state ->
                val song = state.currentSong
                // Pausing is not the same as having nothing loaded, and it used to be treated
                // that way: !isPlaying fell into the else below and wiped the presence outright,
                // so pausing looked to everyone else like Lyrenne had been closed. Discord keeps
                // whatever was last sent, so the paused track stays up until it is replaced or
                // the track is genuinely cleared.
                if (song != null && !state.isPlaying) {
                    if (lastSongId != song.id || lastWasPlaying) {
                        lastWasPlaying = false
                        schedulePausedPresence(player)
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
                        sendPresence(song, wall / 1000 - pos / 1000, state.duration)
                    } else if (seeked || durationCorrected) {
                        // The debounce MUST live outside this collectLatest block. Playback
                        // emits a new position every ~200ms, and collectLatest cancels the
                        // block on every emission — so an inline `delay(700)` was killed by
                        // the very next tick and the seek was never sent. By then the jump
                        // is no longer detectable, so the update was simply lost.
                        // Rewinding showed this every time; seeking forward only appeared
                        // to work because re-buffering paused emissions long enough for the
                        // delay to survive.
                        scheduleSeekPresence(player)
                    }
                } else {
                    // Reached only when there is genuinely no track loaded.
                    if (lastSongId != null) {
                        lastSongId = null
                        lastDuration = 0L
                        lastWasPlaying = false
                        clearPresence()
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
    private fun scheduleSeekPresence(player: DesktopPlayer) {
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(700)
            val state = player.state.value
            val song = state.currentSong ?: return@launch
            if (!state.isPlaying) return@launch
            sendPresence(song, System.currentTimeMillis() / 1000 - state.position / 1000, state.duration)
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
    private fun schedulePausedPresence(player: DesktopPlayer) {
        pauseJob?.cancel()
        pauseJob = scope.launch {
            delay(500)
            val state = player.state.value
            if (state.isPlaying) return@launch
            val song = state.currentSong ?: return@launch
            lastSongId = song.id
            lastDuration = state.duration
            sendPausedPresence(song)
        }
    }

    /** Serializes pipe writes — song-change and seek updates come from different coroutines. */
    private suspend fun sendPresence(song: SongInfo, startEpoch: Long, durationMs: Long) {
        presenceMutex.withLock { setPresence(song, startEpoch, durationMs) }
    }

    private suspend fun stopPresenceUpdates() {
        seekJob?.cancel()
        seekJob = null
        pauseJob?.cancel()
        pauseJob = null
        updateJob?.cancel()
        updateJob = null
        lastSongId = null
        lastDuration = 0L
        clearPresence()
        disconnect()
    }

    private suspend fun connect(): Boolean {
        if (connected && pipe != null) return true

        // Clean up any stale connection first
        disconnect()

        // Try pipes 0-9
        for (i in 0..9) {
            try {
                val raf = IpcPipe.open(i) ?: continue
                pipe = raf

                // Send handshake
                val handshake = """{"v":1,"client_id":"$APPLICATION_ID"}"""
                sendFrame(OP_HANDSHAKE, handshake)

                // Read response with timeout (should be READY event)
                val response = withTimeoutOrNull(5000) {
                    withContext(Dispatchers.IO) { readFrame() }
                }
                if (response != null) {
                    connected = true
                    Timber.i("Discord IPC connected on pipe $i")
                    return true
                }

                // No response — close and try next
                try { raf.close() } catch (_: Exception) {}
                pipe = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // cancellation must propagate, not be treated as a failure
            } catch (_: Exception) {
                // Try next pipe
                pipe = null
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

    private fun sendFrame(opcode: Int, json: String) {
        val raf = pipe ?: return
        try {
            val payload = json.toByteArray(Charsets.UTF_8)
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(opcode)
            header.putInt(payload.size)
            raf.write(header.array())
            raf.write(payload)
        } catch (e: Exception) {
            Timber.w("Discord sendFrame failed: ${e.message}")
            connected = false
            try { raf.close() } catch (_: Exception) {}
            pipe = null
            throw e
        }
    }

    private fun readFrame(): String? {
        val raf = pipe ?: return null
        return try {
            val header = ByteArray(8)
            raf.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val opcode = buf.getInt()
            val length = buf.getInt()

            if (length in 1 until 65536) {
                val payload = ByteArray(length)
                raf.readFully(payload)
                String(payload, Charsets.UTF_8)
            } else null
        } catch (e: Exception) {
            Timber.d("Discord readFrame failed: ${e.message}")
            connected = false
            try { raf.close() } catch (_: Exception) {}
            pipe = null
            null
        }
    }

    /**
     * Publish the paused view of [song]: same track, no timestamps.
     *
     * Timestamps are what make Discord animate. Leaving them in place while paused would leave a
     * progress bar advancing through a track that is not moving, which reads worse than showing
     * nothing. Dropping them freezes the entry, and the small badge says Paused so it is legible
     * rather than just stalled.
     */
    private suspend fun sendPausedPresence(song: SongInfo) {
        presenceMutex.withLock { setPresence(song, startEpoch = 0L, durationMs = 0L, paused = true) }
    }

    private suspend fun setPresence(
        song: SongInfo,
        startEpoch: Long,
        durationMs: Long,
        paused: Boolean = false
    ) {
            try {
                if (!connect()) return

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
                // Read response but don't block forever
                withTimeoutOrNull(2000) {
                    withContext(Dispatchers.IO) { readFrame() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // cancellation must propagate, not be treated as a failure
            } catch (e: Exception) {
                Timber.w("Discord presence update failed: ${e.message}")
                connected = false
                try { pipe?.close() } catch (_: Exception) {}
                pipe = null
            }
    }

    private suspend fun clearPresence() {
        if (!connected) return
            try {
                val clear = """{"cmd":"SET_ACTIVITY","args":{"pid":${ProcessHandle.current().pid()},"activity":null},"nonce":"${System.nanoTime()}"}"""
                sendFrame(OP_FRAME, clear)
                withTimeoutOrNull(2000) {
                    withContext(Dispatchers.IO) { readFrame() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // cancellation must propagate, not be treated as a failure
            } catch (_: Exception) {
                connected = false
                try { pipe?.close() } catch (_: Exception) {}
                pipe = null
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
                    val raf = RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw")
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
        settingsJob?.cancel()
        updateJob?.cancel()
        seekJob?.cancel()
        pauseJob?.cancel()
        lastSongId = null
        lastDuration = 0L
        lastWasPlaying = false
        disconnect()
        scope.cancel()
    }

    fun isConnected(): Boolean = connected
}
