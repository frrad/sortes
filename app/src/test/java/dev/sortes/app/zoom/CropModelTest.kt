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
        assertTrue(crops.pages.all { it.windowIndex == 0 })
        assertEquals(8L * 6, crops.cutCells)
    }

    @Test
    fun aTwoSidedBookGetsOneWindowPerSide() {
        val crops = solve(List(8) { if (it % 2 == 0) left else right }, 0.75f)
        assertEquals(2, crops.k)
        assertEquals(0L, crops.cutCells)
        assertArrayEquals(IntArray(8) { it % 2 }, crops.pages.map { it.windowIndex }.toIntArray())
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
        assertEquals(crops.pages[1].windowIndex, crops.pages[2].windowIndex)
    }

    @Test
    fun blankPagesContinueAnAlternation() {
        val crops = solve(listOf(left, right, page(), right), 0.75f)
        assertEquals(crops.pages[0].windowIndex, crops.pages[2].windowIndex)
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
        assertEquals(crops.pages[0].windowIndex, model.assign(left, crops, ref).windowIndex)
        assertEquals(crops.pages[1].windowIndex, model.assign(right, crops, ref).windowIndex)
        // A blank page has no preference: it takes the one suggested.
        assertEquals(1, model.assign(page(), crops, ref, preferred = 1).windowIndex)
    }

    @Test
    fun autoKeepsTheModelsCutWithinTheReadersBudget() = runBlocking {
        val masks = List(5) { page(body, pageNumber) } + page(rect(10, 30, 50, 60), pageNumber)
        val crops = CropModel(aspect).auto(masks)
        assertTrue(crops.cutFraction <= CropAnalysis.AUTO_MAX_LOST)
        // Tight enough to cut the page numbers (0.3% of the ink), so tighter than the whole page.
        assertTrue(crops.scale < 0.9f)
    }

    @Test
    fun aBlankPaperStaysWhole() = runBlocking {
        val masks = listOf(page(), page())
        val crops = CropModel(aspect).auto(masks)
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

    /**
     * Random pages of mixed sizes, so some are padded at the sides and some above and below, by
     * odd and even numbers of cells, with a few random ink rectangles.
     */
    private fun randomPaper(random: Random, pages: Int = random.nextInt(2, 9)): List<InkMask> = List(pages) {
        val w = random.nextInt(50, 71)
        val h = random.nextInt(64, 91)
        val p = TestPage(w, h)
        repeat(random.nextInt(0, 6)) {
            val l = random.nextInt(0, w - 4)
            val t = random.nextInt(0, h - 4)
            p.rect(l, t, (l + random.nextInt(1, 30)).coerceAtMost(w), (t + random.nextInt(1, 30)).coerceAtMost(h))
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
            val rescored = model.score(masks, crops.pages.map { crops.windows[it.windowIndex] })
            assertEquals(crops.total, rescored.total, 1e-9)
            // No single window, wherever it sits, does better.
            repeat(10) {
                val w = CropWindow(random.nextFloat() * 0.3f, random.nextFloat() * 0.3f, 0.7f)
                assertTrue(crops.total <= model.score(masks, List(masks.size) { w }).total + 1e-9)
            }
        }
    }

    @Test
    fun theAssignmentStepIsOptimal() = runBlocking {
        // Against brute force over every assignment, for each kind of penalty.
        val random = Random(17)
        for ((delta, delta2) in listOf(0.0 to 0.0, 0.03 to 0.0, 0.0 to 0.04, 0.03 to 0.04)) {
            repeat(30) {
                val masks = randomPaper(random, pages = random.nextInt(1, 8))
                val solver = CropModel(aspect, CropModelWeights(delta = delta, delta2 = delta2)).solver(masks, 0.7f)
                val k = random.nextInt(1, 4)
                val windows = IntArray(k) { random.nextInt(solver.positions) }
                val n = masks.size
                var best = Double.MAX_VALUE
                val assignment = IntArray(n)
                fun search(p: Int) {
                    if (p == n) {
                        best = minOf(best, solver.pathCost(windows, assignment))
                        return
                    }
                    for (j in 0 until k) {
                        assignment[p] = j
                        search(p + 1)
                    }
                }
                search(0)
                val chosen = solver.assign(windows)
                assertEquals("δ=$delta δ₂=$delta2 n=$n k=$k", best, solver.pathCost(windows, chosen), 1e-9)
            }
        }
    }

    @Test
    fun pagesOfOtherSizesRenderOnTheCellsTheModelCounted() = runBlocking {
        // 57 x 80 and 58 x 80 both pad to 60 x 80 cells at 3:4, but by 3 and 2 cells: the shared
        // window must land on each page's own cells.
        val masks = listOf(
            TestPage(57, 80).rect(8, 10, 49, 70).mask(),
            TestPage(58, 80).rect(8, 10, 50, 70).mask(),
            TestPage(57, 80).rect(8, 10, 49, 70).mask(),
            TestPage(61, 80).rect(9, 10, 52, 70).mask(),
        )
        for (scale in listOf(0.6f, 0.75f, 0.85f)) {
            val crops = CropModel(aspect).solve(masks, scale)
            crops.pages.forEachIndexed { p, crop ->
                val mask = masks[p]
                val r = crop.window.regionOn(PageSize(mask.width.toFloat(), mask.height.toFloat()), aspect)
                val c = crop.cells
                val clampedX = crop.window.left == 0f || crop.window.left == 1f - scale
                val clampedY = crop.window.top == 0f || crop.window.top == 1f - scale
                assertEquals("page $p x at $scale", (c.left + c.right) / 2f, r.left + r.width / 2, if (clampedX) 0.5f else 0.02f)
                assertEquals("page $p y at $scale", (c.top + c.bottom) / 2f, r.top + r.height / 2, if (clampedY) 0.5f else 0.02f)
                // The shared window maps back to the same cells.
                assertEquals(crop.cells, CropModel(aspect).score(listOf(mask), listOf(crops.windows[crop.windowIndex])).pages[0].cells)
            }
        }
    }

    @Test
    fun aBlankPaperBelowFullPageIsCentred() = runBlocking {
        val crops = CropModel(aspect).solve(listOf(page(), page()), 0.5f)
        assertEquals(1, crops.k)
        assertEquals(0.25f, crops.windows[0].left, 0.01f)
        assertEquals(0.25f, crops.windows[0].top, 0.01f)
    }

    @Test
    fun offCentresForAllPositionsMatchTheSearchPerPosition() {
        val random = Random(23)
        repeat(20) {
            val mask = randomPaper(random, pages = 1).single()
            val page = PaddedPage(mask, aspect)
            val scale = 0.4f + random.nextFloat() * 0.6f
            val w = page.windowWidth(scale)
            val h = page.windowHeight(scale)
            val cols = page.width - w + 1
            val out = DoubleArray(cols * (page.height - h + 1))
            page.offCentres(w, h, out)
            for (y in 0..page.height - h) for (x in 0 until cols) {
                assertEquals("($x, $y) at $scale", page.offCentre(x, y, w, h), out[y * cols + x], 1e-12)
            }
        }
    }

    @Test
    fun theBisectedBoundIsTheFullAnalysisBound() = runBlocking {
        // The bound auto starts from must be exactly the tightest scale at which CropAnalysis'
        // whole curve is within budget.
        val random = Random(29)
        repeat(25) {
            val masks = randomPaper(random)
            val analysis = CropAnalysis.of(masks, aspect)
            val expected = if (analysis.totalInk == 0L) 0 else
                analysis.scales.indices.last { analysis.lostFraction(it) <= CropAnalysis.AUTO_MAX_LOST }
            assertEquals(expected, CropModel(aspect).prepare(masks).bound(analysis.scales))
        }
    }

    @Test
    fun autoIsWithinBudgetAndNoTighterThanTheBound() = runBlocking {
        val random = Random(31)
        repeat(10) {
            val masks = randomPaper(random)
            val prepared = CropModel(aspect).prepare(masks)
            val scales = CropAnalysis.defaultScales()
            val crops = prepared.auto(scales)
            assertTrue(crops.scale >= scales[prepared.bound(scales)] - 1e-6f)
            assertTrue(crops.cutFraction <= CropAnalysis.AUTO_MAX_LOST + 1e-9)
        }
    }

    @Test
    fun aPagesOwnCropIsItsBestPositionAtTheSameScale() {
        val offset = page(rect(26, 10, 58, 70))
        val own = CropModel(aspect).ownCrop(offset, 0.75f, 2000.0)
        assertEquals(CropModel.OWN_WINDOW, own.windowIndex)
        assertEquals(0, own.cutCells)
        assertEquals(0.75f, own.window.scale)
    }
}

