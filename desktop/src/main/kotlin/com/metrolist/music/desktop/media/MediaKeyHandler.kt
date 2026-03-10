package com.metrolist.music.desktop.media

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.settings.PreferencesManager
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

    /**
     * Handle keyboard shortcuts from Compose's onPreviewKeyEvent.
     * Called from App.kt ONLY when textInputActive is false.
     * Returns true if the key event was consumed.
     */
    fun handleComposeKeyEvent(key: Key, isCtrl: Boolean, player: DesktopPlayer): Boolean {
        return when (key) {
            Key.Spacebar -> { player.togglePlayPause(); true }
            Key.DirectionRight -> {
                if (isCtrl) { scope.launch { player.playNext() } }
                else {
                    val pos = player.state.value.position
                    val dur = player.state.value.duration
                    player.seekTo((pos + 10000).coerceAtMost(dur))
                }
                true
            }
            Key.DirectionLeft -> {
                if (isCtrl) { scope.launch { player.playPrevious() } }
                else {
                    val pos = player.state.value.position
                    player.seekTo((pos - 10000).coerceAtLeast(0))
                }
                true
            }
            Key.P -> if (isCtrl) { player.togglePlayPause(); true } else false
            Key.S -> if (isCtrl) { player.toggleShuffle(); true } else false
            Key.R -> if (isCtrl) { player.toggleRepeat(); true } else false
            Key.DirectionUp -> if (isCtrl) {
                val current = PreferencesManager.preferences.value.volume
                val newVol = (current + 0.05f).coerceAtMost(1f)
                PreferencesManager.setVolume(newVol)
                player.setVolume(newVol); true
            } else false
            Key.DirectionDown -> if (isCtrl) {
                val current = PreferencesManager.preferences.value.volume
                val newVol = (current - 0.05f).coerceAtLeast(0f)
                PreferencesManager.setVolume(newVol)
                player.setVolume(newVol); true
            } else false
            Key.M -> {
                toggleMute(player); true
            }
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
 */
fun Modifier.suppressMediaKeys(): Modifier = this.onFocusChanged { state ->
    if (state.isFocused) {
        MediaKeyHandler.focusedTextFieldCount.incrementAndGet()
    } else {
        MediaKeyHandler.focusedTextFieldCount.decrementAndGet()
    }
}
