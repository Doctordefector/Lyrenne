package com.metrolist.music.desktop.notification

import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.settings.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Toolkit

/**
 * Shows desktop notifications when the current song changes.
 * Uses AWT SystemTray for cross-platform support.
 */
object DesktopNotification {
    private var trayIcon: TrayIcon? = null
    private var observeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastNotifiedSongId: String? = null

    fun initialize(player: DesktopPlayer) {
        if (!SystemTray.isSupported()) {
            Timber.w("System tray not supported — notifications disabled")
            return
        }

        try {
            val image = Toolkit.getDefaultToolkit().createImage(
                DesktopNotification::class.java.getResource("/icon.png")
            ) ?: Toolkit.getDefaultToolkit().createImage(ByteArray(0))

            trayIcon = TrayIcon(image, "Metrolist").apply {
                isImageAutoSize = true
            }

            // Don't add to system tray — we only use it for popup messages
            // Adding to tray would show a persistent icon which is undesirable
            SystemTray.getSystemTray().add(trayIcon)
        } catch (e: Exception) {
            Timber.w("Failed to create tray icon: ${e.message}")
            return
        }

        // Watch for song changes
        observeJob = scope.launch {
            var previousSongId: String? = null
            player.state.collectLatest { state ->
                val currentSong = state.currentSong
                if (currentSong != null && currentSong.id != previousSongId && state.isPlaying) {
                    previousSongId = currentSong.id
                    if (PreferencesManager.preferences.value.notificationsEnabled) {
                        showNowPlaying(currentSong)
                    }
                }
            }
        }
    }

    private fun showNowPlaying(song: SongInfo) {
        try {
            trayIcon?.displayMessage(
                song.title,
                song.artist,
                TrayIcon.MessageType.NONE
            )
        } catch (e: Exception) {
            Timber.w("Failed to show notification: ${e.message}")
        }
    }

    fun release() {
        observeJob?.cancel()
        trayIcon?.let {
            try {
                SystemTray.getSystemTray().remove(it)
            } catch (_: Exception) {}
        }
        trayIcon = null
    }
}
