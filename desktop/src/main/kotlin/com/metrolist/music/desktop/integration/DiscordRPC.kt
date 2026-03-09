package com.metrolist.music.desktop.integration

import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.settings.PreferencesManager
import timber.log.Timber
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

object DiscordRPC {
    private const val APPLICATION_ID = "1411019391843172514"
    private var rpc: KizzyRPC? = null
    private var updateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize(player: DesktopPlayer) {
        val token = PreferencesManager.preferences.value.discordToken ?: return
        if (!PreferencesManager.preferences.value.discordRpcEnabled) return

        try {
            rpc = KizzyRPC(
                token = token,
                os = "Windows",
                browser = "Discord Client",
                device = "Desktop"
            )

            // Watch player state and update presence
            updateJob = scope.launch {
                player.state.collectLatest { state ->
                    if (state.currentSong != null && state.isPlaying) {
                        updatePresence(state.currentSong)
                    } else {
                        try { rpc?.close() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w("Failed to initialize Discord RPC: ${e.message}")
        }
    }

    private suspend fun updatePresence(song: SongInfo) {
        try {
            val thumbnailUrl = song.thumbnailUrl?.replace("w60-h60", "w512-h512")

            rpc?.setActivity(
                name = "Metrolist",
                details = song.title,
                state = song.artist,
                largeImage = thumbnailUrl?.let { RpcImage.ExternalImage(it) },
                largeText = song.album ?: song.title,
                smallImage = null,
                smallText = "Metrolist",
                startTime = System.currentTimeMillis(),
                type = KizzyRPC.Type.LISTENING,
                applicationId = APPLICATION_ID,
                buttons = listOf(
                    "Listen on YouTube Music" to "https://music.youtube.com/watch?v=${song.id}"
                )
            )
        } catch (e: Exception) {
            Timber.w("Discord RPC update failed: ${e.message}")
        }
    }

    fun release() {
        updateJob?.cancel()
        val rpcRef = rpc
        rpc = null
        if (rpcRef != null) {
            // Use a daemon thread to avoid deadlock when called from coroutine/main thread
            Thread {
                try { runBlocking { rpcRef.close() } } catch (_: Exception) {}
            }.apply {
                isDaemon = true
                start()
                join(3000) // Wait max 3 seconds for graceful shutdown
            }
        }
    }

    fun isConnected(): Boolean = rpc?.isRpcRunning() == true
}
