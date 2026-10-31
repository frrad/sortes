package dev.sortes.app.zoom

/**
 * Ignores isolated marks: small blobs of ink with nothing else near them, like scan dirt, the edge
 * of the paper in a scan, or a stray page number. Left in, a speck in a corner holds the window
 * loose or off-centre. The owner's Shannon labels (a scan) asked for a tighter, better balanced
 * crop than such marks allowed.
 *
 * A mark is a connected piece of ink (cells touching, diagonals included) that is
 * - small: at most [MARK_MAX_SHARE] of the page's ink,
 * - alone: no other ink within [MARK_MIN_GAP] of the page height around its bounding box, on a page
 *   with other ink.
 *
 * Used by [CropConfig.DEFAULT], after [IGNORABLE_INK_RULES].
 */
object IsolatedMarks : IgnorableInkRule {
    override fun strip(mask: InkMask): InkMask {
        val total = mask.inkCount
        if (total == 0) return mask
        val w = mask.width
        val h = mask.height
        // Summed-area table of the ink, for "any other ink near this piece?"
        val sums = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                if (mask[x, y]) row++
                sums[(y + 1) * (w + 1) + x + 1] = sums[y * (w + 1) + x + 1] + row
            }
        }
        fun inkIn(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            val l = x0.coerceIn(0, w)
            val t = y0.coerceIn(0, h)
            val r = x1.coerceIn(0, w)
            val b = y1.coerceIn(0, h)
            if (r <= l || b <= t) return 0
            return sums[b * (w + 1) + r] - sums[t * (w + 1) + r] - sums[b * (w + 1) + l] +
                sums[t * (w + 1) + l]
        }
        val gap = maxOf(MARK_MIN_GAP_CELLS, (h * MARK_MIN_GAP).toInt())
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val drop = BooleanArray(w * h)
        var dropped = 0
        for (start in 0 until w * h) {
            if (seen[start] || !mask[start % w, start / w]) continue
            seen[start] = true
            var head = 0
            var tail = 0
            queue[tail++] = start
            var x0 = w
            var y0 = h
            var x1 = -1
            var y1 = -1
            while (head < tail) {
                val cell = queue[head++]
                val cx = cell % w
                val cy = cell / w
                if (cx < x0) x0 = cx
                if (cx > x1) x1 = cx
                if (cy < y0) y0 = cy
                if (cy > y1) y1 = cy
                for (ny in (cy - 1).coerceAtLeast(0)..(cy + 1).coerceAtMost(h - 1)) {
                    for (nx in (cx - 1).coerceAtLeast(0)..(cx + 1).coerceAtMost(w - 1)) {
                        val n = ny * w + nx
                        if (!seen[n] && mask[nx, ny]) {
                            seen[n] = true
                            queue[tail++] = n
                        }
                    }
                }
            }
            val size = tail
            if (size > total * MARK_MAX_SHARE || size == total) continue
            // Its own ink is all inside its box: anything more around it is something else.
            if (
                inkIn(x0 - gap, y0 - gap, x1 + 1 + gap, y1 + 1 + gap) !=
                    inkIn(x0, y0, x1 + 1, y1 + 1)
            )
                continue
            if (inkIn(x0, y0, x1 + 1, y1 + 1) != size) continue
            for (i in 0 until size) drop[queue[i]] = true
            dropped += size
        }
        if (dropped == 0 || dropped == total) return mask
        return InkMask(w, h, BooleanArray(w * h) { i -> mask[i % w, i / w] && !drop[i] })
    }
}

/** Largest share of a page's ink a mark may be. */
const val MARK_MAX_SHARE = 0.005f

/** Blank space all around a mark, as a fraction of the page height... */
const val MARK_MIN_GAP = 0.03f

/** ...and at least this many cells. */
const val MARK_MIN_GAP_CELLS = 3
