package dev.sortes.app.zoom

import dev.sortes.app.ui.FakePages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

// FakePages draws with android.graphics, hence Robolectric.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperAnalysisTest {

    private val aspect = 0.75f

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
        val pages = FakePages(List(300) { textPage().mask() })

        val paper = runBlocking { analyzePaper(pages, aspect) }

        assertEquals(MAX_ANALYZED_PAGES, pages.masked.size)
        assertEquals(MAX_ANALYZED_PAGES, paper.pagesAnalyzed)
        assertEquals(300, paper.pageCount)
        assertTrue("auto ${paper.auto.scale}", paper.auto.scale in 0.75f..0.80f)
    }

    @Test
    fun windowsCanBePlacedOnPagesOutsideTheSample() {
        // Page 4 isn't sampled (0, 1, 8, 9) but still gets a window that fits its text.
        val pages = FakePages(List(10) { textPage().mask() })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }
        assertTrue(4 !in pages.masked)

        val window = runBlocking { paper.place(pages, 4, paper.auto) }.window

        assertTrue("$window", window.covers(60, 80, aspect, 10, 10, 50, 70))
    }

    @Test
    fun placementsReportTheInkTheirWindowCutsOff() {
        // Text block with a running head and page number (cells are 10 points), zoomed in far
        // enough that both are cut off on every page, whether sampled or not.
        val page = textPage().rect(25, 2, 35, 4).rect(29, 76, 31, 78).mask()
        val pages = FakePages(List(10) { page })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }
        val crops = runBlocking { paper.cropsAt(0.76f) }

        for (index in listOf(0, 1)) {
            val placement = runBlocking { paper.place(pages, index, crops) }

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
        val pages = FakePages(List(3) { textPage().rect(29, 76, 31, 78).mask() })
        val paper = runBlocking { analyzePaper(pages, aspect) }

        val placement = runBlocking { paper.place(pages, 0, paper.cropsAt(1f)) }

        assertEquals(FullPage, placement.window)
        assertEquals(emptyList<CutInk>(), placement.cut)
    }

    @Test
    fun theArxivStampDoesNotPullTheFirstPageAside() {
        // Attention Is All You Need: page 1 has the arXiv stamp down its left margin, the rest
        // don't. Page 1's window should line up with the others, on the text, not the stamp.
        val pages = FakePages(listOf(maskFixture("attention-p1.txt")) + List(9) { maskFixture("attention-page2.txt") })
        val paper = runBlocking { analyzePaper(pages, aspect) }

        val (first, second) = runBlocking { listOf(0, 1).map { paper.place(pages, it, paper.auto).window } }

        assertEquals(second.left, first.left, 0.01f)
        // The text block spans columns 33-157 of the 192-cell page (the stamp is at 5-11).
        assertTrue("$first", first.covers(192, 248, aspect, 33, 120, 158, 121))
    }

    @Test
    fun theArxivStampIsNotShownAsCutOff() {
        // Zoomed in to the text block of Attention's page 1 (columns 33-157 of 192), the window
        // leaves the stamp (columns 5-11, or 50-120 points) out, but it's ignored, not cut.
        val pages = FakePages(listOf(maskFixture("attention-p1.txt")) + List(3) { maskFixture("attention-page2.txt") })
        val paper = runBlocking { analyzePaper(pages, aspect) }
        val placement = runBlocking { paper.place(pages, 0, paper.cropsAt(0.7f)) }

        assertTrue("${placement.window}", placement.window.regionOn(PageSize(1920f, 2480f), aspect).left > 150f)
        assertTrue("${placement.cut}", placement.cut.none { it.region.left < 150f })
    }

    @Test
    fun unreadablePagesAreLeftOut() {
        val pages = FakePages(List(5) { textPage().mask() }, broken = setOf(1, 3))

        val paper = runBlocking { analyzePaper(pages, aspect) }

        assertEquals(3, paper.pagesAnalyzed)
        assertEquals(listOf(0, 2, 4), paper.sample)
        assertTrue("auto ${paper.auto.scale}", paper.auto.scale in 0.75f..0.80f)
    }

    @Test
    fun failsWhenNoPageCanBeRead() {
        val pages = FakePages(List(2) { textPage().mask() }, broken = setOf(0, 1))

        assertThrows(IOException::class.java) { runBlocking { analyzePaper(pages, aspect) } }
    }

    // A two-sided layout: text blocks alternate between the left and right of the page.
    private val leftPage = TestPage(60, 80).rect(2, 10, 38, 70).mask()
    private val rightPage = TestPage(60, 80).rect(22, 10, 58, 70).mask()

    @Test
    fun twoSidedPapersGetAWindowPerSideIncludingUnsampledPages() {
        val pages = FakePages(List(12) { if (it % 2 == 0) leftPage else rightPage })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 6) }
        // Facing pairs: half the pages of each side are placed unsampled.
        assertEquals(listOf(0, 1, 4, 5, 10, 11), paper.sample)
        val crops = runBlocking { paper.cropsAt(0.75f) }
        assertEquals(2, crops.k)

        for (index in 0 until 12) {
            val placement = runBlocking { paper.place(pages, index, crops) }
            assertEquals("page $index", emptyList<CutInk>(), placement.cut)
            val block = if (index % 2 == 0) 2 to 38 else 22 to 58
            assertTrue("page $index: ${placement.window}", placement.window.covers(60, 80, aspect, block.first, 10, block.second, 70))
        }
    }

    @Test
    fun cropsAtASavedScaleAreSolvedOnceAndKept() {
        val pages = FakePages(List(4) { textPage().rect(29, 76, 31, 78).mask() })
        val paper = runBlocking { analyzePaper(pages, aspect) }
        val masked = pages.masked.size

        val first = runBlocking { paper.cropsAt(0.8f) }
        val again = runBlocking { paper.cropsAt(0.8f) }

        assertEquals(0.8f, first.scale)
        assertTrue(first === again)
        assertTrue(runBlocking { paper.cropsAt(paper.auto.scale) } === paper.auto)
        // Solving doesn't render anything again.
        assertEquals(masked, pages.masked.size)
    }

    @Test
    fun theCurveFillsInPointByPointAndIsKept() {
        val pages = FakePages(List(3) { textPage().rect(29, 76, 31, 78).mask() })
        val paper = runBlocking { analyzePaper(pages, aspect) }

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
        // Whole page: nothing cut. At auto: within the budget.
        assertEquals(0f, last.points.getValue(0).lostFraction)
        assertTrue(last.points.getValue(last.nearestIndex(paper.auto.scale)).lostFraction <= CropAnalysis.AUTO_MAX_LOST)

        // A second collection starts from the kept points and solves no more.
        assertEquals(last.points.keys, runBlocking { paper.curve().first() }.points.keys)
        assertEquals(last.points.keys, runBlocking { paper.curve().toList() }.last().points.keys)
    }

    @Test
    fun anOddNumberOfAlternatingPagesKeepsBothSidesText() {
        // 11 pages sampled 6 at a time used to sample 0, 2, 4, 6, 8, 10: left pages only, one
        // window fitted to them, and every right page's text cut while the summary said nothing
        // was cut.
        val pages = FakePages(List(11) { if (it % 2 == 0) leftPage else rightPage })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 6) }

        assertEquals(2, paper.auto.k)
        assertEquals(0L, paper.auto.cutCells)
        for (index in 0 until 11) {
            val placement = runBlocking { paper.place(pages, index, paper.auto) }
            assertEquals("page $index", emptyList<CutInk>(), placement.cut)
        }
    }

    @Test
    fun anUnsampledPageThatFitsNoWindowGetsItsOwn() {
        // Pages 0-9 share a centred text block; page 7, unsampled, has its block near the right
        // edge. Both windows the sample chose would cut it; sliding keeps it all.
        val usual = TestPage(60, 80).rect(14, 10, 46, 70).mask()
        val offset = TestPage(60, 80).rect(26, 10, 58, 70).mask()
        val pages = FakePages(List(10) { if (it == 7) offset else usual })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }
        assertTrue(7 !in paper.sample)
        val crops = runBlocking { paper.cropsAt(0.75f) }

        val placement = runBlocking { paper.place(pages, 7, crops) }

        assertEquals(emptyList<CutInk>(), placement.cut)
        assertTrue("${placement.window}", placement.window.covers(60, 80, aspect, 26, 10, 58, 70))
        // Same zoom as every other page.
        assertEquals(crops.scale, placement.window.scale)
        assertTrue(crops.windows.none { it == placement.window })
    }

    @Test
    fun theReaderUsesTheReaderConfigAndATunedConfigDropsFurniture() {
        // A page number under every page's text: the reader keeps it in play, the tuned config
        // ignores it everywhere, on sampled pages and the rest alike.
        val numbered = TestPage(60, 80).rect(10, 10, 50, 70).rect(29, 76, 31, 78).mask()
        val pages = FakePages(List(10) { numbered })
        val reader = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }
        val tuned = runBlocking { analyzePaper(pages, aspect, maxPages = 4, config = CropConfig.TUNED) }
        assertEquals(CropConfig.READER.weights, reader.model.weights)
        assertEquals(CropConfig.TUNED.weights, tuned.model.weights)
        val scale = 0.76f
        for (index in listOf(0, 4)) {
            val r = runBlocking { reader.place(pages, index, reader.cropsAt(scale)) }
            val t = runBlocking { tuned.place(pages, index, tuned.cropsAt(scale)) }
            assertEquals("reader, page $index", 1, r.cut.size)
            assertEquals("tuned, page $index", emptyList<CutInk>(), t.cut)
        }
    }
}