class CropModelBlocksTest {
    private val aspect = 0.75f

    private fun page(vararg rects: IntArray): InkMask {
        val p = TestPage(60, 80)
        for ((l, t, r, b) in rects) p.rect(l, t, r, b)
        return p.mask()
    }

    private fun rect(l: Int, t: Int, r: Int, b: Int) = intArrayOf(l, t, r, b)

    private fun solve(masks: List<InkMask>, scale: Float, weights: CropModelWeights) =
        runBlocking { CropModel(aspect, weights).solve(masks, scale) }

    // The per-page convex term is off here, so the trade-offs are cut ink against blocks alone.
    private fun weights(kappa: Double, zeta: Double = 0.0) = CropModelWeights(epsilon = 0.0, kappa = kappa, zeta = zeta, autoMargin = 0.0)

    /** Text at rows 10-50, a sparse figure (an outline) at rows 54-66, and a dense footnote at 70-76: 66 rows in all. */
    private val figurePage = page(rect(10, 10, 50, 50), rect(10, 54, 50, 55), rect(10, 54, 11, 66), rect(49, 54, 50, 66), rect(10, 65, 50, 66), rect(10, 70, 50, 76))

    @Test
    fun cuttingThroughABlockCostsMoreThanDroppingAWholeOne() {
        // A 60-row window can't hold all 66. By ink alone it cuts two rows off the text and two
        // off the footnote (160 cells) rather than drop the whole footnote (240); with kappa it
        // keeps the text and figure whole and drops the footnote cleanly.
        val byInk = solve(listOf(figurePage), 0.75f, weights(kappa = 0.0))
        assertTrue("${byInk.pages[0]}", byInk.pages[0].cells.top > 10)
        val byBlocks = solve(listOf(figurePage), 0.75f, weights(kappa = 0.2))
        val crop = byBlocks.pages[0]
        assertEquals("$crop", 0, crop.splitBlocks)
        // Text and figure whole, and centred in the window: rows 8-67.
        assertTrue("$crop", crop.cells.top <= 10 && crop.cells.bottom >= 66)
        assertEquals(240, crop.cutCells)
    }

