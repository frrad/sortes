package dev.sortes.app.zoom

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A page's ink mask centred in a frame with the screen's [aspect] ratio, in whole cells, plus a
 * summed-area table for counting the ink in any window.
 */
internal class PaddedPage(mask: InkMask, private val aspect: Float) {
    companion object {
        /** Most frames' worth of cells the blocks' ink tables may take per page: about 200 KB. */
        private const val SAT_AREAS = 1

        /** How many cells [mask]'s frame has when padded to [aspect], without building it. */
        fun frameCells(mask: InkMask, aspect: Float): Long = frameWidth(mask, aspect).toLong() * frameHeight(mask, aspect)

        /** The width in cells of [mask]'s frame when padded to [aspect], without building it. */
        fun frameWidth(mask: InkMask, aspect: Float): Int =
            if (mask.width.toFloat() / mask.height < aspect) (mask.height * aspect).roundToInt().coerceAtLeast(mask.width) else mask.width

        private fun frameHeight(mask: InkMask, aspect: Float): Int =
            if (mask.width.toFloat() / mask.height < aspect) mask.height else (mask.width / aspect).roundToInt().coerceAtLeast(mask.height)
    }

    val width: Int
    val height: Int
    val maskWidth = mask.width
    val maskHeight = mask.height
    val inkCount = mask.inkCount

    /** Where the mask sits in the frame: the padding left of and above it, in cells. */
    val offX: Int
    val offY: Int

    // sums[y * (width + 1) + x] = ink in the frame above and left of (x, y).
    private val sums: IntArray

    init {
        if (mask.width.toFloat() / mask.height < aspect) {
            width = (mask.height * aspect).roundToInt().coerceAtLeast(mask.width)
            height = mask.height
        } else {
            width = mask.width
            height = (mask.width / aspect).roundToInt().coerceAtLeast(mask.height)
        }
        offX = (width - mask.width) / 2
        offY = (height - mask.height) / 2
        val stride = width + 1
        sums = IntArray(stride * (height + 1))
        for (y in 0 until height) {
            var row = 0
            for (x in 0 until width) {
                val mx = x - offX
                val my = y - offY
                if (mx in 0 until mask.width && my in 0 until mask.height && mask[mx, my]) row++
                sums[(y + 1) * stride + x + 1] = sums[y * stride + x + 1] + row
            }
        }
    }

    val mask = mask

    // Raw runs of ink along each frame row and down each frame column, for offCentres: row r's
    // runs are rowRunA[i] until rowRunB[i] for i in rowRuns[r] until rowRuns[r + 1]; likewise
    // the columns. A few kilobytes a page.
    private val rowRuns = IntArray(height + 1)
    private val rowRunA: IntArray
    private val rowRunB: IntArray
    private val colRuns = IntArray(width + 1)
    private val colRunA: IntArray
    private val colRunB: IntArray

    init {
        val ra = ArrayList<Int>()
        val rb = ArrayList<Int>()
        for (y in 0 until height) {
            rowRuns[y] = ra.size
            val my = y - offY
            if (my !in 0 until mask.height) continue
            var x = 0
            while (x < mask.width) {
                if (!mask[x, my]) { x++; continue }
                val start = x
                while (x < mask.width && mask[x, my]) x++
                ra += start + offX
                rb += x + offX
            }
        }
        rowRuns[height] = ra.size
        rowRunA = ra.toIntArray()
        rowRunB = rb.toIntArray()
        val ca = ArrayList<Int>()
        val cb = ArrayList<Int>()
        for (x in 0 until width) {
            colRuns[x] = ca.size
            val mx = x - offX
            if (mx !in 0 until mask.width) continue
            var y = 0
            while (y < mask.height) {
                if (!mask[mx, y]) { y++; continue }
                val start = y
                while (y < mask.height && mask[mx, y]) y++
                ca += start + offY
                cb += y + offY
            }
        }
        colRuns[width] = ca.size
        colRunA = ca.toIntArray()
        colRunB = cb.toIntArray()
    }

    private var blocksGap = -1
    private var found: PageBlocks? = null
    private var blocksFor: List<CellRect> = emptyList()
    // Whether each block is sparse: under half its box inked (a figure, a frame). A window
    // inside a sparse block's box may keep none of its cells.
    private var blockSparse = BooleanArray(0)
    // The blocks' summed-area tables, largest boxes first until they would take over SAT_AREAS
    // frames' worth of cells (so a page of heavily overlapping boxes stays bounded); the rest,
    // and blocks too small to count, are counted from their runs, which for small blocks is as
    // fast. tableOffsets[b] is a block's table's start in tableSums, or -1.
    private var tableOffsets = IntArray(0)
    private var tableSums = IntArray(0)

