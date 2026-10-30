package dev.sortes.app.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFurnitureTest {
    private val numbersOnly = PageFurniture.NUMBERS_ONLY

    private fun body() = TestPage(60, 80).rect(10, 10, 50, 70)

    @Test
    fun removesARealPageNumber() {
        // Attention page 2: "2" alone at rows 232-234, not the "1 Introduction" heading at the top.
        val mask = maskFixture("attention-page2.txt")
        assertEquals(6, mask.inkCount - numbersOnly.strip(mask).inkCount)
    }

    @Test
    fun removesNarrowNumbersAtTheTopOrBottom() {
        assertEquals(40 * 60, numbersOnly.strip(body().rect(29, 76, 31, 78).mask()).inkCount)
        assertEquals(40 * 60, numbersOnly.strip(body().rect(29, 1, 31, 3).mask()).inkCount)
    }

    @Test
    fun keepsLinesThatAreNotFurniture() {
        // A one-off footnote or footer: wide, and on one page only.
        val footnote = body().rect(10, 75, 50, 77).mask()
        assertSame(footnote, numbersOnly.strip(footnote))
        assertSame(footnote, PageFurniture.of(listOf(footnote) + List(5) { body().mask() }).strip(footnote))
        // A short last line right under the text: not clear of it.
        val lastLine = body().rect(10, 71, 12, 73).mask()
        assertSame(lastLine, numbersOnly.strip(lastLine))
        // A section heading alone at the top: too wide for a page number.
        val heading = body().rect(10, 3, 16, 5).mask()
        assertSame(heading, numbersOnly.strip(heading))
        // A short line mid-page isn't at an edge.
        val middle = TestPage(60, 80).rect(10, 10, 50, 30).rect(29, 40, 31, 42).rect(10, 50, 50, 60).mask()
        assertSame(middle, numbersOnly.strip(middle))
    }

    @Test
    fun aPageThatIsOnlyANumberEndsUpBlank() {
        // PDLT's page ii: blank but for its number, which used to earn the page its own window.
        assertEquals(0, numbersOnly.strip(TestPage(60, 80).rect(29, 76, 31, 78).mask()).inkCount)
        val blank = TestPage(60, 80).mask()
        assertSame(blank, numbersOnly.strip(blank))
    }

    @Test
    fun removesARunningHeadThatRecursAcrossThePaper() {
        // A wide running head at the same height on every page (odd pages a title, even pages
        // author and number), as on an LNCS paper.
        val pages = List(6) { i -> body().rect(if (i % 2 == 0) 10 else 20, 2, if (i % 2 == 0) 30 else 50, 4).mask() }
        val furniture = PageFurniture.of(pages)
        for (p in pages) assertEquals(40 * 60, furniture.strip(p).inkCount)
        // A page that wasn't among them loses it too.
        assertEquals(40 * 60, furniture.strip(body().rect(15, 2, 45, 4).mask()).inkCount)
        // The same line on too few pages isn't furniture.
        val rare = listOf(pages[0], pages[1]) + List(8) { body().mask() }
        assertTrue(PageFurniture.of(rare).strip(pages[0]).inkCount > 40 * 60)
    }

    @Test
    fun aPapersPagesLoseTheirFurnitureTogether() {
        val pages = List(5) { body().rect(15, 2, 45, 4).rect(29, 76, 31, 78).mask() }
        assertTrue(pages.withoutFurniture().all { it.inkCount == 40 * 60 })
    }

    @Test
    fun theReaderDoesNotUseItYet() {
        assertTrue(IGNORABLE_INK_RULES.none { it is PageFurniture })
    }

    @Test
    fun aRunningHeadWithARuleUnderItGoesTogether() {
        // DiLoCo: the running title, a rule just below it, then a gap before the text. On a page
        // as tall as a real mask (250 rows), where 3% is 7.5 rows.
        fun tall() = TestPage(100, 250).rect(15, 30, 85, 220)
        val pages = List(5) { tall().rect(30, 5, 70, 8).rect(10, 9, 90, 10).mask() }
        assertTrue(pages.withoutFurniture().all { it.inkCount == 70 * 190 })
        // A rule close above a page number at the bottom too.
        val numbered = tall().rect(10, 230, 90, 231).rect(85, 233, 88, 237).mask()
        assertEquals(70 * 190, PageFurniture.of(List(4) { numbered }).strip(numbered).inkCount)
        // A block taller than a line or two isn't furniture: a short paragraph at the bottom.
        val paragraph = tall().rect(15, 228, 85, 231).rect(15, 232, 85, 235).rect(15, 236, 85, 239).mask()
        assertTrue(PageFurniture.of(List(4) { paragraph }).strip(paragraph).inkCount == paragraph.inkCount)
    }
}
