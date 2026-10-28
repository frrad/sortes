package dev.sortes.app.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CropAnalysisTest {

    private val aspect = 0.75f

    @Test
    fun nothingIsLostUntilTheWindowIsSmallerThanTheText() {
        val a = CropAnalysis(listOf(textPage().mask()), aspect)

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
        val a = CropAnalysis(masks, aspect)

        for (i in 1 until a.scales.size) {
            assertTrue("scale ${a.scales[i]}", a.lostInk[i] >= a.lostInk[i - 1])
        }
        assertEquals(0L, a.lostInk[0])
        assertEquals(1f, a.scales[0])
    }

    @Test
    fun lostFractionIsRelativeToAllInkInThePaper() {
        val a = CropAnalysis(listOf(textPage().mask(), textPage().mask()), aspect)

        assertEquals(2L * 40 * 60, a.totalInk)
        val i = a.scales.indices.first { a.lostInk[it] > 0 }
        assertEquals(a.lostInk[i].toFloat() / a.totalInk, a.lostFraction(i), 1e-6f)
        assertEquals(2, a.pagesCropped(i))
    }

    @Test
    fun autoZoomFitsTheTextBlockSnugly() {
        val a = CropAnalysis(List(3) { textPage().mask() }, aspect)

        assertTrue("auto ${a.autoScale}", a.autoScale in 0.75f..0.80f)
        for (window in a.placements(a.autoScale)) {
            assertTrue("$window", window.covers(60, 80, aspect, 10, 10, 50, 70))
        }
    }

    @Test
    fun autoZoomCutsOffPageNumbers() {
        // A page number well below the text: keeping it would need scale (78 - 10) / 80 = 0.85.
        val pages = List(10) { textPage().rect(29, 76, 31, 78).mask() }

        val a = CropAnalysis(pages, aspect)

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

        val a = CropAnalysis(pages, aspect)

        assertTrue("auto ${a.autoScale}", a.autoScale >= 76f / 80)
    }

    @Test
    fun blankPaperIsNotZoomed() {
        val a = CropAnalysis(List(3) { TestPage(60, 80).mask() }, aspect)

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
        val a = CropAnalysis(pages, aspect)

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
        val a = CropAnalysis(listOf(TestPage(60, 80).rect(40, 60, 60, 80).mask()), aspect)

        val window = a.placements(0.3f).single()

        // An 18x24 window can't hold the 20x20 block, but should sit on it.
        assertTrue("$window", window.covers(60, 80, aspect, 42, 60, 58, 80))
    }

    @Test
    fun shortPagesLineUpWithFullOnes() {
        // The last page of a paper: text fills only the top third of the block.
        val pages = List(4) { textPage().mask() } + TestPage(60, 80).rect(10, 10, 50, 30).mask()
        val a = CropAnalysis(pages, aspect)

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
        val a = CropAnalysis(pages, aspect)

        val (first, blank, _) = a.placements(a.autoScale)

        assertEquals(first.left, blank.left, 0.02f)
        assertEquals(first.top, blank.top, 0.02f)
    }

    @Test
    fun landscapePagesArePaddedAboveAndBelow() {
        // An 80x60 page on a 3:4 screen pads to 80 x 106.7, with the page in the middle.
        val page = TestPage(80, 60).rect(20, 10, 60, 50)
        val a = CropAnalysis(listOf(page.mask()), aspect)

        val window = a.placements(a.autoScale).single()

        // The 40x40 block needs a 3:4 window at least 40 wide: 40 / 80 = 0.5.
        assertTrue("auto ${a.autoScale}", a.autoScale in 0.5f..0.55f)
        assertTrue("$window", window.covers(80, 60, aspect, 20, 10, 60, 50))
    }

    @Test
    fun pagesNarrowerThanTheScreenArePaddedAtTheSides() {
        // 40x80 is narrower than 3:4, so it pads to 60x80; the text block is the full height.
        val page = TestPage(40, 80).rect(0, 4, 40, 76)
        val a = CropAnalysis(listOf(page.mask()), aspect)

        assertTrue("auto ${a.autoScale}", a.autoScale in 0.9f..0.95f)
        assertTrue(a.placements(a.autoScale).single().covers(40, 80, aspect, 0, 4, 40, 76))
    }

    @Test
    fun nearestIndexSnapsToTheClosestScale() {
        val a = CropAnalysis(listOf(textPage().mask()), aspect)

        assertEquals(0, a.nearestIndex(1.2f))
        assertEquals(a.scales.lastIndex, a.nearestIndex(0f))
        assertEquals(0.8f, a.scales[a.nearestIndex(0.8012f)], 1e-6f)
    }

    @Test
    fun rejectsEmptyInput() {
        assertThrows(IllegalArgumentException::class.java) { CropAnalysis(emptyList(), aspect) }
        assertThrows(IllegalArgumentException::class.java) {
            CropAnalysis(listOf(textPage().mask()), 0f)
        }
    }
}
