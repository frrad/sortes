package dev.sortes.app.zoom

/**
 * A page's ink grouped into blocks: the things on the page a crop should keep or drop whole
 * rather than cut through. A block is a connected piece of ink, where two inked cells belong
 * together when at most [gap] blank cells separate them (in either direction, diagonals
 * included). At the analysis resolution (about 3 pt per cell) a gap of 2 joins the lines of a
 * paragraph and the paragraphs of a section, but leaves a heading, a figure and its caption, or
 * two columns apart.
 *
 * Each block is its bounding box in mask cells. A connected piece has ink in every row and
 * column of its box, so a window edge through the box passes through the block.
 */
class PageBlocks private constructor(val boxes: List<CellRect>, /** Each block's ink, in cells. */ val inks: IntArray) {

    /** How many of the blocks a window covering [cells] passes through: overlaps but doesn't contain. */
    fun split(cells: CellRect): Int = boxes.count { it.overlaps(cells) && !cells.contains(it) }

    companion object {
        fun of(mask: InkMask, gap: Int): PageBlocks {
            require(gap >= 0) { "gap must not be negative: $gap" }
            val w = mask.width
            val h = mask.height
            val seen = BooleanArray(w * h)
            val queue = IntArray(w * h)
            val boxes = mutableListOf<CellRect>()
            val inks = mutableListOf<Int>()
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
                boxes += CellRect(x0, y0, x1 + 1, y1 + 1)
                inks += tail
            }
            return PageBlocks(boxes, inks.toIntArray())
        }
    }
}

/** Whether this rect and [other] share any cell. */
fun CellRect.overlaps(other: CellRect): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom

/** Whether [other] lies entirely inside this rect. */
fun CellRect.contains(other: CellRect): Boolean =
    other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom
