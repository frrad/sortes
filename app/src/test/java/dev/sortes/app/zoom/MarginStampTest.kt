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

    /** Asserts [mask] is kept as it is, and that its left margin fails [check]. */
    private fun assertKeptLeft(mask: InkMask, check: NotAStamp) {
        assertEquals(check, MarginStampRule.verdicts(mask).first)
        assertKept(mask)
    }

    /** Asserts the stamp is gone from [mask] and everything right of column [stampEnd] is kept. */
    private fun assertStampRemovedLeft(mask: InkMask, stampEnd: Int) {
        assertEquals(null, MarginStampRule.verdicts(mask).first)
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
        // Thin gray glyphs come out patchy at analysis size: gaps between words, and the odd
        // blank column. Letters within a word still run together for 9 cells or more.
        val stamp = page()
        for (y in 67 until 174) if (y % 12 != 0) stamp.rect(6, y, 8, y + 1).rect(9, y, 11, y + 1)

        assertTrue(stamp.mask().stripped().isSameAs(page().mask()))
    }

    @Test
    fun removesAStampOnADoubleSizePage() {
        // The rule is in fractions of the page, not cells: a page twice the size behaves the same.
        val big = TestPage(384, 496).rect(68, 46, 316, 464).rect(12, 134, 22, 348)

        val cleaned = big.mask().stripped()

        assertTrue(cleaned.isSameAs(TestPage(384, 496).rect(68, 46, 316, 464).mask()))
    }

    /** A landscape page, 248x192, with a text block in the middle. */
    private fun landscape() = TestPage(248, 192).rect(40, 18, 208, 176)

    @Test
    fun removesAStampOnALandscapePage() {
        val cleaned = landscape().rect(6, 50, 11, 140).mask().stripped()

        assertTrue(cleaned.isSameAs(landscape().mask()))
    }

    @Test
    fun keepsSquatMarksOnALandscapePage() {
        // Thin and tall enough by the page's measures, but only four times taller than wide:
        // a block of something, not a line of text on its side.
        assertKeptLeft(landscape().rect(4, 60, 16, 110).mask(), NotAStamp.TooSquat)
    }

    @Test
    fun keepsLineNumbers() {
        // Small numbers by each line of text, with blank rows between lines; even right by the
        // page edge, they're not one line of text.
        val sparse = page()
        for (y in 23 until 232 step 3) sparse.rect(8, y, 11, y + 1)
        assertKeptLeft(sparse.mask(), NotAStamp.Broken)

        // Larger numbers ink most rows (here two of every three), but never more than a line's
        // height in one go.
        val dense = page()
        for (y in 23 until 232 step 3) dense.rect(8, y, 11, y + 2)
        assertKeptLeft(dense.mask(), NotAStamp.Broken)
    }

    @Test
    fun keepsAStampLikeLineThatTouchesTheText() {
        // Two blank cells at most: it's part of the text block, e.g. a change bar or a rule.
        assertKeptLeft(TestPage(192, 248).rect(13, 23, 158, 232).stamp(width = 5).mask(), NotAStamp.TooClose)
    }

    @Test
    fun keepsAStampLikeLineMergedIntoTheText() {
        // A single blank column doesn't separate anything: the line and text are one run, so
        // there's no margin run apart from the text at all.
        assertKeptLeft(TestPage(192, 248).rect(12, 23, 158, 232).stamp().mask(), NotAStamp.NothingElse)
    }

    @Test
    fun keepsTallThinInkAwayFromThePageEdge() {
        // A vertical label 20 cells in (over 10% of the width) belongs to the layout, not a stamp.
        assertKeptLeft(page().stamp(left = 20).mask(), NotAStamp.AwayFromEdge)
    }

    @Test
    fun keepsMarginNotesAndFigures() {
        // Wider than a line of text set on its side.
        assertKeptLeft(page().rect(2, 60, 14, 180).mask(), NotAStamp.TooWide)
    }

    @Test
    fun keepsShortMarginMarks() {
        // A logo or a single word in the margin is too short to be a stamp.
        assertKeptLeft(page().stamp(height = 50).mask(), NotAStamp.TooShort)
    }

    @Test
    fun keepsPagesWithNothingElseOnThem() {
        assertKeptLeft(TestPage(192, 248).stamp().mask(), NotAStamp.NothingElse)
        assertKeptLeft(TestPage(192, 248).stamp().stamp(left = 181).mask(), NotAStamp.NothingElse)
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
    fun keepsLargerLineNumbersInNarrowMargins() {
        // \small line numbers with 0.75in margins: right by the edge, thin, clear of the text,
        // tall, and ink on 53% of their rows. Only the unbroken-run test tells them from a stamp.
        assertKeptLeft(maskFixture("line-numbers-small.txt"), NotAStamp.Broken)
        assertKeptLeft(maskFixture("line-numbers-footnotesize.txt"), NotAStamp.Broken)
        assertKeptLeft(maskFixture("line-numbers-narrow-margin.txt"), NotAStamp.Broken)
    }

    @Test
    fun keepsRealPagesWithoutAStamp() {
        // Text right up to the margins: the outermost run on each side is the text itself.
        assertKept(maskFixture("attention-page2.txt"))
        // Default line numbers with 1in margins sit too far in.
        assertKeptLeft(maskFixture("line-numbers.txt"), NotAStamp.AwayFromEdge)
        // A rotated section label of its own, clear of the text, 9% of the width in.
        assertKeptLeft(maskFixture("rotated-label.txt"), NotAStamp.AwayFromEdge)
        // Margin notes fail several checks at once; the first is reported.
        assertKeptLeft(maskFixture("margin-notes.txt"), NotAStamp.AwayFromEdge)
    }
}
