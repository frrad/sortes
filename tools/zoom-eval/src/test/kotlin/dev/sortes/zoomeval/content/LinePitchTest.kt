package dev.sortes.zoomeval.content

import dev.sortes.app.zoom.InkMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinePitchTest {
    /**
     * Two columns of "text" lines: [glyph]-tall 3-wide glyphs with 2-px spaces, every [pitch] px,
     * columns offset by [offset].
     */
    private fun page(
        width: Int,
        height: Int,
        pitch: Int,
        glyph: Int,
        offset: Int = 0,
        lines: Int = 30,
    ): InkMask {
        val ink = BooleanArray(width * height)
        for (column in 0..1) {
            val x0 = column * width / 2 + 4
            val x1 = (column + 1) * width / 2 - 4
            val y0 = 10 + column * offset
            for (line in 0 until lines) {
                val top = y0 + line * pitch
                for (x in x0 until x1) if ((x - x0) % 5 < 3)
                    for (y in top until top + glyph) if (y < height) ink[y * width + x] = true
            }
        }
        return InkMask(width, height, ink)
    }

    @Test
    fun `line pitch is found despite offset columns`() {
        val p = LinePitch.of(page(200, 400, pitch = 11, glyph = 6, offset = 5))!!
        assertEquals(11.0, p, 0.15)
    }

    @Test
    fun `line pitch is not mistaken for a multiple or half of itself`() {
        for (pitch in listOf(5, 8, 13)) {
            val p = LinePitch.of(page(200, 600, pitch = pitch, glyph = pitch / 2 + 1))!!
            assertEquals(pitch.toDouble(), p, 0.2)
        }
    }

    @Test
    fun `a blank page has no pitch`() {
        assertNull(LinePitch.of(InkMask(100, 100, BooleanArray(100 * 100))))
    }
}
