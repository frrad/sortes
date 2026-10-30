package dev.sortes.app.zoom

/**
 * A page's ink grouped into blocks: the things on the page a crop should keep or drop whole
 * rather than cut through. A block is a connected piece of ink, where two inked cells belong
 * together when at most [gap] blank cells separate them (in either direction, diagonals
 * included). At the analysis resolution (about 3 pt per cell) a gap of 2 joins the lines of a
 * paragraph and the paragraphs of a section, but leaves a heading, a figure and its caption, or
 * two columns apart.
 *
 * Each block has its bounding box in mask cells, its ink, and the cells that are its own
 * ([labels]). A connected piece has ink in every row and column of its box, but boxes can
 * overlap or nest (an L-shaped paragraph beside a figure, a frame around its contents), so what
 * a window cuts off a block is counted from its own cells, never from its box.
 */
class PageBlocks private constructor(
    val maskWidth: Int,
    val boxes: List<CellRect>,
    /** Each block's ink, in cells. */
    val inks: IntArray,
    /** The block each mask cell belongs to, row-major, or -1 for paper. */
    val labels: IntArray,
) {
    /** Each block's ink inside [cells], by block. */
    fun kept(cells: CellRect): IntArray {
        val kept = IntArray(boxes.size)
        for (i in labels.indices) {
            val b = labels[i]
            if (b < 0) continue
            val x = i % maskWidth
            val y = i / maskWidth
            if (x >= cells.left && x < cells.right && y >= cells.top && y < cells.bottom) kept[b]++
        }
        return kept
    }

    /** How many blocks a window covering [cells] cuts into: some of their ink in, some out. */
    fun split(cells: CellRect): Int = kept(cells).withIndex().count { (b, k) -> k > 0 && k < inks[b] }

    companion object {
        fun of(mask: InkMask, gap: Int): PageBlocks {
            require(gap >= 0) { "gap must not be negative: $gap" }
            val w = mask.width
            val h = mask.height
            val seen = BooleanArray(w * h)
            val queue = IntArray(w * h)
            val boxes = mutableListOf<CellRect>()
            val inks = mutableListOf<Int>()
            val labels = IntArray(w * h) { -1 }
            val reach = gap + 1
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
                    for (ny in (cy - reach).coerceAtLeast(0)..(cy + reach).coerceAtMost(h - 1)) {
                        for (nx in (cx - reach).coerceAtLeast(0)..(cx + reach).coerceAtMost(w - 1)) {
                            val n = ny * w + nx
                            if (!seen[n] && mask[nx, ny]) {
                                seen[n] = true
                                queue[tail++] = n
                            }
                        }
                    }
                }
                for (i in 0 until tail) labels[queue[i]] = boxes.size
                boxes += CellRect(x0, y0, x1 + 1, y1 + 1)
                inks += tail
            }
            return PageBlocks(w, boxes, inks.toIntArray(), labels)
        }
    }
}

/** Whether this rect and [other] share any cell. */
internal fun CellRect.overlaps(other: CellRect): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom

/** Whether [other] lies entirely inside this rect. */
internal fun CellRect.contains(other: CellRect): Boolean =
    other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom
