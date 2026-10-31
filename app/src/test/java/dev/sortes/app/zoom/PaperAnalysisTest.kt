package dev.sortes.app.zoom

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PaperAnalysisTest {

    private val aspect = 0.75f

    /** The config without page furniture or marks, for tests of what's cut off. */
    private val keepFurniture = CropConfig.DEFAULT.copy(pageRules = IGNORABLE_INK_RULES, furniture = false)

    @Test
    fun samplesEveryPageOfShortPapers() {
        assertEquals(listOf(0, 1, 2), samplePages(3, 60))
        assertEquals(List(60) { it }, samplePages(60, 60))
    }

    @Test
    fun samplesLongPapersInEvenlySpacedFacingPairsIncludingBothEnds() {
        val sample = samplePages(300, 60)

        assertEquals(60, sample.size)
        assertEquals(0, sample.first())
        assertEquals(299, sample.last())
        assertEquals(sample.sorted().distinct(), sample)
        // Pairs (even, odd): a gap of 1 inside each, 9 or 11 between them.
        val gaps = sample.zipWithNext { a, b -> b - a }
        assertTrue("$gaps", gaps.filterIndexed { i, _ -> i % 2 == 0 }.all { it == 1 })
        assertTrue("$gaps", gaps.filterIndexed { i, _ -> i % 2 == 1 }.all { it in 8..11 })
    }

    @Test
    fun everyLongPaperIsSampledOnBothSidesAlternately() {
        // Plain even spacing put every sampled page of a 119-, 237-, 355- or 473-page paper on
        // the same side.
        for (count in 61..480) {
            val sample = samplePages(count, MAX_ANALYZED_PAGES)
            assertTrue("$count: ${sample.size}", sample.size in MAX_ANALYZED_PAGES - 1..MAX_ANALYZED_PAGES)
            assertEquals("$count", sample.sorted().distinct(), sample)
            assertEquals("$count", 0, sample.first())
            assertTrue("$count: ${sample.last()}", sample.last() >= count - 2 && sample.last() < count)
            // Even, odd, even, odd... so the model sees each side every other sampled page.
            assertTrue("$count: $sample", sample.withIndex().all { (i, page) -> page % 2 == i % 2 })
        }
    }

    @Test
    fun samplingEdgeCases() {
        assertEquals(listOf(0), samplePages(10, 1))
        assertEquals(emptyList<Int>(), samplePages(0, 5))
        assertThrows(IllegalArgumentException::class.java) { samplePages(10, 0) }
    }

    @Test
    fun longPapersRenderOnlyTheSample() {
        val pages = MaskPages(List(300) { textPage().mask() })

        val paper = runBlocking { analyzePaper(pages, aspect) }

        assertEquals(MAX_ANALYZED_PAGES, pages.rendered.size)
        // Each sampled page once at the analysis resolution and once finer, for its text.
        assertEquals(2 * MAX_ANALYZED_PAGES, pages.renders.size)
        assertEquals(MAX_ANALYZED_PAGES, paper.pagesAnalyzed)
        assertEquals(300, paper.pageCount)
        assertTrue("auto ${paper.auto.scale}", paper.auto.scale in 0.75f..0.80f)
    }

    @Test
    fun windowsCanBePlacedOnPagesOutsideTheSample() {
        // Page 4 isn't sampled (0, 1, 8, 9) but still gets a window that fits its text.
        val pages = MaskPages(List(10) { textPage().mask() })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }
        assertTrue(4 !in pages.rendered)

        val window = runBlocking { paper.place(4, paper.auto) }.window

        assertTrue("$window", window.covers(60, 80, aspect, 10, 10, 50, 70))
    }

    @Test
    fun placementsReportTheInkTheirWindowCutsOff() {
        // Text block with a running head and page number (cells are 10 points), zoomed in far
        // enough that both are cut off on every page, whether sampled or not.
        val page = textPage().rect(25, 2, 35, 4).rect(29, 76, 31, 78).mask()
        val pages = MaskPages(List(10) { page })
        val paper = runBlocking { analyzePaper(pages, aspect, keepFurniture, maxPages = 4) }
        val crops = runBlocking { paper.cropsAt(0.76f) }

        for (index in listOf(0, 1, 4)) {
            val placement = runBlocking { paper.place(index, crops) }

            assertEquals(crops.windows.single(), placement.window)
            assertEquals(1, crops.k)
            assertTrue("$placement", placement.window.covers(60, 80, aspect, 10, 10, 50, 70))
            val boxes = placement.cut.map { it.region }
            assertEquals("$boxes", 2, boxes.size)
            val (head, number) = boxes.sortedBy { it.top }
            assertTrue("$head", head.top < 40f && head.top + head.height <= 100f)
            assertTrue("$number", number.top >= 700f && number.top + number.height >= 770f)
        }
    }

    @Test
    fun placementsAtFullPageCutNothing() {
        val pages = MaskPages(List(3) { textPage().rect(29, 76, 31, 78).mask() })
        val paper = runBlocking { analyzePaper(pages, aspect) }

        val placement = runBlocking { paper.place(0, paper.cropsAt(1f)) }

        assertEquals(FullPage, placement.window)
        assertEquals(emptyList<CutInk>(), placement.cut)
    }

    @Test
    fun theArxivStampDoesNotPullTheFirstPageAside() {
        // Attention Is All You Need: page 1 has the arXiv stamp down its left margin, the rest
        // don't. Page 1's window should line up with the others, on the text, not the stamp.
        val pages = MaskPages(listOf(maskFixture("attention-p1.txt")) + List(9) { maskFixture("attention-page2.txt") })
        val paper = runBlocking { analyzePaper(pages, aspect) }

        val (first, second) = runBlocking { listOf(0, 1).map { paper.place(it, paper.auto).window } }

        assertEquals(second.left, first.left, 0.01f)
        // The text block spans columns 33-157 of the 192-cell page (the stamp is at 5-11).
        assertTrue("$first", first.covers(192, 248, aspect, 33, 120, 158, 121))
    }

    @Test
    fun theArxivStampIsNotShownAsCutOff() {
        // Zoomed in to the text block of Attention's page 1 (columns 33-157 of 192), the window
        // leaves the stamp (columns 5-11, or 50-120 points) out, but it's ignored, not cut.
        val pages = MaskPages(listOf(maskFixture("attention-p1.txt")) + List(3) { maskFixture("attention-page2.txt") })
        val paper = runBlocking { analyzePaper(pages, aspect) }
        val placement = runBlocking { paper.place(0, paper.cropsAt(0.7f)) }

        assertTrue("${placement.window}", placement.window.regionOn(PageSize(1920f, 2480f), aspect).left > 150f)
        assertTrue("${placement.cut}", placement.cut.none { it.region.left < 150f })
    }

    @Test
    fun unreadablePagesAreLeftOut() {
        val pages = MaskPages(List(5) { textPage().mask() }, broken = setOf(1, 3))

        val paper = runBlocking { analyzePaper(pages, aspect) }

        assertEquals(3, paper.pagesAnalyzed)
        assertEquals(listOf(0, 2, 4), paper.sample)
        assertTrue("auto ${paper.auto.scale}", paper.auto.scale in 0.75f..0.80f)
    }

    @Test
    fun failsWhenNoPageCanBeRead() {
        val pages = MaskPages(List(2) { textPage().mask() }, broken = setOf(0, 1))

        assertThrows(IOException::class.java) { runBlocking { analyzePaper(pages, aspect) } }
    }

    // A two-sided layout: text blocks alternate between the left and right of the page.
    private val leftPage = TestPage(60, 80).rect(2, 10, 38, 70).mask()
    private val rightPage = TestPage(60, 80).rect(22, 10, 58, 70).mask()

    @Test
    fun twoSidedPapersGetAWindowPerSideIncludingUnsampledPages() {
        val pages = MaskPages(List(12) { if (it % 2 == 0) leftPage else rightPage })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 6) }
        // Facing pairs: half the pages of each side are placed unsampled.
        assertEquals(listOf(0, 1, 4, 5, 10, 11), paper.sample)
        val crops = runBlocking { paper.cropsAt(0.75f) }
        assertEquals(2, crops.k)

        for (index in 0 until 12) {
            val placement = runBlocking { paper.place(index, crops) }
            assertEquals("page $index", emptyList<CutInk>(), placement.cut)
            val block = if (index % 2 == 0) 2 to 38 else 22 to 58
            assertTrue("page $index: ${placement.window}", placement.window.covers(60, 80, aspect, block.first, 10, block.second, 70))
        }
    }

    @Test
    fun cropsAtASavedScaleAreSolvedOnceAndKept() {
        val pages = MaskPages(List(4) { textPage().rect(29, 76, 31, 78).mask() })
        val paper = runBlocking { analyzePaper(pages, aspect) }
        val rendered = pages.renders.size

        val first = runBlocking { paper.cropsAt(0.8f) }
        val again = runBlocking { paper.cropsAt(0.8f) }

        assertEquals(0.8f, first.scale)
        assertTrue(first === again)
        assertTrue(runBlocking { paper.cropsAt(paper.auto.scale) } === paper.auto)
        // Solving doesn't render anything again.
        assertEquals(rendered, pages.renders.size)
    }

    @Test
    fun theCurveFillsInPointByPointAndIsKept() {
        val pages = MaskPages(List(3) { textPage().rect(29, 76, 31, 78).mask() })
        val paper = runBlocking { analyzePaper(pages, aspect, keepFurniture) }

        val curves = runBlocking { paper.curve().toList() }

        assertEquals(0, curves.first().points.size)
        val sizes = curves.map { it.points.size }
        assertEquals(sizes.sorted(), sizes)
        val last = curves.last()
        val scales = paper.scales
        val wanted = (scales.indices.filter { it % PaperAnalysis.CURVE_STRIDE == 0 } + last.nearestIndex(paper.auto.scale))
            .distinct().sorted()
        // Every wanted point, loosest first, up to the first cutting more than CURVE_LIMIT.
        val points = last.sortedPoints
        assertEquals(wanted.take(points.size), points.map { it.first })
        assertTrue(points.size < wanted.size)
        assertTrue(points.dropLast(1).all { it.second.lostFraction <= PaperAnalysis.CURVE_LIMIT })
        assertTrue(points.last().second.lostFraction > PaperAnalysis.CURVE_LIMIT)
        // Whole page: nothing cut. At auto: only the page number.
        assertEquals(0f, last.points.getValue(0).lostFraction)
        assertTrue(last.points.getValue(last.nearestIndex(paper.auto.scale)).lostFraction <= 0.01f)

        // A second collection starts from the kept points and solves no more.
        assertEquals(last.points.keys, runBlocking { paper.curve().first() }.points.keys)
        assertEquals(last.points.keys, runBlocking { paper.curve().toList() }.last().points.keys)
    }

    @Test
    fun anOddNumberOfAlternatingPagesKeepsBothSidesText() {
        // 11 pages sampled 6 at a time used to sample 0, 2, 4, 6, 8, 10: left pages only, one
        // window fitted to them, and every right page's text cut while the summary said nothing
        // was cut.
        val pages = MaskPages(List(11) { if (it % 2 == 0) leftPage else rightPage })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 6) }

        assertEquals(2, paper.auto.k)
        assertEquals(0L, paper.auto.cutCells)
        for (index in 0 until 11) {
            val placement = runBlocking { paper.place(index, paper.auto) }
            assertEquals("page $index", emptyList<CutInk>(), placement.cut)
        }
    }

    @Test
    fun anUnsampledPageThatFitsNoWindowGetsItsOwn() {
        // Pages 0-9 share a centred text block; page 7, unsampled, has its block near the right
        // edge. Both windows the sample chose would cut it; sliding keeps it all.
        val usual = TestPage(60, 80).rect(14, 10, 46, 70).mask()
        val offset = TestPage(60, 80).rect(26, 10, 58, 70).mask()
        val pages = MaskPages(List(10) { if (it == 7) offset else usual })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }
        assertTrue(7 !in paper.sample)
        val crops = runBlocking { paper.cropsAt(0.75f) }

        val placement = runBlocking { paper.place(7, crops) }

        assertEquals(emptyList<CutInk>(), placement.cut)
        assertTrue("${placement.window}", placement.window.covers(60, 80, aspect, 26, 10, 58, 70))
        // Same zoom as every other page.
        assertEquals(crops.scale, placement.window.scale)
        assertTrue(crops.windows.none { it == placement.window })
    }

    @Test
    fun theDefaultConfigDropsFurnitureOnEveryPage() {
        // A page number under every page's text: ignored everywhere, on sampled pages and the
        // rest alike; without the furniture rule it would be cut.
        val numbered = TestPage(60, 80).rect(10, 10, 50, 70).rect(29, 76, 31, 78).mask()
        val pages = MaskPages(List(10) { numbered })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }
        val kept = runBlocking { analyzePaper(pages, aspect, keepFurniture, maxPages = 4) }
        assertEquals(CropConfig.DEFAULT, paper.config)
        val scale = 0.76f
        for (index in listOf(0, 4)) {
            assertEquals("page $index", emptyList<CutInk>(), runBlocking { paper.place(index, paper.cropsAt(scale)) }.cut)
            assertEquals("page $index", 1, runBlocking { kept.place(index, kept.cropsAt(scale)) }.cut.size)
        }
    }

    @Test
    fun unsampledPagesAreMeasuredForTheirTextWhenPlaced() {
        val pages = MaskPages(List(10) { textPage().mask() })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }
        val before = pages.renders.size

        runBlocking { paper.place(4, paper.auto) }

        val coarse = pages.renders.first { it.index == 0 }
        assertEquals(
            listOf(MaskPages.Render(4, coarse.width, coarse.height), MaskPages.Render(4, coarse.width * 4, coarse.height * 4)),
            pages.renders.drop(before),
        )
    }

    @Test
    fun withoutTextTheZoomIsTheLinearOne() {
        // Solid blocks: no glyphs to measure, so zeta·scale chooses the zoom.
        val pages = MaskPages(List(3) { textPage().mask() })
        val paper = runBlocking { analyzePaper(pages, aspect) }
        assertEquals(null, paper.glyphPx)
        val masks = runBlocking { paper.placeAll(paper.auto) }.masks
        val linear = runBlocking { CropModel(aspect, CropConfig.DEFAULT.weights).auto(masks) }
        assertEquals(linear.scale, paper.auto.scale)
    }

    @Test
    fun biggerTextOnTheSameLayoutNeverZoomsTighter() {
        // The same column at both sizes: 10 pt glyphs need no zoom to be legible, 3 pt ones do.
        val small = runBlocking { analyzePaper(TextPages(3, glyph = 3f, pitch = 6f, lines = 100), aspect) }
        val big = runBlocking { analyzePaper(TextPages(3, glyph = 10f, pitch = 20f, lines = 30), aspect) }
        assertTrue("small ${small.glyphPx} big ${big.glyphPx}", small.glyphPx!! < big.glyphPx!!)
        assertTrue("small ${small.auto.scale} big ${big.auto.scale}", big.auto.scale >= small.auto.scale)
    }

    @Test
    fun glyphHeightIsMeasuredOnTheReferenceScreen() {
        // 10 pt glyphs on a 600 pt wide page whose frame is the page (3:4): the glyph is 1/60 of
        // the frame's width, so about 31 px of the 1860 px screen (rendering rounds to pixels).
        val paper = runBlocking { analyzePaper(TextPages(3, glyph = 10f, pitch = 20f, lines = 30), aspect) }
        assertEquals(1860.0 / 60, paper.glyphPx!!, 2.0)
    }

    @Test
    fun placeAllPlacesEveryPageAsPlaceDoes() {
        // Pages 0-9 share a centred block; page 7, unsampled, needs a window of its own.
        val usual = TestPage(60, 80).rect(14, 10, 46, 70).mask()
        val offset = TestPage(60, 80).rect(26, 10, 58, 70).mask()
        val pages = MaskPages(List(10) { if (it == 7) offset else usual })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }
        val crops = runBlocking { paper.cropsAt(0.75f) }

        val all = runBlocking { paper.placeAll(crops) }

        assertEquals(10, all.crops.pages.size)
        assertEquals(crops.k + 1, all.crops.k)
        assertEquals(all.crops.k - 1, all.crops.pages[7].windowIndex)
        assertEquals(all.masks.sumOf { it.inkCount.toLong() }, all.crops.totalInk)
        for (i in 0 until 10) assertEquals("page $i", runBlocking { paper.place(i, crops) }.window, all.crops.pages[i].window)
    }

    @Test
    fun unsampledPagesAreRenderedOnceForSeveralPlacements() {
        val pages = MaskPages(List(10) { textPage().mask() })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }
        runBlocking { paper.place(4, paper.auto) }
        val after = pages.renders.size

        runBlocking { paper.place(4, paper.auto) }
        runBlocking { paper.place(4, paper.cropsAt(0.8f)) }

        assertEquals(after, pages.renders.size)
    }

    @Test
    fun pagesWhoseFineRenderFailsAreSolvedWithoutText() {
        // Every 4x render fails: the paper is still analysed and placed, sampled pages or not.
        val pages = MaskPages(List(10) { textPage().mask() }, maxPixels = 100_000)
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }

        assertEquals(4, paper.pagesAnalyzed)
        assertEquals(0, paper.pagesMeasured)
        assertEquals(null, paper.glyphPx)
        assertTrue("${paper.auto.scale}", paper.auto.scale in 0.75f..0.80f)
        val placement = runBlocking { paper.place(4, paper.auto) }
        assertEquals(emptyList<CutInk>(), placement.cut)
        assertEquals(null, runBlocking { paper.page(4) }.text)
    }

    @Test
    fun aRendererReturningTheWrongNumberOfPixelsIsABug() {
        val wrong = object : PageRenderer {
            override val pageCount = 2
            override suspend fun pageSize(index: Int) = PageSize(600f, 800f)
            override suspend fun renderPixels(index: Int, width: Int, height: Int) = IntArray(width * height - 1)
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { analyzePaper(wrong, aspect) } }
    }

    @Test
    fun concurrentPlacementsAgreeWithOneAtATime() {
        // As when a page turn cancels a placement whose computation still runs.
        val pages = MaskPages(List(12) { if (it % 2 == 0) leftPage else rightPage })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }
        val crops = runBlocking { paper.cropsAt(0.75f) }
        val alone = (0 until 12).map { runBlocking { paper.place(it, crops) } }
        val fresh = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }
        val freshCrops = runBlocking { fresh.cropsAt(0.75f) }
        val together = runBlocking {
            (0 until 12).flatMap { i -> List(3) { async(Dispatchers.Default) { i to fresh.place(i, freshCrops) } } }.awaitAll()
        }
        for ((i, placement) in together) assertEquals("page $i", alone[i], placement)
    }

    @Test
    fun cancellingTheAnalysisStopsIt() {
        val gate = CompletableDeferred<Unit>()
        val pages = object : PageRenderer {
            val inner = MaskPages(List(10) { textPage().mask() })
            override val pageCount = 10
            override suspend fun pageSize(index: Int) = inner.pageSize(index)
            override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray {
                if (index == 2) { gate.complete(Unit); awaitCancellation() }
                return inner.renderPixels(index, width, height)
            }
        }
        runBlocking {
            val job = launch(Dispatchers.Default) { analyzePaper(pages, aspect) }
            gate.await()
            job.cancel()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
        }
    }
}