    /**
     * The page's blocks ([PageBlocks] with [gap]) as rects in frame cells, kept with their
     * tables for the last gap asked for.
     */
    fun blocks(gap: Int): List<CellRect> {
        if (gap != blocksGap) {
            val f = if (inkCount == 0) null else PageBlocks.of(mask, gap)
            found = f
            blocksFor = f?.boxes?.map { CellRect(it.left + offX, it.top + offY, it.right + offX, it.bottom + offY) } ?: emptyList()
            blockSparse = BooleanArray(blocksFor.size) { b ->
                val box = blocksFor[b]
                f!!.inks[b] * 2 < (box.right - box.left) * (box.bottom - box.top)
            }
            tables(f)
            blocksGap = gap
        }
        return blocksFor
    }


    private fun tables(f: PageBlocks?) {
        tableOffsets = IntArray(blocksFor.size) { -1 }
        if (f == null) {
            tableSums = IntArray(0)
            return
        }
        val order = blocksFor.indices.filter { f.inks[it] >= PageBlocks.MIN_BLOCK_INK }
            .sortedByDescending { (blocksFor[it].right - blocksFor[it].left) * (blocksFor[it].bottom - blocksFor[it].top) }
        var total = 0
        val cap = SAT_AREAS * width * height
        for (b in order) {
            val box = blocksFor[b]
            val size = (box.right - box.left + 1) * (box.bottom - box.top + 1)
            if (total + size > cap) break
            tableOffsets[b] = total
            total += size
        }
        tableSums = IntArray(total)
        for (b in order) if (tableOffsets[b] >= 0) f.table(b, tableSums, tableOffsets[b])
    }

    /** Block [b]'s own ink within frame cells [l, r) x [t, bt) (clipped to its box). */
    private fun blockInside(b: Int, l: Int, t: Int, r: Int, bt: Int): Int {
        val box = blocksFor[b]
        val x0 = maxOf(l, box.left) - box.left
        val y0 = maxOf(t, box.top) - box.top
        val x1 = minOf(r, box.right) - box.left
        val y1 = minOf(bt, box.bottom) - box.top
        if (x1 <= x0 || y1 <= y0) return 0
        val offset = tableOffsets[b]
        if (offset < 0) return found!!.inkIn(b, x0 + box.left - offX, y0 + box.top - offY, x1 + box.left - offX, y1 + box.top - offY)
        val stride = box.right - box.left + 1
        val sums = tableSums
        return sums[offset + y1 * stride + x1] - sums[offset + y0 * stride + x1] - sums[offset + y1 * stride + x0] + sums[offset + y0 * stride + x0]
    }

    /**
     * How many blocks (with [gap]) of at least [PageBlocks.MIN_BLOCK_INK] cells a [w] x [h]
     * window at ([x], [y]) cuts into: some ink in, some out.
     */
    fun split(x: Int, y: Int, w: Int, h: Int, gap: Int): Int {
        val boxes = blocks(gap)
        val f = found ?: return 0
        return boxes.indices.count { b ->
            val kept = blockInside(b, x, y, x + w, y + h)
            f.inks[b] >= PageBlocks.MIN_BLOCK_INK && kept > 0 && kept < f.inks[b]
        }
    }

    /**
     * Block [b]'s share of a split for a window keeping [kept] of its ink: 0 if all of it or if
     * the block is too small to count; else the cut as a share of [unit] cells, capped at 1. A
     * window keeping none of a block's cells still counts here; [exactShare] makes the one
     * exception (inside a sparse block's box), and a window sticking out of the box yet keeping
     * nothing (an L-shaped block's empty corner) is charged, which is what lets [splitCosts]
     * fill most positions without looking at them.
     */
    private fun share(b: Int, kept: Int, unit: Int): Double {
        val ink = found!!.inks[b]
        val cut = ink - kept
        if (cut <= 0 || ink < PageBlocks.MIN_BLOCK_INK) return 0.0
        return if (cut >= unit) 1.0 else cut.toDouble() / unit
    }

