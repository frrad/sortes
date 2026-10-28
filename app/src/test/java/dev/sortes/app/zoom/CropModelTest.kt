package dev.sortes.app.zoom

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CropModelTest {
    private val aspect = 0.75f

    /** A 60 x 80 page (exactly 3:4, so unpadded) with the given ink rectangles (l, t, r, b). */
    private fun page(vararg rects: IntArray): InkMask {
        val p = TestPage(60, 80)
        for ((l, t, r, b) in rects) p.rect(l, t, r, b)
        return p.mask()
    }

    private fun rect(l: Int, t: Int, r: Int, b: Int) = intArrayOf(l, t, r, b)

    private val body = rect(10, 10, 50, 70)
    private val pageNumber = rect(28, 75, 32, 77)
    private val left = page(rect(2, 10, 38, 70))
    private val right = page(rect(22, 10, 58, 70))

    private fun solve(masks: List<InkMask>, scale: Float, weights: CropModelWeights = CropModelWeights()) =
        runBlocking { CropModel(aspect, weights).solve(masks, scale) }

    @Test
    fun aSingleWindowCentresTheText() {
        val crops = solve(listOf(page(body)), 0.75f)
        assertEquals(1, crops.k)
        assertEquals(0L, crops.cutCells)
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
        assertEquals(8L * 6, crops.cutCells)
    }

    @Test
    fun aTwoSidedBookGetsOneWindowPerSide() {
        val crops = solve(List(8) { if (it % 2 == 0) left else right }, 0.75f)
        assertEquals(2, crops.k)
        assertEquals(0L, crops.cutCells)
        assertArrayEquals(IntArray(8) { it % 2 }, crops.pages.map { it.window }.toIntArray())
        assertEquals(0, crops.breaks)
    }

    @Test
    fun aSwitchPenaltyCanOutweighAlternatingWindows() {
        val crops = solve(List(6) { if (it % 2 == 0) left else right }, 0.75f, CropModelWeights(delta = 10.0))
        assertEquals(1, crops.k)
        assertEquals(0, crops.switches)
    }

    @Test
    fun breaksSuppressAWindowThatOnlyRecentresAFewPages() {
        // Pages 2 and 5 sit a few cells left: a second window would centre them, at the price
        // of four breaks in the pattern.
        val usual = page(rect(10, 10, 48, 70))
        val shifted = page(rect(4, 10, 42, 70))
        val masks = listOf(usual, usual, shifted, usual, usual, shifted, usual)
        assertEquals(2, solve(masks, 0.75f, CropModelWeights(gamma = 0.001, delta2 = 0.0)).k)
        val steady = solve(masks, 0.75f, CropModelWeights(gamma = 0.001, delta2 = 0.05))
        assertEquals(1, steady.k)
        assertEquals(0, steady.breaks)
    }

    @Test
    fun thePageCutPenaltyLetsAPageThatWouldLoseALotKeepItsInk() {
        // At 0.85 the window is 68 rows. The usual pages need it at rows 9-77 for their page
        // numbers; page 3 has two extra lines above the text and needs rows 2-70. Its lines are
        // fewer cells than all the page numbers, so linear cost cuts them to stay put; the convex
        // per-page term gives page 3 a window of its own.
        val usual = page(body, pageNumber)
        val tall = page(rect(10, 2, 50, 4), rect(10, 5, 50, 7), body)
        val masks = List(14) { if (it == 2) tall else usual }
        val stayPut = solve(masks, 0.85f, CropModelWeights(epsilon = 0.0))
        assertEquals(1, stayPut.k)
        assertTrue("${stayPut.pages}", stayPut.pages[2].cutCells > 0)
        val keep = solve(masks, 0.85f, CropModelWeights(epsilon = 300.0))
        assertEquals(2, keep.k)
        assertEquals(0, keep.pages[2].cutCells)
        assertTrue(stayPut.pages[2].pageCut > 0.02)
    }

    @Test
    fun blankPagesKeepThePreviousWindowWithoutABreakPenalty() {
        val crops = solve(listOf(left, right, page(), left), 0.75f, CropModelWeights(delta2 = 0.0))
        assertEquals(crops.pages[1].window, crops.pages[2].window)
    }

    @Test
    fun blankPagesContinueAnAlternation() {
        val crops = solve(listOf(left, right, page(), right), 0.75f)
        assertEquals(crops.pages[0].window, crops.pages[2].window)
        assertEquals(0, crops.breaks)
    }

    @Test
    fun theTotalAddsUpItsTerms() {
        val weights = CropModelWeights(delta = 0.01, delta2 = 0.02)
        val crops = solve(List(4) { if (it % 2 == 0) left else right }, 0.75f, weights)
        assertEquals(2, crops.k)
        assertEquals(3, crops.switches)
        assertEquals(crops.cutTerm + crops.offTerm + crops.pageCutTerm + 0.05 + 0.03, crops.total, 1e-9)
    }

    @Test
    fun cellsAreThoseTheWindowCovers() {
        val crops = solve(List(3) { page(body, pageNumber) }, 0.75f)
        for (p in crops.pages) {
            val c = p.cells
            assertEquals(45, c.right - c.left)
            assertEquals(60, c.bottom - c.top)
            assertTrue("$c", c.left <= 10 && c.right >= 50 && c.top <= 10 && c.bottom >= 70)
        }
    }

    @Test
    fun scoringGivenWindowsCountsTheInkTheyCut() {
        val full = page(rect(0, 0, 60, 80))
        val crops = CropModel(aspect).score(listOf(full, full), List(2) { CropWindow(0f, 0f, 0.5f) })
        assertEquals(1, crops.k)
        assertEquals(2L * (4800 - 30 * 40), crops.cutCells)
        assertEquals(0.75, crops.pages[0].cut, 1e-9)
        assertEquals(0.75, crops.pages[0].pageCut, 1e-9)
    }

    @Test
    fun unanalysedPagesTakeTheWindowThatSuitsThem() {
        val crops = solve(List(6) { if (it % 2 == 0) left else right }, 0.75f)
        val model = CropModel(aspect)
        val ref = CropModel.referenceInk(listOf(left, right))
        assertEquals(crops.pages[0].window, model.assign(left, crops, ref).window)
        assertEquals(crops.pages[1].window, model.assign(right, crops, ref).window)
        // A blank page has no preference: it takes the one suggested.
        assertEquals(1, model.assign(page(), crops, ref, preferred = 1).window)
    }

    @Test
    fun autoKeepsTheModelsCutWithinTheReadersBudget() = runBlocking {
        val masks = List(5) { page(body, pageNumber) } + page(rect(10, 30, 50, 60), pageNumber)
        val crops = CropModel(aspect).auto(masks, CropAnalysis.of(masks, aspect))
        assertTrue(crops.cutFraction <= CropAnalysis.AUTO_MAX_LOST)
        // Tight enough to cut the page numbers (0.3% of the ink), so tighter than the whole page.
        assertTrue(crops.scale < 0.9f)
    }

    @Test
    fun aBlankPaperStaysWhole() = runBlocking {
        val masks = listOf(page(), page())
        val crops = CropModel(aspect).auto(masks, CropAnalysis.of(masks, aspect))
        assertEquals(1f, crops.scale)
        assertEquals(0.0, crops.total, 1e-9)
    }

    @Test
    fun badWeightsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CropModelWeights(beta = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { CropModelWeights(maxWindows = 0) }
        assertThrows(IllegalArgumentException::class.java) { CropModel(0f) }
    }

    // Property tests on random papers.

    private fun randomPaper(random: Random): List<InkMask> = List(random.nextInt(2, 9)) {
        val p = TestPage(60, 80)
        repeat(random.nextInt(0, 6)) {
            val l = random.nextInt(0, 55)
            val t = random.nextInt(0, 75)
            p.rect(l, t, (l + random.nextInt(1, 30)).coerceAtMost(60), (t + random.nextInt(1, 30)).coerceAtMost(80))
        }
        p.mask()
    }

    @Test
    fun refiningNeverRaisesTheCost() = runBlocking {
        val random = Random(7)
        repeat(40) {
            val masks = randomPaper(random)
            val solver = CropModel(aspect, CropModelWeights(delta = 0.01)).solver(masks, 0.7f)
            val k = random.nextInt(1, 4)
            val start = IntArray(k) { random.nextInt(solver.positions) }
            val before = solver.total(start, solver.assign(start))
            val (windows, assignment) = solver.refine(start)
            assertTrue("$before -> ${solver.total(windows, assignment)}", solver.total(windows, assignment) <= before + 1e-9)
        }
    }

    @Test
    fun theSolutionBeatsAnySingleWindowAndItsReportedTotalIsItsCost() = runBlocking {
        val random = Random(11)
        val model = CropModel(aspect)
        repeat(25) {
            val masks = randomPaper(random)
            val crops = model.solve(masks, 0.7f)
            // Rescoring the solution's own windows gives the same total.
            val rescored = model.score(masks, crops.pages.map { crops.windows[it.window] })
            assertEquals(crops.total, rescored.total, 1e-9)
            // No single window, wherever it sits, does better.
            repeat(10) {
                val w = CropWindow(random.nextFloat() * 0.3f, random.nextFloat() * 0.3f, 0.7f)
                assertTrue(crops.total <= model.score(masks, List(masks.size) { w }).total + 1e-9)
            }
        }
    }
}
