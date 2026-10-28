package dev.sortes.app.zoom

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CropAnalysisTest {

    private val aspect = 0.75f
    private lateinit var masks: List<InkMask>

    private fun analyze(masks: List<InkMask>, aspect: Float = this.aspect): CropAnalysis {
        this.masks = masks
        return runBlocking { CropAnalysis.of(masks, aspect) }
    }

    /** Places windows on every page of the last [analyze], as the reader does page by page. */
    private fun CropAnalysis.placements(scale: Float): List<CropWindow> = runBlocking {
        val anchor = anchor(scale)
        masks.map { placeWindow(it, aspect, scale, anchor) }
    }

    @Test
    fun nothingIsLostUntilTheWindowIsSmallerThanTheText() {
        val a = analyze(listOf(textPage().mask()))

        // The 40x60 block fits a 3:4 window down to scale 60/80 = 0.75.
        for (i in a.scales.indices) {
            val s = a.scales[i]
            if (s >= 0.75f) assertEquals("scale $s", 0L, a.lostInk[i])
            if (s < 0.74f) assertTrue("scale $s", a.lostInk[i] > 0)
        }
    }

    @Test
    fun lostInkNeverShrinksAsTheWindowShrinks() {
        val random = Random(42)
        val masks = List(5) {
            val page = TestPage(48, 64)
            repeat(30) {
                val x = random.nextInt(44)
                val y = random.nextInt(60)
                page.rect(x, y, x + 1 + random.nextInt(4), y + 1 + random.nextInt(4))
            }
            page.mask()
        }
        val a = analyze(masks)

        for (i in 1 until a.scales.size) {
            assertTrue("scale ${a.scales[i]}", a.lostInk[i] >= a.lostInk[i - 1])
        }
        assertEquals(0L, a.lostInk[0])
        assertEquals(1f, a.scales[0])
    }

    @Test
    fun lostFractionIsRelativeToAllInkInThePaper() {
        val a = analyze(listOf(textPage().mask(), textPage().mask()))

        assertEquals(2L * 40 * 60, a.totalInk)
        val i = a.scales.indices.first { a.lostInk[it] > 0 }
        assertEquals(a.lostInk[i].toFloat() / a.totalInk, a.lostFraction(i), 1e-6f)
        assertEquals(2, a.pagesCropped(i))
    }

    @Test
    fun autoZoomFitsTheTextBlockSnugly() {
        val a = analyze(List(3) { textPage().mask() })

        assertTrue("auto ${a.autoScale}", a.autoScale in 0.75f..0.80f)
        for (window in a.placements(a.autoScale)) {
            assertTrue("$window", window.covers(60, 80, aspect, 10, 10, 50, 70))
        }
    }

    @Test
    fun autoZoomCutsOffPageNumbers() {
        // A page number well below the text: keeping it would need scale (78 - 10) / 80 = 0.85.
        val pages = List(10) { textPage().rect(29, 76, 31, 78).mask() }

        val a = analyze(pages)

        assertTrue("auto ${a.autoScale}", a.autoScale < 0.8f)
        for (window in a.placements(a.autoScale)) {
            assertTrue("$window", window.covers(60, 80, aspect, 10, 10, 50, 70))
        }
    }

    @Test
    fun autoZoomDoesNotCutIntoText() {
        // Two pages of text with different, tight margins: no slack beyond the text.
        val pages = listOf(
            TestPage(60, 80).rect(5, 2, 55, 78).mask(),
            TestPage(60, 80).rect(4, 3, 56, 77).mask(),
        )

        val a = analyze(pages)

        assertTrue("auto ${a.autoScale}", a.autoScale >= 76f / 80)
    }

    @Test
    fun blankPaperIsNotZoomed() {
        val a = analyze(List(3) { TestPage(60, 80).mask() })

        assertEquals(0L, a.totalInk)
        assertEquals(0f, a.lostFraction(a.scales.lastIndex))
        assertEquals(1f, a.autoScale)
        assertEquals(List(3) { CropWindow(0f, 0f, 1f) }, a.placements(1f))
    }

    @Test
    fun windowsStayInsideThePaddedFrame() {
        val pages = listOf(
            TestPage(60, 80).rect(0, 0, 3, 3).mask(),
            TestPage(60, 80).rect(57, 77, 60, 80).mask(),
            TestPage(80, 60).rect(0, 0, 80, 60).mask(),
        )
        val a = analyze(pages)

        for (s in floatArrayOf(1f, 0.9f, 0.5f, 0.3f)) {
            for (w in a.placements(s)) {
                assertEquals(s, w.scale)
                assertTrue("$w", w.left >= 0f && w.left + w.scale <= 1f + 1e-6f)
                assertTrue("$w", w.top >= 0f && w.top + w.scale <= 1f + 1e-6f)
            }
        }
    }

    @Test
    fun windowsFollowTheInkWhenTheyMustCrop() {
        // Ink only in the bottom-right corner: a small window should move there.
        val a = analyze(listOf(TestPage(60, 80).rect(40, 60, 60, 80).mask()))

        val window = a.placements(0.3f).single()

        // An 18x24 window can't hold the 20x20 block, but should sit on it.
        assertTrue("$window", window.covers(60, 80, aspect, 42, 60, 58, 80))
    }

    @Test
    fun shortPagesLineUpWithFullOnes() {
        // The last page of a paper: text fills only the top third of the block.
        val pages = List(4) { textPage().mask() } + TestPage(60, 80).rect(10, 10, 50, 30).mask()
        val a = analyze(pages)

        val windows = a.placements(a.autoScale)

        val full = windows.first()
        windows.drop(1).forEach {
            assertEquals(full.left, it.left, 0.02f)
            assertEquals(full.top, it.top, 0.02f)
        }
    }

    @Test
    fun blankPagesLineUpWithTheRest() {
        val pages = listOf(textPage().mask(), TestPage(60, 80).mask(), textPage().mask())
        val a = analyze(pages)

        val (first, blank, _) = a.placements(a.autoScale)

        assertEquals(first.left, blank.left, 0.02f)
        assertEquals(first.top, blank.top, 0.02f)
    }

    @Test
    fun landscapePagesArePaddedAboveAndBelow() {
        // An 80x60 page on a 3:4 screen pads to 80 x 106.7, with the page in the middle.
        val page = TestPage(80, 60).rect(20, 10, 60, 50)
        val a = analyze(listOf(page.mask()))

        val window = a.placements(a.autoScale).single()

        // The 40x40 block needs a 3:4 window at least 40 wide: 40 / 80 = 0.5.
        assertTrue("auto ${a.autoScale}", a.autoScale in 0.5f..0.55f)
        assertTrue("$window", window.covers(80, 60, aspect, 20, 10, 60, 50))
    }

    @Test
    fun pagesNarrowerThanTheScreenArePaddedAtTheSides() {
        // 40x80 is narrower than 3:4, so it pads to 60x80; the text block is the full height.
        val page = TestPage(40, 80).rect(0, 4, 40, 76)
        val a = analyze(listOf(page.mask()))

        assertTrue("auto ${a.autoScale}", a.autoScale in 0.9f..0.95f)
        assertTrue(a.placements(a.autoScale).single().covers(40, 80, aspect, 0, 4, 40, 76))
    }

    @Test
    fun pageNumbersStayInWhenTheWindowCanSlideToThem() {
        // Text from row 8 to 70 and a page number at row 78: a 74-row window (scale 0.925) holds
        // both only from rows 5-8 down. Nearer the middle it would cut the page number off while
        // the rows above the text stay blank.
        val page = TestPage(60, 80).rect(10, 8, 50, 70).rect(29, 78, 31, 79).mask()
        val a = analyze(listOf(page))

        val window = a.placements(0.925f).single()

        assertEquals(Clipped(0, 0, 0, 0, 0), window.clipped(page, aspect))
    }

    @Test
    fun attentionPage2KeepsItsPageNumberWhenZoomedALittle() {
        // Page 2 of "Attention Is All You Need" (arXiv 1706.03762) as the reader sees it on a
        // 3:4 screen: text, then a gap, then the page number. Zooming in a little used to cut
        // the page number off while blank rows remained above the text.
        val page = maskFixture("attention-page2.txt")
        val a = analyze(listOf(page))

        val window = a.placements(0.85f).single()

        assertEquals(Clipped(0, 0, 0, 0, 0), window.clipped(page, aspect))
    }

    @Test
    fun attentionPage2LosesOnlyTheInkNoWindowCanKeep() {
        val page = maskFixture("attention-page2.txt")
        val a = analyze(listOf(page))

        for (i in a.scales.indices) {
            val window = a.placements(a.scales[i]).single()
            assertEquals("scale ${a.scales[i]}", a.lostInk[i], window.clipped(page, aspect).total.toLong())
        }
    }

    @Test
    fun windowsNeverLeaveInkBehindThatSlidingWouldKeep() {
        val random = Random(7)
        // Page sizes that pad evenly to a 60x80 frame on a 3:4 screen, so cells line up exactly.
        val sizes = listOf(60 to 80, 48 to 80, 40 to 80, 60 to 70, 60 to 64)
        var clippedWindows = 0
        repeat(6) {
            val masks = List(5) { syntheticPage(random, sizes.random(random)).mask() }
            val a = analyze(masks)
            for (i in a.scales.indices step 3) {
                val windows = a.placements(a.scales[i])
                windows.forEachIndexed { p, window ->
                    val clipped = window.clipped(masks[p], aspect)
                    if (clipped.total == 0) return@forEachIndexed
                    clippedWindows++
                    for ((dx, dy) in towardClipped(clipped)) {
                        var moved = window
                        while (true) {
                            moved = moved.copy(left = moved.left + dx / 60f, top = moved.top + dy / 80f)
                            if (moved.left < -1e-4f || moved.top < -1e-4f ||
                                moved.left + moved.scale > 1 + 1e-4f || moved.top + moved.scale > 1 + 1e-4f
                            ) break
                            assertTrue(
                                "page $p at scale ${a.scales[i]}: $window clips $clipped, $moved clips less",
                                moved.clipped(masks[p], aspect).total >= clipped.total,
                            )
                        }
                    }
                }
                // And so each page loses only what no window could keep.
                val lost = windows.indices.sumOf { windows[it].clipped(masks[it], aspect).total.toLong() }
                assertEquals("scale ${a.scales[i]}", a.lostInk[i], lost)
            }
        }
        // The pages are dense enough that small windows have to cut into them.
        assertTrue("$clippedWindows", clippedWindows > 100)
    }

    /** Unit steps, in cells, toward each edge of a window that ink is clipped beyond. */
    private fun towardClipped(c: Clipped) = listOfNotNull(
        (-1 to 0).takeIf { c.left > 0 },
        (0 to -1).takeIf { c.top > 0 },
        (1 to 0).takeIf { c.right > 0 },
        (0 to 1).takeIf { c.bottom > 0 },
    )

    /** A page of text lines, sometimes with a running head, a page number and stray marks. */
    private fun syntheticPage(random: Random, size: Pair<Int, Int>): TestPage {
        val (width, height) = size
        val page = TestPage(width, height)
        val left = random.nextInt(1, width / 4)
        val right = width - random.nextInt(1, width / 4)
        val top = random.nextInt(2, height / 4)
        val bottom = height - random.nextInt(4, height / 4)
        for (y in top until bottom step 2) {
            page.rect(left, y, right - random.nextInt(0, (right - left) / 3), y + 1)
        }
        if (random.nextBoolean()) page.rect(left, top - 2, right, top - 1)
        if (random.nextBoolean()) {
            val y = random.nextInt(bottom + 1, height)
            page.rect(width / 2 - 1, y, width / 2 + 1, y + 1)
        }
        repeat(random.nextInt(3)) {
            val x = random.nextInt(width)
            val y = random.nextInt(height)
            page.rect(x, y, x + 1, y + 1)
        }
        return page
    }

    @Test
    fun nearestIndexSnapsToTheClosestScale() {
        val a = analyze(listOf(textPage().mask()))

        assertEquals(0, a.nearestIndex(1.2f))
        assertEquals(a.scales.lastIndex, a.nearestIndex(0f))
        assertEquals(0.8f, a.scales[a.nearestIndex(0.8012f)], 1e-6f)
    }

    @Test
    fun blankPagesSitAtTheAnchor() {
        val blank = TestPage(60, 80).mask()

        val window = placeWindow(blank, aspect, 0.5f, Anchor(u = 0f, v = 1f))

        assertEquals(CropWindow(left = 0f, top = 0.5f, scale = 0.5f), window)
    }

    @Test
    fun analysisCanBeCancelledBetweenPages() {
        var finished = false
        runBlocking {
            launch(start = CoroutineStart.UNDISPATCHED) {
                cancel()
                // Nothing in here suspends, so only an explicit check can stop it.
                CropAnalysis.of(List(5) { textPage().mask() }, aspect)
                finished = true
            }
        }
        assertFalse(finished)
    }

    @Test
    fun rejectsEmptyInput() {
        assertThrows(IllegalArgumentException::class.java) { analyze(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            analyze(listOf(textPage().mask()), 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            analyze(listOf(textPage().mask()), Float.NaN)
        }
    }
}
