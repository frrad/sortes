package dev.sortes.zoomeval

import dev.sortes.app.zoom.CellRect
import dev.sortes.app.zoom.InkMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeSlicesTest {
    /** A mask from rows of '#' (ink) and '.' (paper). */
    private fun mask(vararg rows: String): InkMask {
        val width = rows[0].length
        return InkMask(
            width,
            rows.size,
            BooleanArray(width * rows.size) { rows[it / width][it % width] == '#' },
        )
    }

    // Two text lines, two rows tall each, with a blank row between them.
    private val lines =
        mask(
            "........",
            ".######.",
            ".######.",
            "........",
            ".#####..",
            ".#####..",
            "........",
        )

    @Test
    fun anEdgeInTheGapBetweenLinesSlicesNothing() {
        assertEquals(EdgeSlices(0, 0, 0, 0), edgeSlices(lines, CellRect(0, 3, 8, 7)))
        assertEquals(EdgeSlices(0, 0, 0, 0), edgeSlices(lines, CellRect(0, 0, 8, 4)))
    }

    @Test
    fun anEdgeJustClearOfALineSlicesNothing() {
        // The top edge row is the line's first row: the row beyond it is blank.
        assertEquals(0, edgeSlices(lines, CellRect(0, 1, 8, 7)).top)
        // The bottom edge row is the line's last row.
        assertEquals(0, edgeSlices(lines, CellRect(0, 0, 8, 6)).bottom)
    }

    @Test
    fun edgesThroughALineCountItsCells() {
        val s = edgeSlices(lines, CellRect(0, 2, 8, 5))
        assertEquals(6, s.top)
        assertEquals(5, s.bottom)
        assertTrue(s.slicesLine())
    }

    @Test
    fun sideEdgesCountTheRowsTheyCut() {
        val s = edgeSlices(lines, CellRect(3, 0, 8, 7))
        assertEquals(4, s.left)
        assertEquals(0, s.right)
        assertTrue(s.slicesSide())
        assertFalse(s.slicesLine())
    }

    @Test
    fun onlyTheWindowsColumnsCount() {
        // A window over the right part only: the second line (columns 1-5) is cut in columns 4-5.
        assertEquals(2, edgeSlices(lines, CellRect(4, 5, 8, 7)).top)
        assertFalse(edgeSlices(lines, CellRect(4, 5, 8, 7)).slicesLine())
    }

    @Test
    fun cellsBeyondTheMaskAreBlank() {
        // A window reaching into the padding above and to the left of the page.
        assertEquals(EdgeSlices(0, 0, 0, 0), edgeSlices(lines, CellRect(-2, -1, 9, 8)))
    }
}
