package dev.sortes.app.ui

import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.app.zoom.CropWindow
import dev.sortes.app.zoom.ModelCrops
import dev.sortes.app.zoom.ZoomCurve
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomSummaryTest {
    private val scales = floatArrayOf(1f, 0.9f, 0.8f, 0.7f)

    private fun curve(points: Map<Int, ZoomCurve.Point>) =
        ZoomCurve(scales, points, pagesAnalyzed = 10, autoScale = 0.8f)

    /** Crops at [scale] with [k] windows that cut [cut] of 1000 cells on [pages] pages. */
    private fun crops(scale: Float, k: Int, cut: Int, pages: Int) =
        ModelCrops(
                scale = scale,
                windows = List(k) { CropWindow(0f, 0f, scale) },
                pages = emptyList(),
                weights = CropModelWeights(),
                totalInk = 1000,
            )
            .let {
                it.copy(pages = List(10) { p -> page(p % k, if (p < pages) cut / pages else 0) })
            }

    private fun page(window: Int, cut: Int) =
        dev.sortes.app.zoom.PageCrop(
            windowIndex = window,
            window = CropWindow(0f, 0f, 1f),
            cells = dev.sortes.app.zoom.CellRect(0, 0, 1, 1),
            cutCells = cut,
            cut = 0.0,
            pageCut = 0.0,
            offCentre = 0.0,
        )

    @Test
    fun beforeAnyPointIsComputedOnlyTheZoomIsShown() {
        assertEquals("Zoom 1.25×", zoomSummary(curve(emptyMap()), 2, crops = null, sampled = false))
    }

    @Test
    fun aComputedPointIsShownAsIs() {
        val c = curve(mapOf(2 to ZoomCurve.Point(0.004f, 3)))
        assertEquals(
            "Zoom 1.25× · cuts 0.4% of ink on 3 of 10 pages",
            zoomSummary(c, 2, crops = null, sampled = false),
        )
        assertEquals(
            "Zoom 1.25× · cuts 0.4% of ink on 3 of 10 sampled pages",
            zoomSummary(c, 2, crops = null, sampled = true),
        )
    }

    @Test
    fun betweenComputedPointsTheNearestIsShownAsAbout() {
        val c = curve(mapOf(0 to ZoomCurve.Point(0f, 0), 2 to ZoomCurve.Point(0.004f, 3)))
        assertEquals(
            "Zoom 1.43× · cuts about 0.4% of ink on 3 of 10 pages",
            zoomSummary(c, 3, crops = null, sampled = false),
        )
        // Index 1 is as near point 0 as point 2: the looser one is taken.
        assertEquals(
            "Zoom 1.11× · about nothing cut off",
            zoomSummary(c, 1, crops = null, sampled = false),
        )
    }

    @Test
    fun theCurrentCropsAreExactAndCountTheirWindows() {
        val c = curve(mapOf(2 to ZoomCurve.Point(0.5f, 9)))
        // The crops at this zoom win over the curve's point.
        assertEquals(
            "Zoom 1.25× · cuts 0.2% of ink on 2 of 10 pages · 2 windows",
            zoomSummary(c, 2, crops(0.8f, k = 2, cut = 2, pages = 2), sampled = false),
        )
        assertEquals(
            "Zoom 1.25× · nothing cut off",
            zoomSummary(c, 2, crops(0.8f, k = 1, cut = 0, pages = 0), sampled = false),
        )
        // Crops for another zoom don't apply.
        assertEquals(
            "Zoom 1.25× · cuts 50% of ink on 9 of 10 pages",
            zoomSummary(c, 2, crops(0.9f, k = 2, cut = 2, pages = 2), sampled = false),
        )
    }
}
