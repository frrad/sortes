package dev.sortes.app.zoom

import org.junit.Assert.*
import org.junit.Test

class ElementLossTest {
    private fun mask(vararg rows: String) = InkMask(rows[0].length, rows.size,
        rows.flatMap { it.map { c -> c == '#' } }.toBooleanArray())

    @Test fun `a long rule and each small glyph have equal total mass`() {
        val m = mask("##########", "..........", "#.#.#.#.#.")
        val f = ElementLoss.of(m, m, m)
        assertEquals(6, f.elements)
        assertEquals(1.0, f.lost(CellRect(0, 1, 10, 3)), 1e-9)
        assertEquals(5.0, f.lost(CellRect(0, 0, 10, 2)), 1e-9)
        assertEquals(0.0, f.lost(CellRect(-2, -2, 20, 20)), 1e-9)
        assertEquals(6.0, f.lost(CellRect(20, 20, 21, 21)), 1e-9)
    }

    @Test fun `partial components cost their lost fraction and diagonal ink is connected`() {
        val m = mask("##..", "..#.", "...#")
        val f = ElementLoss.of(m, m, m)
        assertEquals(1, f.elements)
        assertEquals(0.5, f.lost(CellRect(0, 0, 2, 3)), 1e-9)
    }

    @Test fun `projection preserves fine elements invisible at coarse resolution`() {
        val fine = mask("#...", "....", "...#", "....")
        val raw = mask("..", "..")
        val f = ElementLoss.of(fine, raw, raw)
        assertEquals(2, f.elements)
        assertEquals(1.0, f.lost(CellRect(0, 0, 1, 2)), 1e-9)
    }

    @Test fun `existing removed furniture remains removed`() {
        val fine = mask("##..", "....", "..##", "....")
        val raw = mask("#.", ".#")
        val prepared = mask("..", ".#")
        val f = ElementLoss.of(fine, raw, prepared)
        assertEquals(1, f.elements)
        assertEquals(0.0, f.lost(CellRect(1, 1, 2, 2)), 1e-9)
    }

    @Test fun `ignored components do not leave artificial fringe elements`() {
        val fine = mask(".##...", "......")
        val raw = mask("#..")
        val prepared = mask("...")
        // One connected glyph straddles a removed coarse cell and its blank neighbour.
        // It must be excluded whole, rather than manufacture a one-pixel element.
        assertEquals(0, ElementLoss.of(fine,raw,prepared).elements)
    }

    @Test fun `blank masks and invalid sizes`() {
        val m = mask("..", "..")
        assertEquals(0.0, ElementLoss.of(m, m, m).lost(CellRect(0, 0, 1, 1)), 0.0)
        assertThrows(IllegalArgumentException::class.java) { ElementLoss.of(m, m, mask("...")) }
        assertThrows(IllegalArgumentException::class.java) { ElementLoss.of(mask("."), m, m) }
    }

}