    /**
     * How badly a [w] x [h] window at ([x], [y]) cuts into the page's blocks (with [gap]): for
     * each block whose box it overlaps without containing, the ink cut off that block as a
     * share of [unit] cells, capped at 1, summed. A nibble off a block's edge counts little; a
     * sliced title, line or table row counts a whole block. A window inside a sparse block's
     * box keeping none of its cells (inside a frame) counts nothing for it; one sticking out of
     * a block's box yet keeping none of its cells (an L-shaped block's empty corner) counts as
     * if it cut the block, so this can exceed [split]'s count of blocks with ink on both sides.
     */
    fun splitCost(x: Int, y: Int, w: Int, h: Int, gap: Int, unit: Int): Double {
        val boxes = blocks(gap)
        val window = CellRect(x, y, x + w, y + h)
        var cost = 0.0
        for (b in boxes.indices) {
            if (!boxes[b].overlaps(window)) continue
            cost += exactShare(b, x, y, w, h, unit)
        }
        return cost
    }

    /** [splitCost]'s share of block [b] for one window: a clean drop when inside a sparse box with none of its cells. */
    private fun exactShare(b: Int, x: Int, y: Int, w: Int, h: Int, unit: Int): Double {
        val kept = blockInside(b, x, y, x + w, y + h)
        if (kept == 0 && blockSparse[b] && blocksFor[b].contains(CellRect(x, y, x + w, y + h))) return 0.0
        return share(b, kept, unit)
    }

