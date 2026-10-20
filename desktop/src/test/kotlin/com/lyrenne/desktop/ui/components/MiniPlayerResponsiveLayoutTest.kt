package com.lyrenne.desktop.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerResponsiveLayoutTest {
    @Test
    fun `at the minimum window the transport fits and the slider gives way`() {
        // 720 dp window: minus the rail and the dock's margins
        val layout = dockLayout(619.dp)
        assertTrue(layout.centerWidth >= 216.dp) // five 40 dp buttons and their gaps
        assertEquals(0.dp, layout.volumeSliderWidth)
    }

    @Test
    fun `at the default window the slider is full width`() {
        val layout = dockLayout(1099.dp)
        assertEquals(110.dp, layout.volumeSliderWidth)
        assertTrue(layout.centerWidth in 400.dp..560.dp)
    }

    @Test
    fun `the centre column never outgrows its cap`() {
        assertEquals(560.dp, dockLayout(2400.dp).centerWidth)
    }
}
