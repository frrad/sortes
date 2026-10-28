package dev.sortes.app.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    private val letter = PageSize(612f, 792f)

    @Test
    fun widePagesArePaddedVertically() {
        assertEquals(PageSize(612f, 816f), letter.padded(0.75f))
    }

    @Test
    fun narrowPagesArePaddedHorizontally() {
        val padded = PageSize(400f, 800f).padded(0.75f)
        assertEquals(600f, padded.width, 1e-3f)
        assertEquals(800f, padded.height, 1e-3f)
    }

    @Test
    fun fullPageShowsThePageWithItsPadding() {
        assertEquals(Region(0f, -12f, 612f, 816f), FullPage.regionOn(letter, 0.75f))
    }

    @Test
    fun windowsMapToPagePoints() {
        val window = CropWindow(left = 0.1f, top = 0.2f, scale = 0.5f)

        val region = window.regionOn(letter, 0.75f)

        assertEquals(61.2f, region.left, 1e-3f)
        assertEquals(0.2f * 816 - 12, region.top, 1e-3f)
        assertEquals(306f, region.width, 1e-3f)
        assertEquals(408f, region.height, 1e-3f)
        // Same shape as the screen, so it fills it without distortion.
        assertEquals(0.75f, region.width / region.height, 1e-5f)
    }

    @Test
    fun analysisDensityFitsTheLargestPaddedPage() {
        val pages = listOf(letter, PageSize(306f, 396f))

        val density = analysisDensity(pages, 0.75f, longSide = 256)

        assertEquals(256f / 816f, density, 1e-6f)
        assertEquals(192 to 248, maskSize(letter, density))
        assertEquals(96 to 124, maskSize(PageSize(306f, 396f), density))
    }

    @Test
    fun masksAreNeverEmpty() {
        assertEquals(1 to 1, maskSize(PageSize(0.1f, 0.1f), 1f))
    }

    @Test
    fun inkMaskThresholdsLuminance() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val lightGray = 0xFFE8E8E8.toInt()  // anti-aliasing fringe: paper
        val midGray = 0xFF808080.toInt()
        val red = 0xFFFF0000.toInt()  // colored figures count as ink

        val mask = InkMask.fromArgb(intArrayOf(white, black, lightGray, midGray, red, white), 3, 2)

        assertEquals(listOf(false, true, false, true, true, false), (0 until 6).map { mask[it % 3, it / 3] })
        assertEquals(3, mask.inkCount)
        assertTrue(mask[1, 0])
    }

    @Test
    fun inkMaskRejectsMismatchedSizes() {
        assertThrows(IllegalArgumentException::class.java) { InkMask(2, 2, BooleanArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { InkMask(0, 2, BooleanArray(0)) }
    }
}
