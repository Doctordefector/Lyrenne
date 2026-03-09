package com.metrolist.music.desktop.integration

import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.settings.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
            player.state.collectLatest { state ->
                if (state.currentSong != null && state.isPlaying) {
                    // Only update if song changed
                    if (state.currentSong.id != lastSongId) {
                        lastSongId = state.currentSong.id
                        setPresence(state.currentSong)
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

    private fun stopPresenceUpdates() {
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

    private fun setPresence(song: SongInfo) {
        scope.launch {
            try {
                if (!connect()) return@launch

                val title = escapeJson(song.title)
                val artist = escapeJson(song.artist)
                val album = escapeJson(song.album ?: song.title)
                val thumbnailUrl = song.thumbnailUrl
                    ?.replace("w60-h60", "w512-h512")
                    ?.replace("w120-h120", "w512-h512")
                    ?.let { escapeJson(it) }
                val ytUrl = escapeJson("https://music.youtube.com/watch?v=${song.id}")
                val now = System.currentTimeMillis() / 1000

                val activity = buildString {
                    append("""{"cmd":"SET_ACTIVITY","args":{"pid":${ProcessHandle.current().pid()},"activity":{""")
                    append(""""type":2,""") // LISTENING
                    append(""""details":"$title",""")
                    append(""""state":"$artist",""")
                    append(""""timestamps":{"start":$now},""")
                    append(""""assets":{""")
                    if (thumbnailUrl != null) {
                        append(""""large_image":"$thumbnailUrl",""")
                    }
                    append(""""large_text":"$album",""")
                    append(""""small_image":"https://raw.githubusercontent.com/Doctordefector/Metrolist-Desktop/main/desktop/src/main/resources/icon.png",""")
                    append(""""small_text":"Metrolist"""")
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
    }

    private fun clearPresence() {
        if (!connected) return
        scope.launch {
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
