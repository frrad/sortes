package dev.sortes.app.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageNumberTest {
    private fun rowsWithInk(mask: InkMask) = (0 until mask.height).filter { y -> (0 until mask.width).any { mask[it, y] } }

    @Test
    fun removesARealPageNumber() {
        // Attention page 2: the text ends around row 226; "2" sits alone at rows 230-234.
        val mask = maskFixture("attention-page2.txt")
        val stripped = PageNumberRule.strip(mask)
        val removed = mask.inkCount - stripped.inkCount
        // Only the number: its 6 cells, not the "1 Introduction" heading at the top.
        assertEquals(6, removed)
        assertTrue(rowsWithInk(stripped).last() < rowsWithInk(mask).last() - 3)
    }

    @Test
    fun removesNumbersAtTheTopOrBottom() {
        val bottom = TestPage(60, 80).rect(10, 10, 50, 70).rect(29, 76, 31, 78).mask()
        assertEquals(40 * 60, PageNumberRule.strip(bottom).inkCount)
        val top = TestPage(60, 80).rect(29, 1, 31, 3).rect(10, 10, 50, 70).mask()
        assertEquals(40 * 60, PageNumberRule.strip(top).inkCount)
    }

    @Test
    fun keepsWideLinesAndLinesCloseToTheText() {
        // A footnote or running head: too wide.
        val footnote = TestPage(60, 80).rect(10, 10, 50, 70).rect(10, 75, 50, 77).mask()
        assertSame(footnote, PageNumberRule.strip(footnote))
        // A short last line right under the text: not clear of it.
        val lastLine = TestPage(60, 80).rect(10, 10, 50, 70).rect(10, 71, 16, 73).mask()
        assertSame(lastLine, PageNumberRule.strip(lastLine))
        // A section heading alone at the top: too wide (10% of the page).
        val heading = TestPage(60, 80).rect(10, 3, 16, 5).rect(10, 10, 50, 70).mask()
        assertSame(heading, PageNumberRule.strip(heading))
        // A short line in the middle of the page isn't at an edge.
        val middle = TestPage(60, 80).rect(10, 10, 50, 30).rect(28, 40, 32, 42).rect(10, 50, 50, 60).mask()
        assertSame(middle, PageNumberRule.strip(middle))
    }

    @Test
    fun keepsAPageThatIsOnlyANumber() {
        val alone = TestPage(60, 80).rect(29, 76, 31, 78).mask()
        assertSame(alone, PageNumberRule.strip(alone))
        val blank = TestPage(60, 80).mask()
        assertSame(blank, PageNumberRule.strip(blank))
    }

    @Test
    fun theReaderDoesNotUseItYet() {
        assertTrue(PageNumberRule !in IGNORABLE_INK_RULES)
    }
}
