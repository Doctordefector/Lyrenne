package com.lyrenne.desktop.ui.stage

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.KeyEvent as AwtKeyEvent

/** F11, Esc and the open shortcut drive Stage, and full screen is undone on the way out. */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class StageKeysTest {
    private val source = javax.swing.JPanel()

    private fun key(code: Int, ctrl: Boolean = false, shift: Boolean = false): KeyEvent {
        var mods = 0
        if (ctrl) mods = mods or AwtKeyEvent.CTRL_DOWN_MASK
        if (shift) mods = mods or AwtKeyEvent.SHIFT_DOWN_MASK
        return KeyEvent(
            key = androidx.compose.ui.input.key.Key(code),
            type = androidx.compose.ui.input.key.KeyEventType.KeyDown,
            isCtrlPressed = ctrl,
            isShiftPressed = shift,
            nativeEvent = AwtKeyEvent(source, AwtKeyEvent.KEY_PRESSED, 0L, mods, code, AwtKeyEvent.CHAR_UNDEFINED),
        )
    }

    @After fun reset() { StageMode.close(); RewindMode.close() }

    @Test
    fun shortcutOpensAndClosesStage() {
        assertTrue(handleStageRewindKey(key(AwtKeyEvent.VK_F, ctrl = true, shift = true)))
        assertTrue(StageMode.isOpen)
        assertTrue(handleStageRewindKey(key(AwtKeyEvent.VK_F, ctrl = true, shift = true)))
        assertFalse(StageMode.isOpen)
        // Plain Ctrl+F is the app's search, not Stage.
        assertFalse(handleStageRewindKey(key(AwtKeyEvent.VK_F, ctrl = true)))
    }

    @Test
    fun f11TogglesFullscreenOnlyWhileStageIsUp() {
        assertFalse(handleStageRewindKey(key(AwtKeyEvent.VK_F11)))
        StageMode.open()
        assertTrue(handleStageRewindKey(key(AwtKeyEvent.VK_F11)))
        assertTrue(StageMode.fullscreenRequested)
        assertTrue(handleStageRewindKey(key(AwtKeyEvent.VK_F11)))
        assertFalse(StageMode.fullscreenRequested)
    }

    @Test
    fun escLeavesStageAndFullscreen() {
        StageMode.open(); StageMode.toggleFullscreen()
        assertTrue(handleStageRewindKey(key(AwtKeyEvent.VK_ESCAPE)))
        assertFalse(StageMode.isOpen)
        assertFalse(StageMode.fullscreenRequested)
    }

    @Test
    fun keeperRestoresTheWindowItFound() {
        for (start in listOf(WindowPlacement.Floating, WindowPlacement.Maximized)) {
            val state = WindowState(placement = start, size = DpSize(1100.dp, 700.dp))
            val keeper = FullscreenKeeper(state)
            keeper.apply(true)
            assertEquals(WindowPlacement.Fullscreen, state.placement)
            keeper.apply(true) // repeated requests change nothing
            assertEquals(WindowPlacement.Fullscreen, state.placement)
            keeper.apply(false)
            assertEquals(start, state.placement)
            assertEquals(DpSize(1100.dp, 700.dp), state.size)
        }
        // A window the user made full screen themselves is not Stage's to undo.
        val userFull = WindowState(placement = WindowPlacement.Fullscreen)
        FullscreenKeeper(userFull).apply(false)
        assertEquals(WindowPlacement.Fullscreen, userFull.placement)
    }
}
