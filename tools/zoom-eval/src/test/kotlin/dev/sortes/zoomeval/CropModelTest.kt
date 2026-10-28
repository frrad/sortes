package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropAnalysis
import dev.sortes.app.zoom.CropWindow
import dev.sortes.app.zoom.InkMask
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropModelTest {
    private val aspect = 0.75f

    /** A 60 x 80 page (exactly 3:4, so unpadded) with the given ink rectangles. */
    private fun page(vararg rects: IntArray): InkMask {
        val ink = BooleanArray(60 * 80)
        for ((l, t, r, b) in rects) for (y in t until b) for (x in l until r) ink[y * 60 + x] = true
        return InkMask(60, 80, ink)
    }

    private fun rect(l: Int, t: Int, r: Int, b: Int) = intArrayOf(l, t, r, b)

    private val body = rect(10, 10, 50, 70)
    private val pageNumber = rect(28, 75, 32, 77)

    private fun solve(masks: List<InkMask>, scale: Float, weights: Weights = Weights()): Crops =
        CropModel(aspect, weights).solve(masks.map { FramedPage(it, aspect) }, scale)

    @Test
    fun offCentreIsZeroWhenCentredAndGrowsTowardAnEdge() {
        val framed = FramedPage(page(body), aspect)
        // A 50 x 70 window: the 40 x 60 block has 5 cells spare on every side at (5, 5).
        assertEquals(0.0, framed.offCentre(5, 5, 50, 70), 1e-9)
        // Flush left and top: gaps 0 vs 10 across, 0 vs 10 down.
        assertEquals(10 / 100.0 + 10 / 140.0, framed.offCentre(10, 10, 50, 70), 1e-9)
        // Nothing kept: no content to centre.
        assertEquals(0.0, framed.offCentre(0, 0, 5, 5), 1e-9)
    }

    @Test
    fun aSingleWindowCentresTheText() {
        val crops = solve(listOf(page(body)), 0.75f)
        assertEquals(1, crops.k)
        assertEquals(0, crops.cutCells)
        // 45 cells across for a 40-cell block: as centred as whole cells allow.
        assertTrue(crops.pages[0].offCentre <= 1.0 / 90 + 1e-9)
    }

    @Test
    fun cuttingOnePagesPageNumberBeatsAnExtraWindow() {
        // Dense pages can't keep their page numbers at this scale; the sparse page could, by
        // moving its window down. One stable window should win.
        val dense = List(5) { page(body, pageNumber) }
        val sparse = page(rect(10, 30, 50, 60), pageNumber)
        val crops = solve(dense + sparse, 0.75f)
        assertEquals(1, crops.k)
        assertTrue(crops.pages.all { it.window == 0 })
        assertEquals(8 * 6, crops.cutCells.toInt())
    }

    @Test
    fun alternatingMarginsGetOneWindowPerSide() {
        val left = page(rect(2, 10, 38, 70))
        val right = page(rect(22, 10, 58, 70))
        val crops = solve(List(6) { if (it % 2 == 0) left else right }, 0.75f)
        assertEquals(2, crops.k)
        assertEquals(0L, crops.cutCells)
        assertArrayEquals(intArrayOf(0, 1, 0, 1, 0, 1), crops.pages.map { it.window }.toIntArray())
    }

    @Test
    fun aSwitchPenaltyCanOutweighAlternatingWindows() {
        val left = page(rect(2, 10, 38, 70))
        val right = page(rect(22, 10, 58, 70))
        val crops = solve(List(6) { if (it % 2 == 0) left else right }, 0.75f, Weights(delta = 10.0))
        assertEquals(1, crops.k)
        assertEquals(0, crops.switches)
    }

    @Test
    fun theSwitchPenaltyIsCountedInTheTotal() {
        val left = page(rect(2, 10, 38, 70))
        val right = page(rect(22, 10, 58, 70))
        val weights = Weights(delta = 0.01)
        val crops = solve(List(4) { if (it % 2 == 0) left else right }, 0.75f, weights)
        assertEquals(2, crops.k)
        assertEquals(3, crops.switches)
        assertEquals(crops.cutTerm + crops.offTerm + 0.05 + 0.03, crops.total, 1e-9)
        val paired = solve(List(4) { if (it % 2 == 0) left else right }, 0.75f, weights.copy(delta2 = 0.02))
        assertEquals(paired.cutTerm + paired.offTerm + 0.05 + 0.03, paired.total, 1e-9)
    }

    @Test
    fun breaksSparesAStrictAlternation() {
        val left = page(rect(2, 10, 38, 70))
        val right = page(rect(22, 10, 58, 70))
        val crops = solve(List(6) { if (it % 2 == 0) left else right }, 0.75f, Weights(delta2 = 0.5))
        assertEquals(2, crops.k)
        assertEquals(0, crops.breaks)
        assertEquals(0L, crops.cutCells)
    }

    @Test
    fun breaksSuppressAWindowThatOnlyRecentresAFewPages() {
        // Pages 2 and 5 sit a few cells left: a second window would centre them, at the price
        // of four breaks in the pattern.
        val usual = page(rect(10, 10, 48, 70))
        val shifted = page(rect(4, 10, 42, 70))
        val masks = listOf(usual, usual, shifted, usual, usual, shifted, usual)
        assertEquals(2, solve(masks, 0.75f, Weights(gamma = 0.001, delta2 = 0.0)).k)
        val steady = solve(masks, 0.75f, Weights(gamma = 0.001, delta2 = 0.05))
        assertEquals(1, steady.k)
        assertEquals(0, steady.breaks)
    }

    @Test
    fun blankPagesKeepThePreviousWindowWithoutABreakPenalty() {
        val left = page(rect(2, 10, 38, 70))
        val right = page(rect(22, 10, 58, 70))
        val crops = solve(listOf(left, right, page(), left), 0.75f, Weights(delta2 = 0.0))
        assertEquals(crops.pages[1].window, crops.pages[2].window)
    }

    @Test
    fun blankPagesContinueAnAlternation() {
        // A blank page in a two-sided layout keeps its side's window, so nothing breaks.
        val left = page(rect(2, 10, 38, 70))
        val right = page(rect(22, 10, 58, 70))
        val crops = solve(listOf(left, right, page(), right), 0.75f)
        assertEquals(crops.pages[0].window, crops.pages[2].window)
        assertEquals(0, crops.breaks)
    }

    @Test
    fun scoringGivenWindowsCountsTheInkTheyCut() {
        val full = page(rect(0, 0, 60, 80))
        val crops = CropModel(aspect).score(listOf(full, full), List(2) { CropWindow(0f, 0f, 0.5f) })
        assertEquals(1, crops.k)
        assertEquals(2 * (4800 - 30 * 40), crops.cutCells.toInt())
        assertEquals(0.75, crops.pages[0].cut, 1e-9)
    }

    @Test
    fun theChosenScaleKeepsTheModelsCutWithinTheReadersBudget() = runBlocking {
        val masks = List(5) { page(body, pageNumber) } + page(rect(10, 30, 50, 60), pageNumber)
        val crops = CropModel(aspect).crops(masks)
        assertTrue(crops.cutFraction <= CropAnalysis.AUTO_MAX_LOST)
        // Tight enough to cut the page numbers (0.3% of the ink), so tighter than the whole page.
        assertTrue(crops.scale < 0.9f)
    }

    @Test
    fun aBlankPaperStaysWhole() = runBlocking {
        val crops = CropModel(aspect).crops(listOf(page(), page()))
        assertEquals(1f, crops.scale)
        assertEquals(0.0, crops.total, 1e-9)
    }
}
