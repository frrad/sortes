package dev.sortes.app.zoom

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A page's ink mask centred in a frame with the screen's [aspect] ratio, in whole cells, plus a
 * summed-area table for counting the ink in any window. Shared by [CropAnalysis] and [CropModel].
 */
internal class PaddedPage(mask: InkMask, private val aspect: Float) {
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

    private val mask = mask
    private var blocksGap = -1
    private var blocksFor: List<CellRect> = emptyList()
    private var blockInks = IntArray(0)
    // Whether each block is sparse: under half its box inked (a figure, a frame). A window
    // overlapping a sparse block's box may keep none of its cells, so its share is always from
    // its cells; a dense block (text) is charged for any overlap of its box short of containing
    // it, which lets most positions be filled in one go.
    private var blockSparse = BooleanArray(0)
    // Each block's own summed-area table over its box: sums[i][(y + 1) * (bw + 1) + x + 1] for
    // the block's cells above and left of (x, y) within the box.
    private var blockSums: Array<IntArray> = emptyArray()

    /**
     * The page's blocks ([PageBlocks] with [gap]) as rects in frame cells, kept (with each
     * block's own ink table) for the last gap asked for.
     */
    fun blocks(gap: Int): List<CellRect> {
        if (gap != blocksGap) {
            if (inkCount == 0) {
                blocksFor = emptyList()
                blockInks = IntArray(0)
                blockSparse = BooleanArray(0)
                blockSums = emptyArray()
            } else {
                val found = PageBlocks.of(mask, gap)
                blocksFor = found.boxes.map { CellRect(it.left + offX, it.top + offY, it.right + offX, it.bottom + offY) }
                blockInks = found.inks
                blockSparse = BooleanArray(found.boxes.size) { b ->
                    val box = found.boxes[b]
                    found.inks[b] * 2 < (box.right - box.left) * (box.bottom - box.top)
                }
                blockSums = Array(found.boxes.size) { b ->
                    val box = found.boxes[b]
                    val bw = box.right - box.left
                    val bh = box.bottom - box.top
                    val stride = bw + 1
                    val sums = IntArray(stride * (bh + 1))
                    for (y in 0 until bh) {
                        var row = 0
                        for (x in 0 until bw) {
                            if (found.labels[(box.top + y) * found.maskWidth + box.left + x] == b) row++
                            sums[(y + 1) * stride + x + 1] = sums[y * stride + x + 1] + row
                        }
                    }
                    sums
                }
            }
            blocksGap = gap
        }
        return blocksFor
    }

    /** Block [b]'s own ink within frame cells [l, r) x [t, bt) (clipped to its box). */
    private fun blockInside(b: Int, l: Int, t: Int, r: Int, bt: Int): Int {
        val box = blocksFor[b]
        val x0 = maxOf(l, box.left) - box.left
        val y0 = maxOf(t, box.top) - box.top
        val x1 = minOf(r, box.right) - box.left
        val y1 = minOf(bt, box.bottom) - box.top
        if (x1 <= x0 || y1 <= y0) return 0
        val stride = box.right - box.left + 1
        val sums = blockSums[b]
        return sums[y1 * stride + x1] - sums[y0 * stride + x1] - sums[y1 * stride + x0] + sums[y0 * stride + x0]
    }

    /** How many blocks (with [gap]) a [w] x [h] window at ([x], [y]) cuts into: some ink in, some out. */
    fun split(x: Int, y: Int, w: Int, h: Int, gap: Int): Int {
        val boxes = blocks(gap)
        return boxes.indices.count { b ->
            val kept = blockInside(b, x, y, x + w, y + h)
            blockInks[b] >= PageBlocks.MIN_BLOCK_INK && kept > 0 && kept < blockInks[b]
        }
    }

    /**
     * The ink cut off one block at which it costs a whole split: [unit], or half the block's
     * ink if that is less, so a small block (an equation number, a short heading) cut in two
     * costs as much as a sliced paragraph, and at least one cell.
     */
    private fun unitFor(b: Int, unit: Int): Int = minOf(unit, maxOf(1, blockInks[b] / 2))

