package com.lyrenne.desktop.ui.stage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.lyrenne.desktop.media.MediaKeyHandler
import com.lyrenne.desktop.rewind.RewindPeriod

/**
 * The main window's state, for Stage's full screen toggle. Provided by Main.kt; null where there
 * is no window (UiSnapshot, tests), and then the toggle is simply not offered.
 */
val LocalMainWindowState = staticCompositionLocalOf<WindowState?> { null }

/** Keyboard shortcut that opens and closes Stage. Listed in CLAUDE.md "Keyboard Shortcuts". */
val STAGE_SHORTCUT_LABEL: String get() = if (com.lyrenne.desktop.Platform.isMac) "⇧⌘F" else "Ctrl+Shift+F"

/**
 * Whether Stage or Rewind is up, as app-wide state: the player bar, the Command Palette, the
 * Stats screen and the window's key handler all open them, and [StageRewindHost] draws them.
 */
object StageMode {
    var isOpen by mutableStateOf(false)
        private set

    /** Set by the Stage screen: whether it put the window into full screen. */
    internal var fullscreenRequested by mutableStateOf(false)

    fun open() { isOpen = true }
    fun close() { isOpen = false; fullscreenRequested = false }
    fun toggle() = if (isOpen) close() else open()
    fun toggleFullscreen() { fullscreenRequested = !fullscreenRequested }
}

object RewindMode {
    /** The period being shown, or null when Rewind is closed. */
    var period by mutableStateOf<RewindPeriod?>(null)
        private set
    /** Asks the open story to step: -1 back, +1 forward. Consumed by the story. */
    internal var step by mutableStateOf(0)

    val isOpen get() = period != null
    fun open(period: RewindPeriod = RewindPeriod.thisYear(java.time.LocalDate.now())) { this.period = period }
    fun close() { period = null }
}

/**
 * Stage and Rewind keys, ahead of the app's own shortcuts (AppShortcuts.preview calls this
 * first). True when the key was used.
 *
 * - Ctrl+Shift+F opens or closes Stage. Plain Ctrl+F is search and Ctrl+S is shuffle, so it
 *   needs the Shift; "F" for full view, next to F11.
 * - F11 toggles full screen while Stage is up; Esc leaves Stage (and full screen with it).
 * - In Rewind, Left/Right and Space step through the cards, Esc closes.
 */
fun handleStageRewindKey(event: KeyEvent): Boolean {
    val typing = MediaKeyHandler.textInputActive
    if (com.lyrenne.desktop.ui.ShortcutKeys.primary(event) && event.isShiftPressed && event.key == Key.F && !typing) {
        RewindMode.close()
        StageMode.toggle()
        return true
    }
    if (RewindMode.isOpen) {
        return when (event.key) {
            Key.Escape -> { RewindMode.close(); true }
            Key.DirectionRight, Key.Spacebar, Key.Enter -> if (typing || event.isCtrlPressed) false else { RewindMode.step = 1; true }
            Key.DirectionLeft -> if (typing || event.isCtrlPressed) false else { RewindMode.step = -1; true }
            else -> false
        }
    }
    if (StageMode.isOpen) {
        return when (event.key) {
            Key.Escape -> { StageMode.close(); true }
            Key.F11 -> { StageMode.toggleFullscreen(); true }
            else -> false
        }
    }
    return false
}

/** Restores whatever the window was before Stage made it full screen. */
internal class FullscreenKeeper(private val state: WindowState?) {
    private var before: WindowPlacement? = null

    fun apply(fullscreen: Boolean) {
        val s = state ?: return
        if (fullscreen && s.placement != WindowPlacement.Fullscreen) {
            before = s.placement
            s.placement = WindowPlacement.Fullscreen
        } else if (!fullscreen && s.placement == WindowPlacement.Fullscreen && before != null) {
            s.placement = before ?: WindowPlacement.Floating
            before = null
        }
    }
}