    /**
     * [splitCost] for every position of a [w] x [h] window at once, into [out] at
     * `y * (width - w + 1) + x`, using [s] for scratch. Each block costs a whole split at every
     * position whose window overlaps its box without containing it, filled through a 2-D
     * difference array, except in a ring of positions around those containing it, where the
     * window cuts fewer than [unit] cells off one side. Along the ring's four strips the window
     * still spans the block in the other direction, so the cut depends on one coordinate only
     * and each strip is a few more lines in the difference array; only the ring's corners are
     * looked up one by one. A block the window can't contain in either direction is looked up
     * at every overlapping position (there are at most a few such blocks: the body at a tight
     * zoom, a big figure), which is also where a window inside a sparse block's box can keep
     * none of its cells and counts nothing. So the work is about linear in the positions plus
     * the blocks' ring strips.
     */
    fun splitCosts(w: Int, h: Int, gap: Int, unit: Int, out: DoubleArray, s: BlockScratch) {
        val cols = width - w + 1
        val rows = height - h + 1
        val boxes = blocks(gap)
        val f = found
        val stride = cols + 1
        val diff = s.diff(stride * (rows + 1))
        diff.fill(0.0, 0, stride * (rows + 1))
        fun add(x0: Int, y0: Int, x1: Int, y1: Int, v: Double) {
            if (x0 > x1 || y0 > y1 || v == 0.0) return
            diff[y0 * stride + x0] += v
            diff[y0 * stride + x1 + 1] -= v
            diff[(y1 + 1) * stride + x0] -= v
            diff[(y1 + 1) * stride + x1 + 1] += v
        }
        // Positions to look up one by one after the fill, per block: up to five rects (four
        // corners, or one whole overlap), as (x0, y0, x1, y1).
        val exact = IntArray(boxes.size * 20)
        val counts = IntArray(boxes.size)
        fun lookup(b: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
            if (x0 > x1 || y0 > y1) return
            val o = b * 20 + counts[b] * 4
            exact[o] = x0; exact[o + 1] = y0; exact[o + 2] = x1; exact[o + 3] = y1
            counts[b]++
        }
        for (b in boxes.indices) {
            if (f!!.inks[b] < PageBlocks.MIN_BLOCK_INK) continue
            val box = boxes[b]
            // Positions whose window overlaps the block's box.
            val ox0 = (box.left - w + 1).coerceAtLeast(0)
            val ox1 = (box.right - 1).coerceAtMost(cols - 1)
            val oy0 = (box.top - h + 1).coerceAtLeast(0)
            val oy1 = (box.bottom - 1).coerceAtMost(rows - 1)
            if (ox0 > ox1 || oy0 > oy1) continue
            // ...and those whose window contains it, on each axis.
            val cx0 = maxOf(box.right - w, ox0)
            val cx1 = minOf(box.left, ox1)
            val cy0 = maxOf(box.bottom - h, oy0)
            val cy1 = minOf(box.top, oy1)
            val spansX = cx0 <= cx1
            val spansY = cy0 <= cy1
            if (!spansX && !spansY) {
                // Bigger than the window both ways. A dense block then loses at least its
                // emptiest bw - w columns (or bh - h rows) at every position: if that alone is a
                // whole unit, every position costs 1 and none needs looking up. A sparse block
                // is looked up, since a window inside its box may keep none of its cells.
                if (!blockSparse[b] && leastCut(b, w, h) >= unit) add(ox0, oy0, ox1, oy1, 1.0)
                else lookup(b, ox0, oy0, ox1, oy1)
                continue
            }
            add(ox0, oy0, ox1, oy1, 1.0)
            if (spansX && spansY) {
                // The ring: how far past containment the window may go on each side before it
                // cuts a whole unit.
                val rx0 = maxOf(ox0, cx0 - edgeRun(b, unit, side = 1))
                val rx1 = minOf(ox1, cx1 + edgeRun(b, unit, side = 0))
                val ry0 = maxOf(oy0, cy0 - edgeRun(b, unit, side = 3))
                val ry1 = minOf(oy1, cy1 + edgeRun(b, unit, side = 2))
                add(rx0, ry0, rx1, ry1, -1.0)
                // Side strips: the window spans the block vertically, the cut depends on x.
                for (x in rx0 until cx0) add(x, cy0, x, cy1, share(b, blockInside(b, x, box.top, x + w, box.bottom), unit))
                for (x in cx1 + 1..rx1) add(x, cy0, x, cy1, share(b, blockInside(b, x, box.top, x + w, box.bottom), unit))
                // Top and bottom strips: the cut depends on y.
                for (y in ry0 until cy0) add(cx0, y, cx1, y, share(b, blockInside(b, box.left, y, box.right, y + h), unit))
                for (y in cy1 + 1..ry1) add(cx0, y, cx1, y, share(b, blockInside(b, box.left, y, box.right, y + h), unit))
                // Corners, one by one.
                lookup(b, rx0, ry0, cx0 - 1, cy0 - 1)
                lookup(b, cx1 + 1, ry0, rx1, cy0 - 1)
                lookup(b, rx0, cy1 + 1, cx0 - 1, ry1)
                lookup(b, cx1 + 1, cy1 + 1, rx1, ry1)
            } else if (spansY) {
                // Wider than the window: where it spans the block vertically the cut depends on
                // x alone, at every x; the rows above and below within the ring are looked up.
                val ry0 = maxOf(oy0, cy0 - edgeRun(b, unit, side = 3))
                val ry1 = minOf(oy1, cy1 + edgeRun(b, unit, side = 2))
                add(ox0, ry0, ox1, ry1, -1.0)
                for (x in ox0..ox1) add(x, cy0, x, cy1, share(b, blockInside(b, x, box.top, x + w, box.bottom), unit))
                lookup(b, ox0, ry0, ox1, cy0 - 1)
                lookup(b, ox0, cy1 + 1, ox1, ry1)
            } else {
                // Taller than the window: likewise with the axes swapped.
                val rx0 = maxOf(ox0, cx0 - edgeRun(b, unit, side = 1))
                val rx1 = minOf(ox1, cx1 + edgeRun(b, unit, side = 0))
                add(rx0, oy0, rx1, oy1, -1.0)
                for (y in oy0..oy1) add(cx0, y, cx1, y, share(b, blockInside(b, box.left, y, box.right, y + h), unit))
                lookup(b, rx0, oy0, cx0 - 1, oy1)
                lookup(b, cx1 + 1, oy0, rx1, oy1)
            }
        }
        for (y in 0 until rows) {
            var run = 0.0
            for (x in 0 until cols) {
                run += diff[y * stride + x]
                out[y * cols + x] = (if (y == 0) 0.0 else out[(y - 1) * cols + x]) + run
            }
        }
        for (b in boxes.indices) for (r in 0 until counts[b]) {
            val o = b * 20 + r * 4
            for (y in exact[o + 1]..exact[o + 3]) for (x in exact[o]..exact[o + 2]) {
                out[y * cols + x] += exactShare(b, x, y, w, h, unit)
            }
        }
    }

