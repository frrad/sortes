package dev.sortes.zoomeval.content

import dev.sortes.app.zoom.CellRect
import dev.sortes.app.zoom.InkMask
import org.junit.Assert.*
import org.junit.Test

class LineTruncationTest {
    private fun mask(vararg rows: String) = InkMask(rows[0].length, rows.size,
        rows.flatMap { it.map { c -> c == '#' } }.toBooleanArray())

    // One line of five 2x2 "glyphs", one cell apart, then a lone glyph far to the right.
    private val line = mask(
        "..........................",
        ".##.##.##.##.##.........##",
        ".##.##.##.##.##.........##",
        "..........................",
    )

    @Test fun `keeping all or none of a line costs nothing`() {
        val t = LineTruncation.of(line, line, line, gap = 1.0, saturation = 2.0)
        assertEquals(0.0, t.cost(CellRect(0, 0, 26, 4)), 1e-12)
        assertEquals(0.0, t.cost(CellRect(0, 0, 16, 4)), 1e-12) // the far glyph is its own segment
        assertEquals(0, t.truncated(CellRect(0, 0, 16, 4)))
    }

    @Test fun `clipping one glyph costs half, two or more the whole line`() {
        val t = LineTruncation.of(line, line, line, gap = 1.0, saturation = 2.0)
        assertEquals(0.5, t.cost(CellRect(0, 0, 14, 4)), 1e-12)
        assertEquals(1.0, t.cost(CellRect(0, 0, 11, 4)), 1e-12)
        assertEquals(1.0, t.cost(CellRect(0, 0, 6, 4)), 1e-12)
        assertEquals(1, t.truncated(CellRect(0, 0, 6, 4)))
        // From the left too; a glyph cut through counts as lost.
        assertEquals(0.5, t.cost(CellRect(2, 0, 16, 4)), 1e-12)
    }

    @Test fun `segments outside the window's rows cost nothing`() {
        val t = LineTruncation.of(line, line, line, gap = 1.0, saturation = 2.0)
        assertEquals(0.0, t.cost(CellRect(0, 3, 6, 4)), 1e-12)
    }

    @Test fun `costs are in coarse cells when the mask is finer`() {
        val coarse = InkMask(13, 2, BooleanArray(26) { true })
        val t = LineTruncation.of(line, coarse, coarse, gap = 1.0, saturation = 2.0)
        // Coarse column 7 = fine 14: loses the fifth glyph (fine 13-14).
        assertEquals(0.5, t.cost(CellRect(0, 0, 7, 2)), 1e-12)
    }

    @Test fun `ignored ink makes no segments`() {
        val coarse = InkMask(13, 2, BooleanArray(26) { true })
        val none = InkMask(13, 2, BooleanArray(26))
        val t = LineTruncation.of(line, coarse, none, gap = 1.0, saturation = 2.0)
        assertEquals(0.0, t.cost(CellRect(0, 0, 7, 2)), 1e-12)
    }
}
