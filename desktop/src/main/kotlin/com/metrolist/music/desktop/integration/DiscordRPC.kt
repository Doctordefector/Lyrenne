package com.metrolist.music.desktop.integration

import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.settings.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Discord Rich Presence via local IPC (named pipe).
 *
 * Connects to the Discord client running on the same machine using
 * `\\.\pipe\discord-ipc-N` (Windows) or `/tmp/discord-ipc-N` (Unix).
 * No user token required — only the application ID.
 *
 * Protocol:
 *   Frame = [opcode: u32 LE] [length: u32 LE] [JSON payload]
 *   Opcodes: 0=HANDSHAKE, 1=FRAME, 2=CLOSE, 3=PING, 4=PONG
 */
object DiscordRPC {
    private const val APPLICATION_ID = "1411019391843172514"

    private var pipe: RandomAccessFile? = null
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
    private var seekJob: Job? = null
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
                if (song != null && state.isPlaying) {
                    val pos = state.position
                    val wall = System.currentTimeMillis()
                    val songChanged = song.id != lastSongId
                    val expectedPos = lastPosition + (wall - lastPositionWall)
                    val seeked = !songChanged && lastSongId != null &&
                        kotlin.math.abs(pos - expectedPos) > 3000
                    lastPosition = pos
                    lastPositionWall = wall

                    if (songChanged) {
                        lastSongId = song.id
                        sendPresence(song, wall / 1000 - pos / 1000, state.duration)
                    } else if (seeked) {
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
                    if (lastSongId != null) {
                        lastSongId = null
                        clearPresence()
                    }
                }
            }
        }
    }

    /**
     * Debounced seek update, running in [scope] so routine position ticks can't cancel it.
     * A further seek restarts the timer; once the user settles, the CURRENT state is read
     * and sent — so the anchor is right no matter how long the scrub took.
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

    /** Serializes pipe writes — song-change and seek updates come from different coroutines. */
    private suspend fun sendPresence(song: SongInfo, startEpoch: Long, durationMs: Long) {
        presenceMutex.withLock { setPresence(song, startEpoch, durationMs) }
    }

    private suspend fun stopPresenceUpdates() {
        seekJob?.cancel()
        seekJob = null
        updateJob?.cancel()
        updateJob = null
        lastSongId = null
        clearPresence()
        disconnect()
    }

    private suspend fun connect(): Boolean {
        if (connected && pipe != null) return true

        // Clean up any stale connection first
        disconnect()

        val os = System.getProperty("os.name").lowercase()
        val isWindows = os.contains("win")

        // Try pipes 0-9
        for (i in 0..9) {
            try {
                val pipePath = if (isWindows) {
                    "\\\\.\\pipe\\discord-ipc-$i"
                } else {
                    // Linux/macOS: check XDG_RUNTIME_DIR, TMPDIR, /tmp
                    val dirs = listOfNotNull(
                        System.getenv("XDG_RUNTIME_DIR"),
                        System.getenv("TMPDIR"),
                        "/tmp"
                    )
                    val dir = dirs.firstOrNull { java.io.File(it, "discord-ipc-$i").exists() }
                        ?: dirs.first()
                    "$dir/discord-ipc-$i"
                }

                val raf = RandomAccessFile(pipePath, "rw")
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

    private suspend fun setPresence(song: SongInfo, startEpoch: Long, durationMs: Long) {
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
                val timestamps = if (durationMs > 0) {
                    """{"start":$startEpoch,"end":${startEpoch + durationMs / 1000}}"""
                } else {
                    """{"start":$startEpoch}"""
                }

                val activity = buildString {
                    append("""{"cmd":"SET_ACTIVITY","args":{"pid":${ProcessHandle.current().pid()},"activity":{""")
                    append(""""type":2,""") // LISTENING
                    append(""""details":"$title",""")
                    append(""""state":"$artist",""")
                    append(""""timestamps":$timestamps,""")
                    append(""""assets":{""")
                    if (thumbnailUrl != null) {
                        append(""""large_image":"$thumbnailUrl",""")
                    }
                    append(""""large_text":"$album",""")
                    append(""""small_image":"https://raw.githubusercontent.com/Doctordefector/Lyrenne/main/desktop/src/main/resources/icon.png",""")
                    append(""""small_text":"Lyrenne"""")
                    append("""},""")
                    append(""""buttons":[{"label":"Listen on YouTube Music","url":"$ytUrl"}]""")
                    append("""}},"nonce":"${System.nanoTime()}"}""")
                }

                sendFrame(OP_FRAME, activity)
                // Read response but don't block forever
                withTimeoutOrNull(2000) {
                    withContext(Dispatchers.IO) { readFrame() }
                }
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
            } catch (_: Exception) {
                connected = false
                try { pipe?.close() } catch (_: Exception) {}
                pipe = null
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
        lastSongId = null
        disconnect()
        scope.cancel()
    }

    fun isConnected(): Boolean = connected
}
