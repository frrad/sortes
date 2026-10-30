package dev.sortes.app.zoom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PageBlocksTest {
    private val aspect = 0.75f

    @Test
    fun inkWithinTheGapIsOneBlockAndBeyondItAnother() {
        // Three lines two cells apart, then a caption four cells below them.
        val page = TestPage(60, 80).rect(10, 10, 50, 12).rect(10, 14, 50, 16).rect(10, 18, 50, 20).rect(10, 24, 50, 26)
        val two = PageBlocks.of(page.mask(), 2)
        assertEquals(listOf(CellRect(10, 10, 50, 20), CellRect(10, 24, 50, 26)), two.boxes)
        assertEquals(4, PageBlocks.of(page.mask(), 1).boxes.size)
        assertEquals(2, PageBlocks.of(page.mask(), 3).boxes.size)
        assertEquals(1, PageBlocks.of(page.mask(), 4).boxes.size)
    }

    @Test
    fun diagonalNeighboursJoin() {
        val page = TestPage(10, 10).rect(0, 0, 2, 2).rect(2, 2, 4, 4)
        assertEquals(1, PageBlocks.of(page.mask(), 0).boxes.size)
    }

    @Test
    fun blankPagesHaveNoBlocksAndBadGapsAreRejected() {
        assertTrue(PageBlocks.of(TestPage(10, 10).mask(), 2).boxes.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { PageBlocks.of(TestPage(10, 10).mask(), -1) }
    }

    @Test
    fun aWindowSplitsTheBlocksItOverlapsButDoesNotContain() {
        val blocks = PageBlocks.of(TestPage(60, 80).rect(10, 10, 50, 30).rect(10, 40, 50, 60).mask(), 2)
        assertEquals(0, blocks.split(CellRect(0, 0, 60, 80)))     // both kept
        assertEquals(0, blocks.split(CellRect(0, 0, 60, 35)))     // one kept, one dropped
        assertEquals(1, blocks.split(CellRect(0, 0, 60, 50)))     // through the second
        assertEquals(2, blocks.split(CellRect(0, 20, 60, 50)))    // through both
        assertEquals(1, blocks.split(CellRect(0, 0, 30, 35)))     // the first, by a side edge
        assertEquals(0, blocks.split(CellRect(10, 10, 50, 30)))   // exactly the first: contained, the second not overlapped
    }

    @Test
    fun splitsForAllPositionsMatchTheCountPerPosition() {
        val random = Random(7)
        repeat(20) {
            val page = TestPage(40, 50)
            repeat(random.nextInt(1, 7)) {
                val l = random.nextInt(0, 36)
                val t = random.nextInt(0, 46)
                page.rect(l, t, random.nextInt(l + 1, 41), random.nextInt(t + 1, 51))
            }
            val padded = PaddedPage(page.mask(), aspect)
            val w = random.nextInt(5, padded.width + 1)
            val h = random.nextInt(5, padded.height + 1)
            val gap = random.nextInt(0, 3)
            val cols = padded.width - w + 1
            val out = IntArray(cols * (padded.height - h + 1))
            padded.splits(w, h, gap, out)
            val expected = IntArray(out.size) { padded.split(it % cols, it / cols, w, h, gap) }
            assertArrayEquals(expected, out)
        }
    }

    @Test
    fun theSplitCostGrowsWithTheInkCutOffABlockUpToOnePerBlock() {
        // A 40 x 20 block (800 cells) and, below it, a 40 x 10 one (400 cells).
        val padded = PaddedPage(TestPage(60, 80).rect(10, 10, 50, 30).rect(10, 40, 50, 50).mask(), 0.75f)
        val unit = 100
        // The whole page: nothing cut.
        assertEquals(0.0, padded.splitCost(0, 0, 60, 80, 2, unit), 0.0)
        // A 60 x 28 window from row 0: the first block loses rows 28-29 (80 cells), 0.8 of a unit.
        assertEquals(0.8, padded.splitCost(0, 0, 60, 28, 2, unit), 1e-9)
        // From row 0 to 25: 200 cells cut, capped at 1.
        assertEquals(1.0, padded.splitCost(0, 0, 60, 25, 2, unit), 1e-9)
        // Rows 20-45: through both blocks, 400 and 200 cells cut.
        assertEquals(2.0, padded.splitCost(0, 20, 60, 25, 2, unit), 1e-9)
        // A side nibble: columns 0-48 keep all but one column (20 cells) of the first block and
        // one (10) of the second.
        assertEquals(0.3, padded.splitCost(0, 0, 49, 80, 2, unit), 1e-9)
    }

    @Test
    fun splitCostsForAllPositionsMatchTheCostPerPosition() {
        val random = Random(11)
        repeat(20) {
            val page = TestPage(40, 50)
            repeat(random.nextInt(1, 7)) {
                val l = random.nextInt(0, 36)
                val t = random.nextInt(0, 46)
                page.rect(l, t, random.nextInt(l + 1, 41), random.nextInt(t + 1, 51))
            }
            val padded = PaddedPage(page.mask(), aspect)
            val w = random.nextInt(5, padded.width + 1)
            val h = random.nextInt(5, padded.height + 1)
            val gap = random.nextInt(0, 3)
            val unit = random.nextInt(1, 200)
            val cols = padded.width - w + 1
            val out = DoubleArray(cols * (padded.height - h + 1))
            padded.splitCosts(w, h, gap, unit, out)
            for (i in out.indices) assertEquals(padded.splitCost(i % cols, i / cols, w, h, gap, unit), out[i], 1e-9)
        }
    }

    @Test
    fun blocksArePlacedInTheFrame() {
        // A 40 x 80 page pads to 60 x 80: 10 cells of padding on the left.
        val padded = PaddedPage(TestPage(40, 80).rect(5, 10, 35, 20).mask(), aspect)
        assertEquals(10, padded.offX)
        assertEquals(listOf(CellRect(15, 10, 45, 20)), padded.blocks(2))
    }
}
