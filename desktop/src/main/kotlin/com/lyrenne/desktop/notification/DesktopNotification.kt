package com.lyrenne.desktop.notification

import com.lyrenne.desktop.Platform
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.PreferencesManager
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

    /**
     * True only once a tray icon was actually added. GNOME (Ubuntu, Fedora) has no AWT tray, and
     * hiding the window there leaves no way to bring it back, so minimize-to-tray checks this.
     */
    val trayActive: Boolean get() = trayIcon != null
    private var observeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Callback invoked when user clicks "Show Lyrenne" in tray menu or double-clicks the icon. */
    var onShowWindow: (() -> Unit)? = null

    /** Callback invoked when user clicks "Exit" in tray menu. */
    var onExitApp: (() -> Unit)? = null

    /** Right-click on the tray icon, with screen coordinates for placing the panel. */
    var onTrayMenu: ((x: Int, y: Int) -> Unit)? = null

    fun initialize(player: DesktopPlayer) {
        if (SystemTray.isSupported()) addTrayIcon()
        else Timber.w("System tray not supported — tray minimize disabled")
        // Without a tray, Linux still gets notifications through notify-send.
        if (trayIcon == null && !Platform.isLinux) return

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

    private fun addTrayIcon() {
        try {
            // icon-small.png, not icon.png: the tray renders at 16px, where the full mark's gold
            // ring is most of the pixels and the icon reads as a gold box instead of a lyre.
            val iconStream = Thread.currentThread().contextClassLoader
                .getResourceAsStream("icon-small.png")
                ?: DesktopNotification::class.java.getResourceAsStream("/icon-small.png")
                ?: Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
            val image = if (iconStream != null) {
                javax.imageio.ImageIO.read(iconStream)
            } else {
                Timber.w("no tray icon artwork found on the classpath")
                java.awt.Toolkit.getDefaultToolkit().createImage(ByteArray(0))
            }

            // Deliberately created WITHOUT a java.awt.PopupMenu. That class is a heavyweight
            // native Win32 menu — unthemeable, no icons, no custom fonts. Handling the
            // right-click ourselves lets the menu be rendered as ordinary Compose instead
            // (see TrayPanel.kt); AWT only delivers mouse events when no popup is attached.
            trayIcon = TrayIcon(image, "Lyrenne").apply {
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
            trayIcon = null
        }
    }

    private fun showNowPlaying(song: SongInfo) {
        try {
            val icon = trayIcon
            if (icon != null) icon.displayMessage(song.title, song.artist, TrayIcon.MessageType.NONE)
            else notifySend(song.title, song.artist)
        } catch (e: Exception) {
            Timber.w("Failed to show notification: ${e.message}")
        }
    }

    /** Linux without a tray: notify-send ships with libnotify on virtually every desktop. */
    private fun notifySend(title: String, message: String) {
        if (!Platform.isLinux) return
        runCatching {
            ProcessBuilder("notify-send", "--app-name=Lyrenne", "--icon=lyrenne", title, message)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }.onFailure { Timber.w("notify-send unavailable: ${it.message}") }
    }

    /**
     * Show a tray balloon, or notify-send on Linux without a tray. Silently does nothing
     * otherwise, so callers do not have to care.
     */
    fun notify(title: String, message: String) {
        try {
            val icon = trayIcon
            if (icon != null) icon.displayMessage(title, message, TrayIcon.MessageType.INFO)
            else notifySend(title, message)
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
