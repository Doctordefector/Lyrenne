package com.metrolist.music.desktop.media

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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

    private fun handleKeyPress(event: KeyEvent): Boolean {
        val player = player ?: return false

        return when (event.keyCode) {
            // Media play/pause key
            VK_MEDIA_PLAY_PAUSE -> {
                player.togglePlayPause()
                true
            }
            // Media stop
            VK_MEDIA_STOP -> {
                player.pause()
                true
            }
            // Media next track
            VK_MEDIA_NEXT_TRACK -> {
                scope.launch { player.playNext() }
                true
            }
            // Media previous track
            VK_MEDIA_PREV_TRACK -> {
                scope.launch { player.playPrevious() }
                true
            }
            // All shortcuts below are suppressed when a text field is focused
            else -> {
                if (textInputActive) return false
                when (event.keyCode) {
                    // Space bar for play/pause
                    KeyEvent.VK_SPACE -> {
                        player.togglePlayPause()
                        true
                    }
                    // Right arrow: Ctrl = next track, plain = seek +10s
                    KeyEvent.VK_RIGHT -> {
                        if (event.isControlDown || event.isMetaDown) {
                            scope.launch { player.playNext() }
                            true
                        } else {
                            val pos = player.state.value.position
                            val dur = player.state.value.duration
                            player.seekTo((pos + 10000).coerceAtMost(dur))
                            true
                        }
                    }
                    // Left arrow: Ctrl = previous track, plain = seek -10s
                    KeyEvent.VK_LEFT -> {
                        if (event.isControlDown || event.isMetaDown) {
                            scope.launch { player.playPrevious() }
                            true
                        } else {
                            val pos = player.state.value.position
                            player.seekTo((pos - 10000).coerceAtLeast(0))
                            true
                        }
                    }
                    // Ctrl+P for play/pause
                    KeyEvent.VK_P -> {
                        if (event.isControlDown || event.isMetaDown) {
                            player.togglePlayPause()
                            true
                        } else false
                    }
                    // Ctrl+S for shuffle toggle
                    KeyEvent.VK_S -> {
                        if (event.isControlDown || event.isMetaDown) {
                            player.toggleShuffle()
                            true
                        } else false
                    }
                    // Ctrl+R for repeat toggle
                    KeyEvent.VK_R -> {
                        if (event.isControlDown || event.isMetaDown) {
                            player.toggleRepeat()
                            true
                        } else false
                    }
                    // Ctrl+Up = volume up
                    KeyEvent.VK_UP -> {
                        if (event.isControlDown || event.isMetaDown) {
                            val current = PreferencesManager.preferences.value.volume
                            val newVol = (current + 0.05f).coerceAtMost(1f)
                            PreferencesManager.setVolume(newVol)
                            player.setVolume(newVol)
                            true
                        } else false
                    }
                    // Ctrl+Down = volume down
                    KeyEvent.VK_DOWN -> {
                        if (event.isControlDown || event.isMetaDown) {
                            val current = PreferencesManager.preferences.value.volume
                            val newVol = (current - 0.05f).coerceAtLeast(0f)
                            PreferencesManager.setVolume(newVol)
                            player.setVolume(newVol)
                            true
                        } else false
                    }
                    // M = mute/unmute
                    KeyEvent.VK_M -> {
                        val current = PreferencesManager.preferences.value.volume
                        val newVol = if (current > 0f) 0f else 1f
                        PreferencesManager.setVolume(newVol)
                        player.setVolume(newVol)
                        true
                    }
                    else -> false
                }
            }
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
