package dev.sortes.app.zoom

import org.junit.Assert.assertEquals
import org.junit.Test

class PaddedPageTest {
    private val aspect = 0.75f

    /**
     * The centre of the region the reader renders for a window, in page cells (1 cell = 1 point).
     */
    private fun renderedCentre(window: CropWindow, mask: InkMask): Pair<Float, Float> {
        val r = window.regionOn(PageSize(mask.width.toFloat(), mask.height.toFloat()), aspect)
        return (r.left + r.width / 2) to (r.top + r.height / 2)
    }

    private fun assertRenderedOnItsCells(mask: InkMask) {
        val page = PaddedPage(mask, aspect)
        val scale = 0.7f
        for (y in 0..page.height - page.windowHeight(scale) step 3) {
            for (x in 0..page.width - page.windowWidth(scale) step 3) {
                val cells = page.cellsAt(x, y, scale)
                val window = page.windowAt(x, y, scale)
                val (cx, cy) = renderedCentre(window, mask)
                // Flush against the frame's edge, the whole-cell frame can reach half a cell past
                // the exact one, and the window can't follow it there.
                val clampedX = window.left == 0f || window.left == 1f - scale
                val clampedY = window.top == 0f || window.top == 1f - scale
                assertEquals(
                    "x at ($x, $y)",
                    (cells.left + cells.right) / 2f,
                    cx,
                    if (clampedX) 0.5f else 0.02f,
                )
                assertEquals(
                    "y at ($x, $y)",
                    (cells.top + cells.bottom) / 2f,
                    cy,
                    if (clampedY) 0.5f else 0.02f,
                )
            }
        }
    }

    @Test
    fun windowsRenderCentredOnTheirCellsWithOddVerticalPadding() {
        // 61 x 80 is wider than 3:4: padded to 61 x 81 cells, but exactly 61 x 81.33 points, so
        // whole-cell centring puts the page 0 cells down where the reader puts it 0.67 down.
        assertRenderedOnItsCells(TestPage(61, 80).rect(5, 5, 55, 75).mask())
    }

    @Test
    fun windowsRenderCentredOnTheirCellsWithOddHorizontalPadding() {
        // 57 x 80 is narrower than 3:4: padded to 60 x 80, with 1.5 cells each side.
        assertRenderedOnItsCells(TestPage(57, 80).rect(5, 5, 50, 75).mask())
    }

    @Test
    fun positionOfInvertsWindowAt() {
        for (mask in listOf(TestPage(61, 80).mask(), TestPage(57, 80).mask(), textPage().mask())) {
            val page = PaddedPage(mask, aspect)
            val scale = 0.6f
            for (y in 0..page.height - page.windowHeight(scale)) for (x in
                0..page.width - page.windowWidth(scale)) {
                assertEquals(x to y, page.positionOf(page.windowAt(x, y, scale)))
            }
        }
    }

    @Test
    fun offCentreMeasuresTheKeptInksOffset() {
        val page = PaddedPage(textPage().mask(), aspect) // 40 x 60 block at (10, 10)
        assertEquals(0.0, page.offCentre(5, 5, 50, 70), 1e-9)
        assertEquals(10 / 100.0 + 10 / 140.0, page.offCentre(10, 10, 50, 70), 1e-9)
        assertEquals(0.0, page.offCentre(0, 0, 5, 5), 1e-9)
    }
}
