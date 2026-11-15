package com.lyrenne.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.lyrenne.desktop.Platform

/**
 * The shortcut modifier per platform: Cmd on a Mac, Ctrl everywhere else, and the labels to match.
 *
 * One exception on the Mac: Cmd+Q is Quit there, everywhere, so the queue moves to Cmd+U (as in
 * "up next"). Ctrl on a Mac is not a fallback either: Ctrl+arrows belong to Mission Control.
 */
object ShortcutKeys {
    private val mac = Platform.isMac

    /** Whether the platform's shortcut modifier is held. */
    fun primary(event: KeyEvent): Boolean = if (mac) event.isMetaPressed else event.isCtrlPressed

    /** The key that, with [primary], toggles the queue. */
    val queueKey: Key = if (mac) Key.U else Key.Q

    /** Cmd+W on a Mac, which closes the window like its close button. Nothing elsewhere. */
    fun isCloseWindow(event: KeyEvent): Boolean =
        mac && event.type == KeyEventType.KeyDown && event.isMetaPressed && event.key == Key.W

    /** "Ctrl+S" or "⌘S". Arrow names become arrows on a Mac, the way its menus write them. */
    fun label(key: String): String =
        if (mac) "⌘" + (ARROWS[key] ?: key) else "Ctrl+$key"

    /** Label for the queue shortcut. */
    val queueLabel: String get() = label(if (mac) "U" else "Q")

    private val ARROWS = mapOf("Left" to "←", "Right" to "→", "Up" to "↑", "Down" to "↓")
}
