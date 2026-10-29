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
    fun shadedBoxesOnWhitePaperAreInk() {
        // A code listing on a 240-grey background: content, so kept like text.
        val page = IntArray(100) { i -> if (i / 10 in 3..6 && i % 10 in 2..7) gray(240) else gray(255) }
        val mask = InkMask.fromArgb(page, 10, 10)
        assertEquals(24, mask.inkCount)
        assertEquals(249, InkMask.inkThreshold(IntArray(100) { 255 }))
    }

    /** A grainy off-white scan: paper at 245 ± [noise], with a dark text block. */
    private fun scan(noise: Int, random: kotlin.random.Random): IntArray = IntArray(60 * 80) { i ->
        val x = i % 60
        val y = i / 60
        if (x in 10 until 50 && y in 10 until 70 && y % 3 != 0) gray(30) else gray(245 + random.nextInt(-noise, noise + 1))
    }

    @Test
    fun grainOnAnOffWhiteScanIsPaper() {
        val pixels = scan(8, kotlin.random.Random(3))
        val mask = InkMask.fromArgb(pixels, 60, 80)
        // Only the text block's inked rows: 40 x 40 cells.
        assertEquals(40 * 40, mask.inkCount)
        assertTrue(InkMask.inkThreshold(IntArray(pixels.size) { pixels[it] and 0xFF }) < 237)
    }

    @Test
    fun aGrainyScanStillZoomsToItsText() = kotlinx.coroutines.runBlocking {
        val random = kotlin.random.Random(5)
        val masks = List(4) { InkMask.fromArgb(scan(8, random), 60, 80) }
        val analysis = CropAnalysis.of(masks, 0.75f)
        assertTrue("auto ${analysis.autoScale} ink ${masks.map { it.inkCount }} lost ${(0 until analysis.scales.size step 10).map { analysis.lostFraction(it) }}", analysis.autoScale < 0.85f)
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
