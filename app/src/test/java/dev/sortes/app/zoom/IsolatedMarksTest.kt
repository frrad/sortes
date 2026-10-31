package dev.sortes.app.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IsolatedMarksTest {
    private fun body() = TestPage(100, 140).rect(15, 15, 85, 125)

    @Test
    fun removesSpecksAndCornerMarksAwayFromEverything() {
        val page = body().rect(2, 2, 4, 3).rect(95, 135, 96, 136).mask()
        assertEquals(70 * 110, IsolatedMarks.strip(page).inkCount)
    }

    @Test
    fun keepsInkNearOtherInk() {
        // A small mark close to the text (a footnote marker, an equation number) stays.
        val near = body().rect(87, 60, 89, 62).mask()
        assertSame(near, IsolatedMarks.strip(near))
    }

    @Test
    fun keepsLargePieces() {
        // A figure off on its own is content, not a mark: bigger than 0.5% of the page's ink.
        val figure = body().rect(88, 40, 99, 60).mask()
        assertSame(figure, IsolatedMarks.strip(figure))
    }

    @Test
    fun keepsAPageOfOneSmallPiece() {
        val dot = TestPage(100, 140).rect(50, 70, 52, 72).mask()
        assertSame(dot, IsolatedMarks.strip(dot))
        val blank = TestPage(100, 140).mask()
        assertSame(blank, IsolatedMarks.strip(blank))
    }

    @Test
    fun keepsTextLinesWhoseLettersTouchOrNearlyTouch() {
        // Words in a line are separate pieces, but each has neighbours within the gap.
        val words = TestPage(100, 140).also { p -> for (i in 0 until 10) p.rect(10 + i * 8, 50, 16 + i * 8, 53) }.mask()
        assertSame(words, IsolatedMarks.strip(words))
    }

    @Test
    fun theReaderUsesIt() {
        assertTrue(IsolatedMarks in CropConfig.DEFAULT.pageRules)
    }
}
