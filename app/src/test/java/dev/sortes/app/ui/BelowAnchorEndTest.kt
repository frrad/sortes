package dev.sortes.app.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class BelowAnchorEndTest {

    private val window = IntSize(1000, 2000)

    private fun position(
        anchor: IntRect,
        popup: IntSize,
        direction: LayoutDirection = LayoutDirection.Ltr,
        margin: Int = 10,
    ) = BelowAnchorEnd(margin).calculatePosition(anchor, window, direction, popup)

    @Test
    fun belowTheAnchorWithEndEdgesAligned() {
        assertEquals(IntOffset(500, 150), position(IntRect(700, 50, 800, 150), IntSize(300, 400)))
    }

    @Test
    fun keepsAMarginFromTheRightEdge() {
        // A ⋮ button hard against the right edge: the menu's outline stays off the edge.
        assertEquals(IntOffset(690, 150), position(IntRect(900, 50, 1000, 150), IntSize(300, 400)))
    }

    @Test
    fun withoutMarginLinesUpWithTheEdge() {
        assertEquals(IntOffset(700, 150), position(IntRect(900, 50, 1000, 150), IntSize(300, 400), margin = 0))
    }

    @Test
    fun rightToLeftAlignsStartEdges() {
        assertEquals(
            IntOffset(50, 150),
            position(IntRect(50, 50, 150, 150), IntSize(300, 400), LayoutDirection.Rtl),
        )
    }

    @Test
    fun keepsAMarginFromTheLeftEdge() {
        assertEquals(IntOffset(10, 150), position(IntRect(0, 50, 100, 150), IntSize(300, 400)))
    }

    @Test
    fun goesAboveWhenThereIsNoRoomBelow() {
        assertEquals(IntOffset(500, 1450), position(IntRect(700, 1850, 800, 1950), IntSize(300, 400)))
    }

    @Test
    fun tallPopupKeepsTheMarginAtTheTop() {
        // Too tall to fit above or below: it still stays off the top edge (and the status bar).
        assertEquals(IntOffset(500, 10), position(IntRect(700, 900, 800, 1000), IntSize(300, 2500)))
    }

    @Test
    fun tallPopupThatFitsWithinTheMarginsIsMovedUpToFit() {
        assertEquals(IntOffset(500, 10), position(IntRect(700, 50, 800, 150), IntSize(300, 1980)))
    }

    @Test
    fun popupWiderThanTheWindowStartsAtTheMargin() {
        assertEquals(IntOffset(10, 150), position(IntRect(700, 50, 800, 150), IntSize(1200, 400)))
    }
}
