package com.lyrenne.desktop.gamemode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Rectangle

class OverlayModelTest {
    private val playingSynced = OverlayInputs(hasSong = true, isPlaying = true, hasSyncedLyrics = true)
    private val playingPlain = OverlayInputs(hasSong = true, isPlaying = true, hasSyncedLyrics = false)
    private val pausedSynced = OverlayInputs(hasSong = true, isPlaying = false, hasSyncedLyrics = true)
    private val nothing = OverlayInputs(hasSong = false, isPlaying = false, hasSyncedLyrics = false)

    private fun model(mode: OverlayMode) = OverlayModel().apply { this.mode = mode }

    @Test
    fun `off shows nothing on its own, even on a song change`() {
        val m = model(OverlayMode.OFF)
        m.songChanged(0)
        assertEquals(OverlayView.NONE, m.view(0, playingSynced))
        assertNull(m.nextChange(0))
    }

    @Test
    fun `toast shows on a song change and hides after its time`() {
        val m = model(OverlayMode.TOAST)
        assertFalse(m.view(0, playingPlain).visible)
        m.songChanged(1_000)
        assertTrue(m.view(1_000, playingPlain).card)
        assertTrue(m.view(1_000 + OverlayModel.TOAST_MS - 1, playingPlain).card)
        assertEquals(1_000 + OverlayModel.TOAST_MS, m.nextChange(1_000))
        assertFalse(m.view(1_000 + OverlayModel.TOAST_MS, playingPlain).visible)
        assertNull(m.nextChange(1_000 + OverlayModel.TOAST_MS))
    }

    @Test
    fun `toast mode never shows the lyrics strip on its own`() {
        val m = model(OverlayMode.TOAST)
        m.songChanged(0)
        assertFalse(m.view(0, playingSynced).lyrics)
    }

    @Test
    fun `a second song change restarts the toast`() {
        val m = model(OverlayMode.TOAST)
        m.songChanged(0)
        m.songChanged(4_000)
        assertTrue(m.view(8_000, playingPlain).card)
        assertFalse(m.view(4_000 + OverlayModel.TOAST_MS, playingPlain).card)
    }

    @Test
    fun `lyrics strip shows while synced lyrics play, and not while paused`() {
        val m = model(OverlayMode.LYRICS)
        assertTrue(m.view(0, playingSynced).lyrics)
        assertFalse(m.view(0, pausedSynced).visible)
        assertFalse(m.view(0, playingPlain).lyrics)
    }

    @Test
    fun `lyrics mode also introduces each song with the card`() {
        val m = model(OverlayMode.LYRICS)
        m.songChanged(0)
        val v = m.view(100, playingSynced)
        assertTrue(v.card && v.lyrics)
        assertFalse(m.view(OverlayModel.TOAST_MS, playingSynced).card)
        // A song without synced lyrics still gets its toast.
        m.songChanged(10_000)
        assertTrue(m.view(10_000, playingPlain).card)
    }

    @Test
    fun `peek shows the card and lyrics line in any mode, then goes`() {
        // Always on never goes, which is its point; it has its own test.
        for (mode in OverlayMode.entries - OverlayMode.ALWAYS) {
            val m = model(mode)
            m.peek(0)
            val v = m.view(0, pausedSynced)
            assertTrue("$mode", v.card && v.lyrics)
            assertFalse("$mode", m.view(OverlayModel.PEEK_MS, pausedSynced).card)
        }
    }

    @Test
    fun `peeking again extends rather than shortens`() {
        val m = model(OverlayMode.OFF)
        m.peek(0, 10_000)
        m.peek(1_000)
        assertTrue(m.view(9_000, playingPlain).card)
        assertEquals(10_000L, m.nextChange(1_000))
    }

    @Test
    fun `peek with nothing playing still shows the card, which says so`() {
        val m = model(OverlayMode.OFF)
        m.peek(0)
        val v = m.view(0, nothing)
        assertTrue(v.card)
        assertFalse(v.lyrics)
    }

    @Test
    fun `nothing playing hides the toast and the strip`() {
        val m = model(OverlayMode.LYRICS)
        m.songChanged(0)
        assertFalse(m.view(0, nothing).visible)
    }

    @Test
    fun `toggle from off pins the overlay until toggled back`() {
        val m = model(OverlayMode.OFF)
        m.toggle()
        assertTrue(m.isShownByToggle)
        // The card always, the lyrics too when there are synced ones, paused or not.
        assertEquals(OverlayView(card = true, lyrics = true), m.view(99_000, pausedSynced))
        assertEquals(OverlayView(card = true, lyrics = false), m.view(99_000, playingPlain))
        m.toggle()
        assertFalse(m.isShownByToggle)
        assertFalse(m.view(99_000, playingSynced).visible)
    }

    @Test
    fun `the pin hotkey keeps card and lyrics up in any mode until pressed again`() {
        for (mode in OverlayMode.entries) {
            val m = model(mode)
            assertTrue(m.togglePin())
            assertEquals(mode.name, OverlayView(card = true, lyrics = true), m.view(99_000, pausedSynced))
            assertEquals(mode.name, OverlayView(card = true, lyrics = false), m.view(99_000, playingPlain))
            assertFalse(m.togglePin())
        }
        // Unpinned, Off shows nothing again.
        val off = model(OverlayMode.OFF)
        off.togglePin(); off.togglePin()
        assertFalse(off.view(99_000, playingSynced).visible)
    }

