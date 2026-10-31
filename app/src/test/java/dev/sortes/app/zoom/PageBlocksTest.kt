package dev.sortes.app.zoom

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBlocksTest {
    private val aspect = 0.75f
    private val scratch = BlockScratch()

    @Test
    fun inkWithinTheGapIsOneBlockAndBeyondItAnother() {
        // Three lines two cells apart, then a caption four cells below them.
        val page =
            TestPage(60, 80)
                .rect(10, 10, 50, 12)
                .rect(10, 14, 50, 16)
                .rect(10, 18, 50, 20)
                .rect(10, 24, 50, 26)
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
        assertThrows(IllegalArgumentException::class.java) {
            PageBlocks.of(TestPage(10, 10).mask(), -1)
        }
    }

    @Test
    fun aWindowSplitsTheBlocksItOverlapsButDoesNotContain() {
        val blocks =
            PageBlocks.of(TestPage(60, 80).rect(10, 10, 50, 30).rect(10, 40, 50, 60).mask(), 2)
        assertEquals(0, blocks.split(CellRect(0, 0, 60, 80))) // both kept
        assertEquals(0, blocks.split(CellRect(0, 0, 60, 35))) // one kept, one dropped
        assertEquals(1, blocks.split(CellRect(0, 0, 60, 50))) // through the second
        assertEquals(2, blocks.split(CellRect(0, 20, 60, 50))) // through both
        assertEquals(1, blocks.split(CellRect(0, 0, 30, 35))) // the first, by a side edge
        assertEquals(
            0,
            blocks.split(CellRect(10, 10, 50, 30)),
        ) // exactly the first: contained, the second not overlapped
    }

    @Test
    fun theSplitCostGrowsWithTheInkCutOffABlockUpToOnePerBlock() {
        // A 40 x 20 block (800 cells) and, below it, a 40 x 10 one (400 cells).
        val padded =
            PaddedPage(TestPage(60, 80).rect(10, 10, 50, 30).rect(10, 40, 50, 50).mask(), 0.75f)
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

    /**
     * A random page: solid rectangles, hollow frames and sparse dots, so blocks overlap, nest and
     * have holes.
     */
    private fun randomPage(random: Random): TestPage {
        val page = TestPage(40, 50)
        repeat(random.nextInt(1, 7)) {
            val l = random.nextInt(0, 36)
            val t = random.nextInt(0, 46)
            val r = random.nextInt(l + 1, 41)
            val b = random.nextInt(t + 1, 51)
            when (random.nextInt(3)) {
                0 -> page.rect(l, t, r, b)
                1 -> {
                    page.rect(l, t, r, b)
                    if (r - l > 4 && b - t > 4) page.clear(l + 2, t + 2, r - 2, b - 2)
                }
                else ->
                    for (y in t until b step 3) for (x in l until r step 3) page.rect(
                        x,
                        y,
                        x + 1,
                        y + 1,
                    )
            }
        }
        return page
    }

    /** Each block's ink inside the window, straight from the label map: the definition. */
    private fun keptByLabels(blocks: PageBlocks, window: CellRect): IntArray {
        val labels = blocks.labels()
        val kept = IntArray(blocks.boxes.size)
        for (i in labels.indices) {
            val b = labels[i]
            if (b < 0) continue
            val x = i % blocks.maskWidth
            val y = i / blocks.maskWidth
            if (x >= window.left && x < window.right && y >= window.top && y < window.bottom)
                kept[b]++
        }
        return kept
    }

    /**
     * The reference segmentation: breadth-first search over inked cells, joining any two at most
     * gap + 1 apart in both directions. Returns each block's box, ink and cells in order of first
     * cell, as [PageBlocks] numbers them.
     */
    private fun bfsBlocks(mask: InkMask, gap: Int): Triple<List<CellRect>, IntArray, IntArray> {
        val w = mask.width
        val h = mask.height
        val labels = IntArray(w * h) { -1 }
        val boxes = mutableListOf<CellRect>()
        val inks = mutableListOf<Int>()
        val reach = gap + 1
        for (start in 0 until w * h) {
            if (labels[start] >= 0 || !mask[start % w, start / w]) continue
            val b = boxes.size
            val queue = ArrayDeque<Int>()
            queue += start
            labels[start] = b
            var x0 = w
            var y0 = h
            var x1 = -1
            var y1 = -1
            var n = 0
            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                val cx = cell % w
                val cy = cell / w
                n++
                x0 = minOf(x0, cx)
                x1 = maxOf(x1, cx)
                y0 = minOf(y0, cy)
                y1 = maxOf(y1, cy)
                for (ny in (cy - reach).coerceAtLeast(0)..(cy + reach).coerceAtMost(h - 1)) {
                    for (nx in (cx - reach).coerceAtLeast(0)..(cx + reach).coerceAtMost(w - 1)) {
                        val m = ny * w + nx
                        if (labels[m] < 0 && mask[nx, ny]) {
                            labels[m] = b
                            queue += m
                        }
                    }
                }
            }
            boxes += CellRect(x0, y0, x1 + 1, y1 + 1)
            inks += n
        }
        return Triple(boxes, inks.toIntArray(), labels)
    }

    @Test
    fun runSegmentationMatchesABreadthFirstSearchOverCells() {
        val random = Random(3)
        repeat(40) {
            val mask = randomPage(random).mask()
            val gap = random.nextInt(0, 4)
            val blocks = PageBlocks.of(mask, gap)
            val (boxes, inks, labels) = bfsBlocks(mask, gap)
            assertEquals("gap $gap", boxes, blocks.boxes)
            assertArrayEquals(inks, blocks.inks)
            assertArrayEquals(labels, blocks.labels())
        }
    }

    @Test
    fun theSplitCostFollowsEachBlocksOwnCellsNotItsBox() {
        val random = Random(5)
        repeat(30) {
            val page = randomPage(random)
            val mask = page.mask()
            val gap = random.nextInt(0, 3)
            val blocks = PageBlocks.of(mask, gap)
            val padded = PaddedPage(mask, aspect)
            val unit = random.nextInt(1, 60)
            repeat(20) {
                val w = random.nextInt(3, padded.width + 1)
                val h = random.nextInt(3, padded.height + 1)
                val x = random.nextInt(0, padded.width - w + 1)
                val y = random.nextInt(0, padded.height - h + 1)
                val window =
                    CellRect(
                        x - padded.offX,
                        y - padded.offY,
                        x - padded.offX + w,
                        y - padded.offY + h,
                    )
                val kept = keptByLabels(blocks, window)
                var expected = 0.0
                var expectedSplit = 0
                for (b in blocks.boxes.indices) {
                    val box = blocks.boxes[b]
                    val cut = blocks.inks[b] - kept[b]
                    if (blocks.inks[b] >= PageBlocks.MIN_BLOCK_INK && kept[b] > 0 && cut > 0)
                        expectedSplit++
                    if (blocks.inks[b] < PageBlocks.MIN_BLOCK_INK) continue
                    val sparse =
                        blocks.inks[b] * 2 < (box.right - box.left) * (box.bottom - box.top)
                    // The definition: a block whose box the window overlaps is charged for what it
                    // cuts, unless the window keeps none of its cells while lying inside its box
                    // (a sparse frame around other content).
                    val counts =
                        box.overlaps(window) &&
                            cut > 0 &&
                            !(kept[b] == 0 && sparse && box.contains(window))
                    if (!counts) continue
                    expected += minOf(1.0, cut.toDouble() / unit)
                }
                assertEquals(expected, padded.splitCost(x, y, w, h, gap, unit), 1e-9)
                assertEquals(expectedSplit, padded.split(x, y, w, h, gap))
            }
        }
    }

    @Test
    fun splitCostsForAllPositionsMatchTheCostPerPosition() {
        val random = Random(11)
        repeat(30) {
            val padded = PaddedPage(randomPage(random).mask(), aspect)
            val w = random.nextInt(3, padded.width + 1)
            val h = random.nextInt(3, padded.height + 1)
            val gap = random.nextInt(0, 3)
            val unit = random.nextInt(1, 200)
            val cols = padded.width - w + 1
            val out = DoubleArray(cols * (padded.height - h + 1))
            padded.splitCosts(w, h, gap, unit, out, scratch)
            for (i in out.indices) assertEquals(
                "position $i of $cols cols, window $w x $h, gap $gap, unit $unit",
                padded.splitCost(i % cols, i / cols, w, h, gap, unit),
                out[i],
                1e-9,
            )
        }
    }

    @Test
    fun aWindowInsideAFrameCutsNothingOffTheFrame() {
        // A hollow frame (rows 10-40) around a 20 x 10 block of content; a window inside the
        // frame keeps the content whole and none of the frame: no split. One that takes half the
        // content splits the content only.
        val page = TestPage(60, 80).rect(5, 10, 55, 40).clear(7, 12, 53, 38).rect(20, 20, 40, 30)
        val padded = PaddedPage(page.mask(), aspect)
        assertEquals(2, PageBlocks.of(page.mask(), 2).boxes.size)
        assertEquals(0.0, padded.splitCost(10, 15, 40, 20, 2, 50), 0.0)
        assertEquals(0, padded.split(10, 15, 40, 20, 2))
        assertEquals(1.0, padded.splitCost(10, 15, 20, 20, 2, 50), 0.0)
        assertEquals(1, padded.split(10, 15, 20, 20, 2))
    }

    @Test
    fun specksAreNotBlocksToKeepWhole() {
        // Scan dirt of 3 cells beside the text: cutting it costs nothing as a split (its ink
        // still counts as cut); an 8-cell mark does.
        val speck =
            PaddedPage(TestPage(60, 80).rect(10, 10, 50, 40).rect(55, 20, 58, 21).mask(), aspect)
        assertEquals(0.0, speck.splitCost(0, 0, 56, 80, 2, 100), 0.0)
        assertEquals(0, speck.split(0, 0, 56, 80, 2))
        val mark =
            PaddedPage(TestPage(60, 80).rect(10, 10, 50, 40).rect(54, 20, 58, 22).mask(), aspect)
        assertTrue(mark.splitCost(0, 0, 56, 80, 2, 100) > 0.0)
        assertEquals(1, mark.split(0, 0, 56, 80, 2))
    }

    @Test
    fun runsBridgeGapsAlongARowAndAcrossRows() {
        // Dots 2 cells apart along a row are one block at gap 2 and separate at gap 1; a dot 3
        // rows below joins at gap 2 (reach 3) only if within 3 columns.
        val dots =
            TestPage(20, 20)
                .rect(2, 2, 3, 3)
                .rect(5, 2, 6, 3)
                .rect(8, 5, 9, 6)
                .rect(15, 5, 16, 6)
                .mask()
        assertEquals(
            listOf(CellRect(2, 2, 9, 6), CellRect(15, 5, 16, 6)),
            PageBlocks.of(dots, 2).boxes,
        )
        assertEquals(4, PageBlocks.of(dots, 1).boxes.size)
        assertArrayEquals(intArrayOf(3, 1), PageBlocks.of(dots, 2).inks)
    }

    @Test
    fun aSmallBlocksCutIsAShareOfThePapersUnit() {
        // An equation number of 12 cells: cutting 6 of them is 6/100 of a split at a unit of 100.
        val padded =
            PaddedPage(TestPage(60, 80).rect(10, 10, 48, 40).rect(54, 24, 58, 27).mask(), aspect)
        assertEquals(6.0 / 100, padded.splitCost(0, 0, 56, 80, 2, 100), 1e-9)
        assertEquals(3.0 / 100, padded.splitCost(0, 0, 57, 80, 2, 100), 1e-9)
    }

    @Test
    fun blocksArePlacedInTheFrame() {
        // A 40 x 80 page pads to 60 x 80: 10 cells of padding on the left.
        val padded = PaddedPage(TestPage(40, 80).rect(5, 10, 35, 20).mask(), aspect)
        assertEquals(10, padded.offX)
        assertEquals(listOf(CellRect(15, 10, 45, 20)), padded.blocks(2))
    }
}

class BlockScratchTest {
    @Test
    fun aScratchSharedByTwoPagesGivesEachItsOwnAnswers() {
        val scratch = BlockScratch()
        val a = PaddedPage(TestPage(60, 80).rect(10, 10, 50, 40).mask(), 0.75f)
        val b = PaddedPage(TestPage(60, 80).rect(5, 5, 55, 75).rect(20, 77, 40, 79).mask(), 0.75f)
        fun costs(page: PaddedPage, s: BlockScratch): DoubleArray {
            val out = DoubleArray((page.width - 40 + 1) * (page.height - 50 + 1))
            page.splitCosts(40, 50, 2, 100, out, s)
            return out
        }
        val ownA = costs(a, BlockScratch())
        val ownB = costs(b, BlockScratch())
        repeat(3) {
            assertArrayEquals(ownA, costs(a, scratch), 0.0)
            assertArrayEquals(ownB, costs(b, scratch), 0.0)
        }
    }
}
