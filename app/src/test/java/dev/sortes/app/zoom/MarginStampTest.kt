package dev.sortes.app.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarginStampTest {

    /** A 192x248 page, the analysis size of a US Letter page, with a text block in the middle. */
    private fun page() = TestPage(192, 248).rect(34, 23, 158, 232)

    /** An arXiv-like stamp: a 5-wide, 107-tall line of text 6 cells from the left edge. */
    private fun TestPage.stamp(left: Int = 6, width: Int = 5, top: Int = 67, height: Int = 107) =
        rect(left, top, left + width, top + height)

    private fun InkMask.stripped() = MarginStampRule.strip(this)

    private fun InkMask.isSameAs(other: InkMask): Boolean =
        width == other.width && height == other.height &&
            (0 until height).all { y -> (0 until width).all { x -> this[x, y] == other[x, y] } }

    private fun assertKept(mask: InkMask) = assertSame(mask, mask.stripped())

    /** Asserts the stamp is gone from [mask] and everything right of column [stampEnd] is kept. */
    private fun assertStampRemovedLeft(mask: InkMask, stampEnd: Int) {
        val cleaned = mask.stripped()
        assertTrue("stamp ink should be removed", cleaned.inkCount < mask.inkCount)
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (x < stampEnd) assertTrue("ink left at ($x, $y)", !cleaned[x, y])
            else assertEquals("($x, $y)", mask[x, y], cleaned[x, y])
        }
    }

    @Test
    fun removesAStampInTheLeftMargin() {
        val cleaned = page().stamp().mask().stripped()

        assertTrue(cleaned.isSameAs(page().mask()))
    }

    @Test
    fun removesAStampInTheRightMargin() {
        val cleaned = page().stamp(left = 181).mask().stripped()

        assertTrue(cleaned.isSameAs(page().mask()))
    }

    @Test
    fun removesStampsInBothMargins() {
        val cleaned = page().stamp().stamp(left = 181).mask().stripped()

        assertTrue(cleaned.isSameAs(page().mask()))
    }

    @Test
    fun removesAStampBrokenUpByTheRendering() {
        // Thin gray glyphs come out patchy at analysis size: gaps between letters and words,
        // and the odd blank column.
        val stamp = page()
        for (y in 67 until 174) if (y % 4 != 0) stamp.rect(6, y, 8, y + 1).rect(9, y, 11, y + 1)

        assertTrue(stamp.mask().stripped().isSameAs(page().mask()))
    }

    @Test
    fun removesAStampOnALandscapeOrUnusualPage() {
        // The rule is in fractions of the page, not cells: a page twice the size behaves the same.
        val big = TestPage(384, 496).rect(68, 46, 316, 464).rect(12, 134, 22, 348)

        val cleaned = big.mask().stripped()

        assertTrue(cleaned.isSameAs(TestPage(384, 496).rect(68, 46, 316, 464).mask()))
    }

    @Test
    fun keepsLineNumbers() {
        // Small numbers by each line of text, with blank rows between lines (in the fixtures, ink
        // on about 30% of the rows); even right by the page edge, they're not one line of text.
        val numbered = page()
        for (y in 23 until 232 step 3) numbered.rect(8, y, 11, y + 1)

        assertKept(numbered.mask())
    }

    @Test
    fun keepsAStampLikeLineThatTouchesTheText() {
        // Two cells' gap at most: it's part of the text block, e.g. a change bar or a rule.
        assertKept(page().stamp(left = 28, width = 4).mask())
        assertKept(TestPage(192, 248).rect(16, 23, 158, 232).stamp(left = 10, width = 4).mask())
    }

    @Test
    fun keepsTallThinInkAwayFromThePageEdge() {
        // A vertical label 20 cells in (over 10% of the width) belongs to the layout, not a stamp.
        assertKept(page().stamp(left = 20).mask())
    }

    @Test
    fun keepsMarginNotesAndFigures() {
        // Wider than a line of text set on its side.
        assertKept(page().rect(2, 60, 14, 180).mask())
    }

    @Test
    fun keepsShortMarginMarks() {
        // A logo or a single word in the margin is too short to be a stamp.
        assertKept(page().stamp(height = 50).mask())
    }

    @Test
    fun keepsPagesWithNothingElseOnThem() {
        assertKept(TestPage(192, 248).stamp().mask())
        assertKept(TestPage(192, 248).stamp().stamp(left = 181).mask())
    }

    @Test
    fun keepsBlankAndFullPages() {
        assertKept(TestPage(192, 248).mask())
        assertKept(TestPage(192, 248).rect(0, 0, 192, 248).mask())
        assertKept(page().mask())
    }

    @Test
    fun removesTheArxivStampFromRealFirstPages() {
        // Each of these has the stamp in columns 5-10, then a clear gap before the text.
        assertStampRemovedLeft(maskFixture("attention-p1.txt"), stampEnd = 12)
        assertStampRemovedLeft(maskFixture("bert-p1.txt"), stampEnd = 12)
        // ResNet's text comes within 4 cells of the stamp.
        assertStampRemovedLeft(maskFixture("resnet-p1.txt"), stampEnd = 12)
    }

    @Test
    fun theStampIsASmallShareOfThePage() {
        for (name in listOf("attention-p1", "bert-p1", "resnet-p1")) {
            val mask = maskFixture("$name.txt")
            val removed = mask.inkCount - mask.stripped().inkCount
            assertTrue("$name: removed $removed of ${mask.inkCount}", removed in 100..mask.inkCount / 20)
        }
    }

    @Test
    fun keepsRealPagesWithoutAStamp() {
        assertKept(maskFixture("attention-page2.txt"))
        assertKept(maskFixture("line-numbers.txt"))
        assertKept(maskFixture("line-numbers-narrow-margin.txt"))
        assertKept(maskFixture("margin-notes.txt"))
        assertKept(maskFixture("rotated-label.txt"))
    }
}
