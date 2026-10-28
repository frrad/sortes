package dev.sortes.app.ui

import androidx.compose.ui.geometry.Rect
import dev.sortes.app.zoom.CropWindow
import dev.sortes.app.zoom.FullPage
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.Region
import dev.sortes.app.zoom.regionOn
import org.junit.Assert.assertEquals
import org.junit.Test

class CropPreviewGeometryTest {

    @Test
    fun fitsTallSpacesByWidth() {
        assertEquals(300 to 400, fitAspect(300, 500, 0.75f))
    }

    @Test
    fun fitsWideSpacesByHeight() {
        // The space above an open zoom panel: wider than the screen's shape.
        assertEquals(180 to 240, fitAspect(360, 240, 0.75f))
    }

    @Test
    fun fitsExactly() {
        assertEquals(600 to 800, fitAspect(600, 800, 0.75f))
    }

    @Test
    fun roundingNeverOverflowsTheSpace() {
        for (w in 1..60) for (h in 1..60) {
            val (fw, fh) = fitAspect(w, h, 0.7071f)
            assert(fw <= w && fh <= h) { "$w x $h -> $fw x $fh" }
        }
    }

    @Test
    fun theShownRegionFillsTheBitmap() {
        val shown = Region(-12f, 0f, 624f, 832f)

        assertEquals(Rect(0f, 0f, 300f, 400f), shown.toPixels(shown, 300, 400))
    }

    @Test
    fun cropWindowsMapIntoTheWholePageBitmap() {
        // US Letter padded to 3:4 (612 x 816 points), shown on a 306 x 408 bitmap: half size.
        val letter = PageSize(612f, 792f)
        val shown = FullPage.regionOn(letter, 0.75f)
        val window = CropWindow(left = 0.1f, top = 0.2f, scale = 0.5f).regionOn(letter, 0.75f)

        val rect = window.toPixels(shown, 306, 408)

        assertEquals(0.1f * 306, rect.left, 1e-3f)
        assertEquals(0.2f * 408, rect.top, 1e-3f)
        assertEquals(0.5f * 306, rect.width, 1e-3f)
        assertEquals(0.5f * 408, rect.height, 1e-3f)
    }

    @Test
    fun pageRegionsMapThroughThePadding() {
        // The page number box of a 600 x 800 page shown padded to 1:1, on a 100 x 100 bitmap.
        val page = PageSize(600f, 800f)
        val shown = FullPage.regionOn(page, 1f)

        val rect = Region(290f, 760f, 20f, 20f).toPixels(shown, 100, 100)

        // The page is centred in 100 points of padding on each side.
        assertEquals(Rect(390f / 8, 760f / 8, 410f / 8, 780f / 8), rect)
    }
}