    /**
     * The least ink a [w] x [h] window can cut off block [b] when the block is wider and taller
     * than the window: the sum of its emptiest bw - w columns, or of its emptiest bh - h rows,
     * whichever is more. A lower bound, since a window leaves out at least that many whole
     * columns and rows.
     */
    private fun leastCut(b: Int, w: Int, h: Int): Int {
        val box = blocksFor[b]
        val bw = box.right - box.left
        val bh = box.bottom - box.top
        val columns = IntArray(bw) { blockInside(b, box.left + it, box.top, box.left + it + 1, box.bottom) }
        columns.sort()
        var byColumns = 0
        for (i in 0 until bw - w) byColumns += columns[i]
        val rowInks = IntArray(bh) { blockInside(b, box.left, box.top + it, box.right, box.top + it + 1) }
        rowInks.sort()
        var byRows = 0
        for (i in 0 until bh - h) byRows += rowInks[i]
        return maxOf(byColumns, byRows)
    }

    /**
     * How many columns (sides 0 left, 1 right) or rows (2 top, 3 bottom) of block [b], taken
     * from that edge inwards, hold fewer than [u] of its cells: how far a window edge may move
     * into the block from that side before the cut is a whole split.
     */
    private fun edgeRun(b: Int, u: Int, side: Int): Int {
        val box = blocksFor[b]
        val bw = box.right - box.left
        val bh = box.bottom - box.top
        var n = 0
        while (true) {
            val next = n + 1
            val ink = when (side) {
                0 -> if (next > bw) Int.MAX_VALUE else blockInside(b, box.left, box.top, box.left + next, box.bottom)
                1 -> if (next > bw) Int.MAX_VALUE else blockInside(b, box.right - next, box.top, box.right, box.bottom)
                2 -> if (next > bh) Int.MAX_VALUE else blockInside(b, box.left, box.top, box.right, box.top + next)
                else -> if (next > bh) Int.MAX_VALUE else blockInside(b, box.left, box.bottom - next, box.right, box.bottom)
            }
            if (ink >= u) return n
            n = next
        }
    }

    fun windowWidth(scale: Float) = (scale * width).roundToInt().coerceIn(1, width)
    fun windowHeight(scale: Float) = (scale * height).roundToInt().coerceIn(1, height)

    /** Ink in the [w] x [h] cells at ([x], [y]). */
    fun inside(x: Int, y: Int, w: Int, h: Int): Int {
        val stride = width + 1
        return sums[(y + h) * stride + x + w] - sums[y * stride + x + w] -
            sums[(y + h) * stride + x] + sums[y * stride + x]
    }