    @Test
    fun theSplitTermIsPartOfTheTotal() {
        val weights = weights(kappa = 0.2, zeta = 1.0)
        // A 48-row window must cut through the 40-row text or more.
        val crops = solve(listOf(figurePage), 0.6f, weights)
        assertTrue(crops.splitBlocks >= 1)
        assertEquals(0.2 * crops.pages.sumOf { it.split }, crops.splitTerm, 1e-12)
        assertTrue(crops.pages[0].split > 0.0 && crops.pages[0].split <= crops.splitBlocks)
        assertEquals(crops.cutTerm + crops.offTerm + crops.pageCutTerm + crops.splitTerm + crops.windowTerm + crops.switchTerm, crops.total, 1e-9)
        assertEquals(crops.total / 1 + 1.0 * 0.6, crops.zoomObjective, 1e-6)
    }

    @Test
    fun theCostBasedZoomTightensUntilBlocksSplit() = runBlocking {
        // Every page: a text block of rows 10-70, 60 of the frame's 80 rows. Tightening past it
        // splits the text on every page, so the zoom stops at the tightest scale whose window
        // still has 60 rows.
        val masks = List(6) { page(rect(10, 10, 50, 70)) }
        val crops = CropModel(aspect, weights(kappa = 0.2, zeta = 1.0)).auto(masks)
        assertEquals(0, crops.splitBlocks)
        assertEquals(0L, crops.cutCells)
        assertEquals(60, crops.pages[0].cells.bottom - crops.pages[0].cells.top)
        assertTrue("${crops.scale}", crops.scale <= 0.75f)
    }

    @Test
    fun theCostBasedZoomSacrificesOnePageForTheRest() = runBlocking {
        // One page's block (64 rows) is taller than the others' (56). Keeping it whole costs every
        // page 8 rows of zoom; splitting it costs kappa plus its cut ink, on one page.
        val usual = page(rect(10, 10, 50, 66))
        val tall = page(rect(10, 6, 50, 70))
        val masks = List(12) { usual } + tall
        val keep = CropModel(aspect, weights(kappa = 0.2, zeta = 1.0)).auto(masks)
        assertEquals(56, keep.pages[0].cells.bottom - keep.pages[0].cells.top)
        assertEquals(1, keep.pagesSplit)
        val whole = CropModel(aspect, weights(kappa = 5.0, zeta = 1.0)).auto(masks)
        assertEquals(64, whole.pages[0].cells.bottom - whole.pages[0].cells.top)
        assertEquals(0, whole.pagesSplit)
    }

    @Test
    fun aNibbleOffABlockIsCheaperThanAWholeSplit() = runBlocking {
        // Every page: a 40-wide text block and a 44-wide figure. The figure fits a window of 44
        // columns (scale 0.735); a tighter zoom nibbles a few of its columns. With a large
        // splitInk the nibble is a small share of a split, so the legibility reward wins and the
        // zoom tightens past the figure; with a small one every cut is a whole split, so the zoom
        // stops where the figure fits.
        val masks = List(6) { page(rect(10, 10, 50, 35), rect(8, 40, 52, 60)) }
        val nibble = CropModel(aspect, weights(kappa = 0.2, zeta = 2.0).copy(splitInk = 0.5)).auto(masks)
        assertTrue("${nibble.scale}", nibble.scale <= 0.7f)
        assertTrue("${nibble.pages[0]}", nibble.pages[0].splitBlocks >= 1 && nibble.pages[0].split < 0.3)
        val whole = CropModel(aspect, weights(kappa = 0.2, zeta = 2.0).copy(splitInk = 0.005)).auto(masks)
        assertEquals(0, whole.splitBlocks)
        // The tightest window with the figure's 44 columns (0.725 rounds to 44 too).
        assertEquals(44, whole.pages[0].cells.right - whole.pages[0].cells.left)
        assertTrue("${whole.scale}", whole.scale >= 0.72f)
    }

    @Test
    fun readerWeightsIgnoreBlocks() {
        val crops = solve(listOf(figurePage), 0.6f, CropModelWeights())
        assertEquals(0, crops.splitBlocks)
        assertEquals(0.0, crops.splitTerm, 0.0)
    }
}