    @Test
    fun `always on keeps card and lyrics up, paused too, and steps aside for Lyrenne`() {
        val m = model(OverlayMode.ALWAYS)
        assertEquals(OverlayView(card = true, lyrics = true), m.view(0, playingSynced))
        assertEquals(OverlayView(card = true, lyrics = true), m.view(99_000, pausedSynced))
        assertFalse(m.view(0, playingSynced.copy(appFocused = true)).visible)
        // The show/hide hotkey still clears the screen.
        m.toggle()
        assertFalse(m.view(0, playingSynced).visible)
    }

    @Test
    fun `a pinned overlay also steps aside while Lyrenne is the active window`() {
        val m = model(OverlayMode.OFF)
        m.togglePin()
        assertFalse(m.view(0, playingSynced.copy(appFocused = true)).visible)
        assertTrue(m.view(0, playingSynced).card)
    }

    @Test
    fun `toggle in an active mode hides it, toast and peek included`() {
        val m = model(OverlayMode.LYRICS)
        m.songChanged(0)
        m.peek(0)
        m.toggle()
        assertFalse(m.isShownByToggle)
        assertFalse(m.view(10, playingSynced).visible)
        // Song changes while hidden stay hidden...
        m.songChanged(100)
        assertFalse(m.view(100, playingSynced).visible)
        // ...but an explicit peek still shows.
        m.peek(200)
        assertTrue(m.view(200, playingSynced).card)
        m.toggle()
        assertTrue(m.view(99_000, playingSynced).lyrics)
    }

    @Test
    fun `flash always shows, briefly, and the newest one wins`() {
        val m = model(OverlayMode.LYRICS)
        m.toggle() // hidden
        val first = m.flash(OverlayFlash.Kind.VOLUME, "Volume 55%", 0)
        assertEquals(first, m.view(0, nothing).flash)
        val second = m.flash(OverlayFlash.Kind.VOLUME, "Volume 60%", 500)
        assertTrue(second.serial > first.serial)
        assertEquals(second, m.view(600, playingSynced).flash)
        assertEquals(500 + OverlayModel.FLASH_MS, m.nextChange(600))
        assertNull(m.view(500 + OverlayModel.FLASH_MS, playingSynced).flash)
    }

    @Test
    fun `next change is the earliest pending timer`() {
        val m = model(OverlayMode.TOAST)
        m.songChanged(0)
        m.flash(OverlayFlash.Kind.NEXT, "Next", 0)
        assertEquals(OverlayModel.FLASH_MS, m.nextChange(0))
        assertEquals(OverlayModel.TOAST_MS, m.nextChange(OverlayModel.FLASH_MS))
    }

    @Test
    fun `nothing shows on its own while Lyrenne is the active window, but peeks and flashes do`() {
        val focused = playingSynced.copy(appFocused = true)
        val m = model(OverlayMode.LYRICS)
        m.songChanged(0)
        assertFalse(m.view(0, focused).visible)
        m.peek(0)
        assertTrue(m.view(0, focused).card && m.view(0, focused).lyrics)
        val toast = model(OverlayMode.TOAST)
        toast.songChanged(0)
        assertFalse(toast.view(0, focused).visible)
        assertTrue(toast.view(0, playingPlain).card)
        toast.flash(OverlayFlash.Kind.LIKED, "Liked", 0)
        assertTrue(toast.view(0, focused).flash != null)
    }

    @Test
    fun `placement puts the window inside the screen at every corner`() {
        val screen = Rectangle(1280, 0, 1280, 720) // a second monitor at 150%
        for (corner in OverlayCorner.entries) {
            val p = overlayLocation(screen, corner, 420, 270, margin = 28)
            assertTrue("$corner", screen.contains(Rectangle(p.x, p.y, 420, 270)))
        }
        assertEquals(java.awt.Point(1280 + 1280 - 420 - 28, 28), overlayLocation(screen, OverlayCorner.TOP_RIGHT, 420, 270, 28))
        assertEquals(java.awt.Point(1280 + 430, 720 - 270 - 28), overlayLocation(screen, OverlayCorner.BOTTOM_CENTER, 420, 270, 28))
    }

    @Test
    fun `overlay style adds click-through, no-activate and tool window, and drops the taskbar flag`() {
        val awt = OverlayWin32.WS_EX_APPWINDOW or OverlayWin32.WS_EX_TOPMOST
        val style = OverlayWin32.overlayStyle(awt)
        assertEquals(OverlayWin32.OVERLAY_EX_STYLE, style and OverlayWin32.OVERLAY_EX_STYLE)
        assertEquals(0, style and OverlayWin32.WS_EX_APPWINDOW)
        assertEquals(OverlayWin32.WS_EX_TOPMOST, style and OverlayWin32.WS_EX_TOPMOST)
        assertEquals(style, OverlayWin32.overlayStyle(style))
    }

    @Test
    fun `window sizes scale one layout`() {
        assertEquals(OVERLAY_BASE_WIDTH_DP, OverlaySize.MEDIUM.windowWidthDp())
        assertTrue(OverlaySize.SMALL.windowWidthDp() < OverlaySize.MEDIUM.windowWidthDp())
        assertTrue(OverlaySize.LARGE.windowHeightDp() > OverlaySize.MEDIUM.windowHeightDp())
    }
}