    /**
     * Block [b]'s share of a split for a window keeping [kept] of its ink: 0 if all of it or if
     * the block is too small to count; else the cut as a share of the block's unit, capped at 1.
     * A window keeping none of a block's cells still counts here; [exactShare] makes the one
     * exception (inside a sparse block's box), and a window sticking out of the box yet keeping
     * nothing (an L-shaped block's empty corner) is charged, which is what lets [splitCosts]
     * fill most positions without looking at them.
     */
    private fun share(b: Int, kept: Int, unit: Int): Double {
        val ink = blockInks[b]
        val cut = ink - kept
        if (cut <= 0 || ink < PageBlocks.MIN_BLOCK_INK) return 0.0
        val u = unitFor(b, unit)
        return if (cut >= u) 1.0 else cut.toDouble() / u
    }

    /**
     * How badly a [w] x [h] window at ([x], [y]) cuts into the page's blocks (with [gap]): for
     * each block it cuts into, the ink cut off that block as a share of [unit] cells (or of half
     * the block, if smaller), capped at 1, summed. A nibble off a block's edge counts little; a
     * sliced title, line or table row counts a whole block.
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

    /**
     * [splitCost] for every position of a [w] x [h] window at once, into [out] at
     * `y * (width - w + 1) + x`. Each block costs a whole split at every position whose window
     * overlaps its box without containing it, filled through a 2-D difference array, except in a
     * ring of positions around those containing it, where the window cuts fewer than the block's
     * unit of cells off one side and the share is computed exactly from the block's own ink
     * table. The ring is a few cells wide, so that is about linear in the blocks plus the
     * positions. A window that lies entirely inside a sparse block's box (inside a frame, in a
     * figure's white) may keep none of its cells; those positions are computed exactly too, so a
     * frame around other content isn't charged for it. What remains approximate is a window that
     * sticks out of a block's box yet keeps none of its cells (the empty corner of an L-shaped
     * block): it is charged as if it cut the block, as [splitCost] does.
     */
    fun splitCosts(w: Int, h: Int, gap: Int, unit: Int, out: DoubleArray) {
        val cols = width - w + 1
        val rows = height - h + 1
        val boxes = blocks(gap)
        val stride = cols + 1
        val diff = diffBuffer(stride * (rows + 1))
        diff.fill(0.0, 0, stride * (rows + 1))
        fun add(x0: Int, y0: Int, x1: Int, y1: Int, v: Double) {
            if (x0 > x1 || y0 > y1) return
            diff[y0 * stride + x0] += v
            diff[y0 * stride + x1 + 1] -= v
            diff[(y1 + 1) * stride + x0] -= v
            diff[(y1 + 1) * stride + x1 + 1] += v
        }
        // Per block, two rects of positions handled exactly after the fill: the ring (with the
        // containing positions inside it, which cost nothing), and the inside-the-box positions.
        val exact = IntArray(boxes.size * 12)
        for (b in boxes.indices) {
            val o = b * 12
            exact[o] = 1; exact[o + 2] = 0; exact[o + 8] = 1; exact[o + 10] = 0
            if (blockInks[b] < PageBlocks.MIN_BLOCK_INK) continue
            val box = boxes[b]
            val u = unitFor(b, unit)
            val ox0 = (box.left - w + 1).coerceAtLeast(0)
            val ox1 = (box.right - 1).coerceAtMost(cols - 1)
            val oy0 = (box.top - h + 1).coerceAtLeast(0)
            val oy1 = (box.bottom - 1).coerceAtMost(rows - 1)
            if (ox0 > ox1 || oy0 > oy1) continue
            val cx0 = box.right - w
            val cx1 = box.left
            val cy0 = box.bottom - h
            val cy1 = box.top
            val rx0 = if (cx0 <= cx1) maxOf(ox0, cx0 - edgeRun(b, u, side = 1)) else ox0
            val rx1 = if (cx0 <= cx1) minOf(ox1, cx1 + edgeRun(b, u, side = 0)) else ox1
            val ry0 = if (cy0 <= cy1) maxOf(oy0, cy0 - edgeRun(b, u, side = 3)) else oy0
            val ry1 = if (cy0 <= cy1) minOf(oy1, cy1 + edgeRun(b, u, side = 2)) else oy1
            add(ox0, oy0, ox1, oy1, 1.0)
            add(rx0, ry0, rx1, ry1, -1.0)
            exact[o] = rx0; exact[o + 1] = ry0; exact[o + 2] = rx1; exact[o + 3] = ry1
            exact[o + 4] = maxOf(cx0, rx0); exact[o + 5] = maxOf(cy0, ry0)
            exact[o + 6] = minOf(cx1, rx1); exact[o + 7] = minOf(cy1, ry1)
            if (blockSparse[b]) {
                // Windows inside the box: left at or past the box's left, right at or before its right.
                val ix0 = maxOf(box.left, ox0)
                val ix1 = minOf(box.right - w, ox1)
                val iy0 = maxOf(box.top, oy0)
                val iy1 = minOf(box.bottom - h, oy1)
                if (ix0 <= ix1 && iy0 <= iy1) {
                    add(ix0, iy0, ix1, iy1, -1.0)
                    // Where the two exact rects overlap, one was taken off twice: put one back.
                    add(maxOf(ix0, rx0), maxOf(iy0, ry0), minOf(ix1, rx1), minOf(iy1, ry1), 1.0)
                    exact[o + 8] = ix0; exact[o + 9] = iy0; exact[o + 10] = ix1; exact[o + 11] = iy1
                }
            }
        }
        for (y in 0 until rows) {
            var run = 0.0
            for (x in 0 until cols) {
                run += diff[y * stride + x]
                out[y * cols + x] = (if (y == 0) 0.0 else out[(y - 1) * cols + x]) + run
            }
        }
        for (b in boxes.indices) {
            val o = b * 12
            val x0 = exact[o]; val y0 = exact[o + 1]; val x1 = exact[o + 2]; val y1 = exact[o + 3]
            val cx0 = exact[o + 4]; val cy0 = exact[o + 5]; val cx1 = exact[o + 6]; val cy1 = exact[o + 7]
            val ix0 = exact[o + 8]; val iy0 = exact[o + 9]; val ix1 = exact[o + 10]; val iy1 = exact[o + 11]
            for (y in y0..y1) {
                val containsY = y in cy0..cy1
                for (x in x0..x1) {
                    if (containsY && x in cx0..cx1) continue
                    out[y * cols + x] += exactShare(b, x, y, w, h, unit)
                }
            }
            for (y in iy0..iy1) for (x in ix0..ix1) {
                if (x in x0..x1 && y in y0..y1) continue
                out[y * cols + x] += exactShare(b, x, y, w, h, unit)
            }
        }
    }

