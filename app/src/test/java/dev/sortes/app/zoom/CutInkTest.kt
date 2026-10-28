package dev.sortes.app.zoom

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class CutInkTest {

    private val aspect = 0.75f

    // A 60x80-cell mask of a 600x800-point page: exactly 3:4, so no padding and 10 points a cell.
    private val page = PageSize(600f, 800f)

    /** Cells x 5-49 and y 10-69 of a 60x80 frame. */
    private val window = CropWindow(left = 5f / 60, top = 10f / 80, scale = 0.75f)

    private fun cells(left: Int, top: Int, right: Int, bottom: Int) =
        Region(left * 10f, top * 10f, (right - left) * 10f, (bottom - top) * 10f)

    @Test
    fun nothingIsCutFromInkInsideTheWindow() {
        val mask = TestPage(60, 80).rect(5, 10, 50, 70).mask()

        assertEquals(emptyList<CutInk>(), cutInk(mask, page, aspect, window))
        assertEquals(emptyList<CutInk>(), cutInk(mask, page, aspect, FullPage))
    }

    @Test
    fun blankPagesHaveNothingCut() {
        assertEquals(emptyList<CutInk>(), cutInk(TestPage(60, 80).mask(), page, aspect, window))
    }

    @Test
    fun runningHeadAndPageNumberGetABoxEach() {
        val mask = TestPage(60, 80)
            .rect(10, 3, 30, 5) // running head
            .rect(10, 12, 45, 66) // body
            .rect(29, 75, 31, 77) // page number
            .mask()

        val cut = cutInk(mask, page, aspect, window)

        assertEquals(
            listOf(CutInk(cells(10, 3, 30, 5), 40), CutInk(cells(29, 75, 31, 77), 4)),
            cut,
        )
    }

    @Test
    fun inkStraddlingAnEdgeIsBoxedOnlyOutsideIt() {
        // A line running off the right edge, which is at x = 50.
        val mask = TestPage(60, 80).rect(20, 30, 55, 31).mask()

        assertEquals(listOf(CutInk(cells(50, 30, 55, 31), 5)), cutInk(mask, page, aspect, window))
    }

    @Test
    fun inkPastACornerIsOneBox() {
        val mask = TestPage(60, 80).rect(48, 66, 54, 74).mask()

        // The L-shape outside the corner: right of x = 50 or below y = 70.
        assertEquals(listOf(CutInk(cells(48, 66, 54, 74), 48 - 8)), cutInk(mask, page, aspect, window))
    }

    @Test
    fun cutInkWithinTheGapSharesABox() {
        // Two words in a cut-off running head, two blank cells apart.
        val mask = TestPage(60, 80).rect(10, 3, 15, 5).rect(17, 3, 22, 5).mask()

        assertEquals(listOf(CutInk(cells(10, 3, 22, 5), 20)), cutInk(mask, page, aspect, window, gap = 2))
        assertEquals(
            listOf(CutInk(cells(10, 3, 15, 5), 10), CutInk(cells(17, 3, 22, 5), 10)),
            cutInk(mask, page, aspect, window, gap = 1),
        )
    }

    @Test
    fun gapZeroStillJoinsDiagonalNeighbours() {
        val mask = TestPage(60, 80).rect(10, 2, 11, 3).rect(11, 3, 12, 4).rect(14, 3, 15, 4).mask()

        assertEquals(
            listOf(CutInk(cells(10, 2, 12, 4), 2), CutInk(cells(14, 3, 15, 4), 1)),
            cutInk(mask, page, aspect, window, gap = 0),
        )
    }

    @Test
    fun inkOnTheOtherSideOfTheWindowIsAnotherBox() {
        // Both pieces are cut, and close enough to share a box, but the ink linking them is
        // inside the window: they're boxed apart because only cut cells link boxes.
        val mask = TestPage(60, 80).rect(0, 30, 60, 31).mask()

        assertEquals(
            listOf(CutInk(cells(0, 30, 5, 31), 5), CutInk(cells(50, 30, 60, 31), 10)),
            cutInk(mask, page, aspect, window),
        )
    }

    @Test
    fun boxesAreInPagePointsWhateverTheMaskResolution() {
        // A US Letter page analyzed at 0.25 cells per point, padded top and bottom to 3:4.
        val letter = PageSize(612f, 792f)
        val (w, h) = maskSize(letter, 0.25f)
        val mask = TestPage(w, h).rect(0, h - 3, w, h).mask()

        val cut = cutInk(mask, letter, aspect, CropWindow(left = 0f, top = 0f, scale = 0.9f))

        val box = cut.single().region
        assertEquals(0f, box.left, 1e-3f)
        assertEquals(612f, box.width, 1e-3f)
        assertEquals(792f, box.top + box.height, 1e-3f)
        assertEquals(3 * 792f / h, box.height, 1e-3f)
    }

    @Test
    fun cutCellsAddUpToTheInkTheAnalysisSaysIsLost() {
        val random = Random(11)
        val masks = List(4) {
            val p = TestPage(60, 80)
            repeat(8) {
                val x = random.nextInt(0, 55)
                val y = random.nextInt(0, 75)
                p.rect(x, y, x + random.nextInt(1, 6), y + random.nextInt(1, 6))
            }
            p.mask()
        } + maskFixture("attention-page2.txt")
        for (mask in masks) {
            val a = runBlocking { CropAnalysis.of(listOf(mask), aspect) }
            for (i in a.scales.indices step 5) {
                val scale = a.scales[i]
                val window = placeWindow(mask, aspect, scale, runBlocking { a.anchor(scale) })
                val size = PageSize(mask.width.toFloat(), mask.height.toFloat())
                assertEquals("scale $scale", a.lostInk[i], cutInk(mask, size, aspect, window).sumOf { it.cells }.toLong())
            }
        }
    }

    @Test
    fun negativeGapsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            cutInk(TestPage(60, 80).mask(), page, aspect, window, gap = -1)
        }
    }
}