    /**
     * How far the ink a [w] x [h] window at ([x], [y]) keeps is from being centred in it: the
     * distance from the kept ink's bounding-box centre to the window's centre, in window widths
     * plus window heights. 0 when centred or when nothing is kept; at most 1.
     */
    fun offCentre(x: Int, y: Int, w: Int, h: Int): Double {
        if (inside(x, y, w, h) == 0) return 0.0
        // Blank rows (or columns) at one edge of the window: the most k with no ink in the first k.
        fun blank(n: Int, inked: (Int) -> Boolean): Int {
            var lo = 1
            var hi = n
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (inked(mid)) hi = mid else lo = mid + 1
            }
            return lo - 1
        }
        val top = blank(h) { inside(x, y, w, it) > 0 }
        val bottom = blank(h) { inside(x, y + h - it, w, it) > 0 }
        val left = blank(w) { inside(x, y, it, h) > 0 }
        val right = blank(w) { inside(x + w - it, y, it, h) > 0 }
        return abs(left - right) / (2.0 * w) + abs(top - bottom) / (2.0 * h)
    }

    /**
     * [offCentre] for every position of a [w] x [h] window at once, into [out] at
     * `y * (width - w + 1) + x`. Linear in the frame's size rather than a search per position:
     * from the ink runs along each row, which window columns x see ink in row r (a row's run
     * [a, b) is seen by x in [a - w + 1, b - 1], marked through a difference array), then for
     * each window column the next and previous inked row; and the same the other way round for
     * the window rows. No table lookups.
     */
    fun offCentres(w: Int, h: Int, out: DoubleArray) {
        val cols = width - w + 1
        val rows = height - h + 1
        // Vertical: seenX[x * height + r] is whether row r has ink across [x, x + w).
        val seenX = BooleanArray(cols * height)
        val diff = IntArray(maxOf(cols, rows) + 1)
        for (r in 0 until height) {
            if (rowRuns[r] == rowRuns[r + 1]) continue
            diff.fill(0, 0, cols + 1)
            for (i in rowRuns[r] until rowRuns[r + 1]) {
                diff[(rowRunA[i] - w + 1).coerceAtLeast(0)]++
                diff[(rowRunB[i] - 1).coerceAtMost(cols - 1) + 1]--
            }
            var n = 0
            for (x in 0 until cols) {
                n += diff[x]
                if (n > 0) seenX[x * height + r] = true
            }
        }
        val next = IntArray(maxOf(width, height) + 1)
        val prev = IntArray(maxOf(width, height))
        for (x in 0 until cols) {
            val base = x * height
            next[height] = height
            for (r in height - 1 downTo 0) next[r] = if (seenX[base + r]) r else next[r + 1]
            var last = -1
            for (r in 0 until height) {
                if (seenX[base + r]) last = r
                prev[r] = last
            }
            for (y in 0 until rows) {
                val top = next[y]
                out[y * cols + x] = if (top >= y + h) Double.NaN else {
                    val bottom = prev[y + h - 1]
                    abs((top - y) - (y + h - 1 - bottom)) / (2.0 * h)
                }
            }
        }
        // Horizontal: seenY[y * width + c] is whether column c has ink across [y, y + h).
        val seenY = BooleanArray(rows * width)
        for (c in 0 until width) {
            if (colRuns[c] == colRuns[c + 1]) continue
            diff.fill(0, 0, rows + 1)
            for (i in colRuns[c] until colRuns[c + 1]) {
                diff[(colRunA[i] - h + 1).coerceAtLeast(0)]++
                diff[(colRunB[i] - 1).coerceAtMost(rows - 1) + 1]--
            }
            var n = 0
            for (y in 0 until rows) {
                n += diff[y]
                if (n > 0) seenY[y * width + c] = true
            }
        }
        for (y in 0 until rows) {
            val base = y * width
            next[width] = width
            for (c in width - 1 downTo 0) next[c] = if (seenY[base + c]) c else next[c + 1]
            var last = -1
            for (c in 0 until width) {
                if (seenY[base + c]) last = c
                prev[c] = last
            }
            for (x in 0 until cols) {
                val i = y * cols + x
                // Nothing kept: no content to centre.
                if (out[i].isNaN()) {
                    out[i] = 0.0
                    continue
                }
                val left = next[x]
                val right = prev[x + w - 1]
                out[i] += abs((left - x) - (x + w - 1 - right)) / (2.0 * w)
            }
        }
    }

    // The frame as a multiple of the page: what regionOn pads to, measured in page widths and
    // heights rather than in whole cells, so it doesn't inherit the cells' rounding.
    private val frameWidth = if (maskWidth.toFloat() / maskHeight < aspect) aspect * maskHeight / maskWidth else 1f
    private val frameHeight = if (maskWidth.toFloat() / maskHeight < aspect) 1f else maskWidth / (aspect * maskHeight)

    /**
     * The window of [scale] whose cells start at ([x], [y]), as the reader's [CropWindow]. It is
     * centred on those cells as they lie on the page: the cell frame centres the page in whole
     * cells, but the reader centres it exactly, so with an odd number of cells of padding the
     * two differ by half a cell, and converting via the cell frame would shift the crop by that.
     */
    fun windowAt(x: Int, y: Int, scale: Float): CropWindow {
        val centreX = (x - offX + windowWidth(scale) / 2f) / maskWidth
        val centreY = (y - offY + windowHeight(scale) / 2f) / maskHeight
        val left = (centreX + (frameWidth - 1) / 2) / frameWidth - scale / 2
        val top = (centreY + (frameHeight - 1) / 2) / frameHeight - scale / 2
        return CropWindow(left.coerceIn(0f, 1f - scale), top.coerceIn(0f, 1f - scale), scale)
    }

    /** The cell position whose [windowAt] is nearest [window]: its inverse. */
    fun positionOf(window: CropWindow): Pair<Int, Int> {
        val w = windowWidth(window.scale)
        val h = windowHeight(window.scale)
        val centreX = (window.left + window.scale / 2) * frameWidth - (frameWidth - 1) / 2
        val centreY = (window.top + window.scale / 2) * frameHeight - (frameHeight - 1) / 2
        val x = (centreX * maskWidth - w / 2f + offX).roundToInt().coerceIn(0, width - w)
        val y = (centreY * maskHeight - h / 2f + offY).roundToInt().coerceIn(0, height - h)
        return x to y
    }

    /** The mask cells a window of [scale] at ([x], [y]) covers. */
    fun cellsAt(x: Int, y: Int, scale: Float): CellRect =
        CellRect(x - offX, y - offY, x - offX + windowWidth(scale), y - offY + windowHeight(scale))
}
