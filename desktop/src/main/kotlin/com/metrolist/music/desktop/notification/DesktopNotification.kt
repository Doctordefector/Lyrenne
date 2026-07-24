package com.metrolist.music.desktop.notification

import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.settings.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * Manages the system tray icon for notifications and minimize-to-tray.
 * Provides a right-click context menu with Show/Exit actions.
 */
object DesktopNotification {
    private var trayIcon: TrayIcon? = null
    private var observeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Callback invoked when user clicks "Show Metrolist" in tray menu or double-clicks the icon. */
    var onShowWindow: (() -> Unit)? = null

    /** Callback invoked when user clicks "Exit" in tray menu. */
    var onExitApp: (() -> Unit)? = null

    /** Right-click on the tray icon, with screen coordinates for placing the panel. */
    var onTrayMenu: ((x: Int, y: Int) -> Unit)? = null

    fun initialize(player: DesktopPlayer) {
        if (!SystemTray.isSupported()) {
            Timber.w("System tray not supported — notifications and tray minimize disabled")
            return
        }

        try {
            val iconStream = Thread.currentThread().contextClassLoader
                .getResourceAsStream("icon.png")
                ?: DesktopNotification::class.java.getResourceAsStream("/icon.png")
            val image = if (iconStream != null) {
                javax.imageio.ImageIO.read(iconStream)
            } else {
                Timber.w("icon.png not found for notification tray icon")
                java.awt.Toolkit.getDefaultToolkit().createImage(ByteArray(0))
            }

            // Deliberately created WITHOUT a java.awt.PopupMenu. That class is a heavyweight
            // native Win32 menu — unthemeable, no icons, no custom fonts. Handling the
            // right-click ourselves lets the menu be rendered as ordinary Compose instead
            // (see TrayPanel.kt); AWT only delivers mouse events when no popup is attached.
            trayIcon = TrayIcon(image, "Metrolist").apply {
                isImageAutoSize = true
                addActionListener { onShowWindow?.invoke() } // Double-click on Windows
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mousePressed(e: java.awt.event.MouseEvent) = maybePopup(e)
                    override fun mouseReleased(e: java.awt.event.MouseEvent) = maybePopup(e)
                    private fun maybePopup(e: java.awt.event.MouseEvent) {
                        // isPopupTrigger fires on press or release depending on platform.
                        if (e.isPopupTrigger) onTrayMenu?.invoke(e.xOnScreen, e.yOnScreen)
                    }
                })
            }

            SystemTray.getSystemTray().add(trayIcon)
        } catch (e: Exception) {
            Timber.w("Failed to create tray icon: ${e.message}")
            return
        }

        // Watch for song changes to show notifications
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
        onShowWindow = null
        onExitApp = null
    }
}
