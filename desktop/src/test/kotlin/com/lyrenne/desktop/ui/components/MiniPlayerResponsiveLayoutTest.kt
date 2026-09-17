package com.lyrenne.desktop.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerResponsiveLayoutTest {
    @Test
    fun `compact layout keeps a usable volume slider`() {
        val layout = miniPlayerResponsiveLayout(639.dp)

        assertFalse(layout.showSecondaryControls)
        assertFalse(layout.showExtendedControls)
        assertEquals(64.dp, layout.volumeSliderWidth)
    }

    @Test
    fun `secondary controls appear at medium breakpoint`() {
        assertFalse(miniPlayerResponsiveLayout(839.dp).showSecondaryControls)

        val layout = miniPlayerResponsiveLayout(840.dp)
        assertTrue(layout.showSecondaryControls)
        assertFalse(layout.showExtendedControls)
        assertEquals(80.dp, layout.volumeSliderWidth)
    }

    @Test
    fun `extended controls and full slider appear at wide breakpoint`() {
        assertFalse(miniPlayerResponsiveLayout(1039.dp).showExtendedControls)

        val layout = miniPlayerResponsiveLayout(1040.dp)
        assertTrue(layout.showSecondaryControls)
        assertTrue(layout.showExtendedControls)
        assertEquals(100.dp, layout.volumeSliderWidth)
    }
}
