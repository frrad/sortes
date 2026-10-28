package dev.sortes.app.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkMaskTest {
    private fun gray(l: Int) = (0xFF shl 24) or (l shl 16) or (l shl 8) or l

    @Test
    fun offWhitePaperIsPaper() {
        // A scan on cream paper: background 238, text 40. A fixed threshold near white would
        // make the whole page ink.
        val pixels = IntArray(100) { if (it % 10 == 0) gray(40) else gray(238) }
        val mask = InkMask.fromArgb(pixels, 10, 10)
        assertEquals(10, mask.inkCount)
        assertEquals(238, InkMask.paperLuminance(IntArray(100) { if (it % 10 == 0) 40 else 238 }))
    }

    @Test
    fun aPageWithNoLightPixelsMeasuresAgainstWhite() {
        val mask = InkMask.fromArgb(IntArray(16) { gray(100) }, 4, 4)
        assertEquals(16, mask.inkCount)
    }

    @Test
    fun anExplicitThresholdStillWorks() {
        val mask = InkMask.fromArgb(intArrayOf(gray(230), gray(220), gray(255), gray(255)), 2, 2, threshold = 224)
        assertFalse(mask[0, 0])
        assertTrue(mask[1, 0])
    }

    @Test
    fun faintGlyphEdgesCountSoAClippedPageNumberIsSeen() {
        // The foot of Attention Is All You Need page 3 (rows 220-247 of its 192 x 248 analysis
        // render). The page number "3" spans rows 232-234, and its bottom row is only faintly
        // inked (luminance 226), lighter than the old fixed threshold of 224.
        val (pixels, width, height) = grayFixture("attention-p3-foot-gray.txt")
        val numberBottom = 14 // row 234
        val window = CellRect(0, 0, width, numberBottom) // ends just above that row

        val mask = InkMask.fromArgb(pixels, width, height)
        val cut = cutInk(mask, PageSize(width.toFloat(), height.toFloat()), window)
        assertTrue("faint row should be ink", (0 until width).any { mask[it, numberBottom] })
        assertTrue("the clipped number should be reported: $cut", cut.sumOf { it.cells } > 0)

        // With the old threshold, the window seemed to keep the whole number.
        val old = InkMask.fromArgb(pixels, width, height, threshold = 224)
        assertEquals(emptyList<CutInk>(), cutInk(old, PageSize(width.toFloat(), height.toFloat()), window))
    }
}
