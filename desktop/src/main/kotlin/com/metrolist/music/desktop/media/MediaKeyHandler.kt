package com.metrolist.music.desktop.media

import com.metrolist.music.desktop.playback.DesktopPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/**
 * Handles global media key events (play/pause, next, previous)
 * Uses AWT KeyboardFocusManager for cross-platform support
 */
object MediaKeyHandler {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var player: DesktopPlayer? = null
    private var keyDispatcher: KeyEventDispatcher? = null
    private var isInitialized = false

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
            // Space bar for play/pause (when not in text field)
            KeyEvent.VK_SPACE -> {
                if (!isTextFieldFocused(event)) {
                    player.togglePlayPause()
                    true
                } else {
                    false
                }
            }
            // Keyboard shortcuts with Ctrl/Cmd
            KeyEvent.VK_RIGHT -> {
                if (event.isControlDown || event.isMetaDown) {
                    scope.launch { player.playNext() }
                    true
                } else {
                    false
                }
            }
            KeyEvent.VK_LEFT -> {
                if (event.isControlDown || event.isMetaDown) {
                    scope.launch { player.playPrevious() }
                    true
                } else {
                    false
                }
            }
            // Ctrl+P for play/pause
            KeyEvent.VK_P -> {
                if (event.isControlDown || event.isMetaDown) {
                    player.togglePlayPause()
                    true
                } else {
                    false
                }
            }
            // Ctrl+S for shuffle toggle
            KeyEvent.VK_S -> {
                if (event.isControlDown || event.isMetaDown) {
                    player.toggleShuffle()
                    true
                } else {
                    false
                }
            }
            // Ctrl+R for repeat toggle
            KeyEvent.VK_R -> {
                if (event.isControlDown || event.isMetaDown) {
                    player.toggleRepeat()
                    true
                } else {
                    false
                }
            }
            // Volume up (Ctrl+Up)
            KeyEvent.VK_UP -> {
                if (event.isControlDown || event.isMetaDown) {
                    // Increase volume by 10%
                    val currentState = player.state.value
                    // Volume is managed separately, this is a placeholder
                    true
                } else {
                    false
                }
            }
            // Volume down (Ctrl+Down)
            KeyEvent.VK_DOWN -> {
                if (event.isControlDown || event.isMetaDown) {
                    // Decrease volume by 10%
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private fun isTextFieldFocused(event: KeyEvent): Boolean {
        val source = event.source
        return source is javax.swing.JTextField ||
               source is javax.swing.JTextArea ||
               source is javax.swing.text.JTextComponent
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
