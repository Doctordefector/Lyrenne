package com.lyrenne.desktop.mac

import com.lyrenne.desktop.Platform
import kotlinx.coroutines.flow.MutableSharedFlow
import timber.log.Timber
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener

/** Requests from outside the Compose tree that App acts on, such as the macOS Settings menu item. */
object AppRequests {
    val openSettings = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

/**
 * The macOS application menu and Dock.
 *
 * Java gives every Mac app a menu named after it with About, Settings and Quit. They do nothing
 * useful until handlers are set: Settings stays greyed out, and Quit (Cmd+Q, the Dock's Quit, or
 * logging out) calls System.exit straight away, which skips [onQuit] and with it the queue save.
 * The Dock icon is the Mac's way back to a closed window, so a click on it reopens Lyrenne.
 */
object MacIntegration {

    /** Before AWT starts: the name in the menu bar, and a title bar that follows dark mode. */
    fun configureEarly() {
        if (!Platform.isMac) return
        System.setProperty("apple.awt.application.name", "Lyrenne")
        System.setProperty("apple.awt.application.appearance", "system")
    }

    fun install(onQuit: () -> Unit, onShowWindow: () -> Unit) {
        if (!Platform.isMac) return
        runCatching {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                // No performQuit or cancelQuit: onQuit exits the process itself (within 3 s), and
                // macOS waits for that rather than cancelling a logout or shutdown over it.
                desktop.setQuitHandler { _, _ ->
                    Timber.i("Quit requested by macOS")
                    onQuit()
                }
            }
            if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
                desktop.setAboutHandler { showAbout() }
            }
            if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
                // Also gives the menu item its standard Cmd+, shortcut.
                desktop.setPreferencesHandler {
                    onShowWindow()
                    AppRequests.openSettings.tryEmit(Unit)
                }
            }
            desktop.addAppEventListener(AppReopenedListener { onShowWindow() })
        }.onFailure { Timber.w("macOS app menu handlers unavailable: ${it.message}") }
    }

    /** Brings Lyrenne to the front, so a window it shows (the menu bar panel) can take focus. */
    fun activate() {
        if (!Platform.isMac) return
        runCatching { Desktop.getDesktop().requestForeground(true) }
    }

    private fun showAbout() {
        val icon = runCatching {
            Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")?.use { javax.imageio.ImageIO.read(it) }
                ?.getScaledInstance(96, 96, java.awt.Image.SCALE_SMOOTH)?.let { javax.swing.ImageIcon(it) }
        }.getOrNull()
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "Lyrenne ${com.lyrenne.desktop.update.AutoUpdater.CURRENT_VERSION}\n" +
                "A YouTube Music player for your desktop.\n\n" +
                "Free and open source (GPL-3.0).\ngithub.com/Doctordefector/Lyrenne",
            "About Lyrenne",
            javax.swing.JOptionPane.PLAIN_MESSAGE,
            icon
        )
    }
}