    /** [splitCost]'s share of block [b] for one window: a clean drop when inside a sparse box with none of its cells. */
    private fun exactShare(b: Int, x: Int, y: Int, w: Int, h: Int, unit: Int): Double {
        val kept = blockInside(b, x, y, x + w, y + h)
        if (kept == 0 && blockSparse[b] && blocksFor[b].contains(CellRect(x, y, x + w, y + h))) return 0.0
        return share(b, kept, unit)
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

    private var diff = DoubleArray(0)
    private fun diffBuffer(size: Int): DoubleArray {
        if (diff.size < size) diff = DoubleArray(size)
        return diff
    }

    fun windowWidth(scale: Float) = (scale * width).roundToInt().coerceIn(1, width)
    fun windowHeight(scale: Float) = (scale * height).roundToInt().coerceIn(1, height)

    /** Ink in the [w] x [h] cells at ([x], [y]). */
    fun inside(x: Int, y: Int, w: Int, h: Int): Int {
        val stride = width + 1
        return sums[(y + h) * stride + x + w] - sums[y * stride + x + w] -
            sums[(y + h) * stride + x] + sums[y * stride + x]
    }

    /** The most ink any window of [scale] can keep. */
    fun maxInside(scale: Float): Int {
        if (inkCount == 0) return 0
        val w = windowWidth(scale)
        val h = windowHeight(scale)
        var best = 0
        for (y in 0..height - h) for (x in 0..width - w) {
            val v = inside(x, y, w, h)
            if (v > best) best = v
        }
        return best
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
     * for each window column it finds the next and previous inked row, and for each window row
     * the next and previous inked column.
     */
    fun offCentres(w: Int, h: Int, out: DoubleArray) {
        val cols = width - w + 1
        val rows = height - h + 1
        // Vertical: for each window column x, the rows with ink across [x, x + w).
        val next = IntArray(maxOf(width, height) + 1)
        val prev = IntArray(maxOf(width, height))
        for (x in 0 until cols) {
            next[height] = height
            for (r in height - 1 downTo 0) next[r] = if (inside(x, r, w, 1) > 0) r else next[r + 1]
            var last = -1
            for (r in 0 until height) {
                if (inside(x, r, w, 1) > 0) last = r
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
        // Horizontal: for each window row y, the columns with ink across [y, y + h).
        for (y in 0 until rows) {
            next[width] = width
            for (c in width - 1 downTo 0) next[c] = if (inside(c, y, 1, h) > 0) c else next[c + 1]
            var last = -1
            for (c in 0 until width) {
                if (inside(c, y, 1, h) > 0) last = c
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
