package com.lyrenne.desktop.media

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.type
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.settings.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles global media key events (play/pause, next, previous)
 * Uses AWT KeyboardFocusManager for cross-platform support
 */
object MediaKeyHandler {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var player: DesktopPlayer? = null
    private var keyDispatcher: KeyEventDispatcher? = null
    private var isInitialized = false

    /**
     * Tracks how many Compose text fields currently have focus.
     * When > 0, only hardware media keys are handled — all keyboard shortcuts
     * (space, Ctrl+P, Ctrl+S, etc.) are passed through to the text field.
     */
    internal val focusedTextFieldCount = AtomicInteger(0)

    /** True when any text field tagged with [suppressMediaKeys] currently has focus. */
    val textInputActive: Boolean get() = focusedTextFieldCount.get() > 0

    fun initialize(desktopPlayer: DesktopPlayer) {
        if (isInitialized) return

        player = desktopPlayer

        keyDispatcher = KeyEventDispatcher { event ->
            if (event.id == KeyEvent.KEY_PRESSED) {
                handleKeyPress(event)
            } else {
                false
            }
        }

        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(keyDispatcher)

        isInitialized = true
    }

    // Media key codes (Windows media key codes, not available in standard KeyEvent constants)
    private const val VK_MEDIA_NEXT_TRACK = 176
    private const val VK_MEDIA_PREV_TRACK = 177
    private const val VK_MEDIA_STOP = 178
    private const val VK_MEDIA_PLAY_PAUSE = 179

    /**
     * AWT dispatcher — ONLY handles hardware media keys.
     * All keyboard shortcuts (Space, M, arrows, Ctrl+combos) are handled
     * in Compose's onPreviewKeyEvent (App.kt) where focus state is always in sync.
     */
    private fun handleKeyPress(event: KeyEvent): Boolean {
        val player = player ?: return false

        return when (event.keyCode) {
            VK_MEDIA_PLAY_PAUSE -> { player.togglePlayPause(); true }
            VK_MEDIA_STOP -> { player.pause(); true }
            VK_MEDIA_NEXT_TRACK -> { scope.launch { player.playNext() }; true }
            VK_MEDIA_PREV_TRACK -> { scope.launch { player.playPrevious() }; true }
            else -> false
        }
    }

    /** Toggle mute with proper volume memory */
    fun toggleMute(player: DesktopPlayer) {
        val prefs = PreferencesManager.preferences.value
        if (prefs.isMuted) {
            // Unmute: restore saved volume
            val restored = prefs.volumeBeforeMute.coerceAtLeast(0.1f)
            PreferencesManager.setMuted(false)
            PreferencesManager.setVolume(restored)
            player.setVolume(restored)
        } else {
            // Mute: save current volume, set to 0
            PreferencesManager.setVolumeBeforeMute(prefs.volume)
            PreferencesManager.setMuted(true)
            PreferencesManager.setVolume(0f)
            player.setVolume(0f)
        }
    }

    fun release() {
        keyDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(it)
        }
        keyDispatcher = null
        player = null
        isInitialized = false
    }
}

/**
 * Modifier extension that suppresses media key shortcuts while a text field has focus.
 * Apply this to any OutlinedTextField / TextField modifier chain.
 *
 * Works by consuming conflicting bare-key events (Space, M) in the bubble phase.
 * In Compose Desktop, typing characters goes through KEY_TYPED / platform text input,
 * while onKeyEvent handles KEY_PRESSED — they're separate channels. Consuming
 * KEY_PRESSED here prevents bubbling to the parent's shortcut handler without
 * affecting text input.
 */
fun Modifier.suppressMediaKeys(): Modifier = composed {
    // Track this field's own contribution to the counter. onFocusChanged also fires once on
    // first composition with hasFocus=false — blindly decrementing there drove the counter
    // negative (one per text field on screen) and permanently disabled the typing guard.
    val counted = remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            // A field can be removed from the tree while still focused; release its count.
            if (counted.value) {
                counted.value = false
                MediaKeyHandler.focusedTextFieldCount.decrementAndGet()
            }
        }
    }

    onFocusChanged { state ->
        if (state.hasFocus != counted.value) {
            counted.value = state.hasFocus
            if (state.hasFocus) {
                MediaKeyHandler.focusedTextFieldCount.incrementAndGet()
            } else {
                MediaKeyHandler.focusedTextFieldCount.decrementAndGet()
            }
        }
    }
    .onKeyEvent { event ->
        // Consume bare shortcut keys so they don't bubble to the parent's onKeyEvent.
        // Only consume on KeyDown, and only bare keys (no Ctrl modifier).
        if (event.type == KeyEventType.KeyDown && !event.isCtrlPressed) {
            event.key == Key.Spacebar || event.key == Key.M
        } else false
    }
}
