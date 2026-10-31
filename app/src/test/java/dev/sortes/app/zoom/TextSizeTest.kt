package dev.sortes.app.zoom

import org.junit.Assert.*
import org.junit.Test

class TextSizeTest {
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
    fun `a blank page has no glyph height`() {
        val blank = InkMask(100, 100, BooleanArray(100 * 100))
        assertNull(TextSize.glyphHeight(blank))
    }

    @Test
    fun `glyph height ignores rules and tall figures`() {
        val m = page(200, 400, pitch = 11, glyph = 6)
        val ink = BooleanArray(200 * 400) { m[it % 200, it / 200] }
        // A long rule and a big filled box.
        for (x in 0 until 200) ink[395 * 200 + x] = true
        for (y in 340..390) for (x in 20..80) ink[y * 200 + x] = true
        assertEquals(6.0, TextSize.glyphHeight(InkMask(200, 400, ink))!!, 1e-9)
    }

    @Test
    fun `median skips nulls`() {
        assertEquals(2.0, TextSize.median(listOf(null, 1.0, 3.0, 2.0))!!, 1e-9)
        assertEquals(1.5, TextSize.median(listOf(1.0, 2.0))!!, 1e-9)
        assertNull(TextSize.median(listOf(null)))
    }
}
