package com.lyrenne.desktop.ui.theme

import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the cover accent: colour wins over grey borders, and a grey cover gives no tint. */
class ArtColorTest {

    private fun bitmap(pixel: (Int, Int) -> Int) = Bitmap().apply {
        allocN32Pixels(16, 16)
        for (y in 0 until 16) for (x in 0 until 16) erase(pixel(x, y), org.jetbrains.skia.IRect.makeXYWH(x, y, 1, 1))
    }

    @Test
    fun `saturated centre beats a grey border`() {
        // A dark red square inside a wide grey frame: the frame is most of the pixels.
        val c = ArtColor.accent(bitmap { x, y ->
            if (x in 5..10 && y in 5..10) 0xFF801010.toInt() else 0xFF808080.toInt()
        })!!
        assertEquals(1f, c.red, 0.01f)
        assert(c.green < 0.4f && c.blue < 0.4f) { "expected red, got $c" }
    }

    @Test
    fun `greyscale cover gives no tint`() {
        assertNull(ArtColor.accent(bitmap { x, _ -> if (x % 2 == 0) 0xFF202020.toInt() else 0xFFC0C0C0.toInt() }))
    }
}
