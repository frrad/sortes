package dev.sortes.app.zoom

/**
 * A page's ink grouped into blocks: the things on the page a crop should keep or drop whole
 * rather than cut through. Runs of ink along a row with at most [gap] blank cells between them
 * are one piece, and pieces in rows at most [gap] + 1 apart that come within [gap] + 1 columns
 * of each other are one block. At the analysis resolution (about 3 pt per cell) a gap of 2
 * joins the lines of a paragraph and the paragraphs of a section, but leaves a heading, a
 * figure and its caption, or two columns apart.
 *
 * Each block has its bounding box in mask cells, its ink, and the cells that are its own
 * ([labels]). Boxes can overlap or nest (an L-shaped paragraph beside a figure, a frame around
 * its contents), so what a window cuts off a block is counted from its own cells, never from its
 * box.
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
        /**
         * Fewest cells of ink a block needs to count as a thing to keep whole: fewer is a speck
         * of scan dirt or a stray dot (about half a glyph at the analysis resolution), which
         * still counts as ink but not as something a window edge can cut through. Equation
         * numbers and short headings are 8 cells and more.
         */
        const val MIN_BLOCK_INK = 8

        fun of(mask: InkMask, gap: Int): PageBlocks {
            require(gap >= 0) { "gap must not be negative: $gap" }
            val w = mask.width
            val h = mask.height
            val reach = gap + 1
            // Runs of ink per row, bridging up to gap blanks: start column, end column
            // (exclusive), ink cells, row; rowStart[y] indexes the first run of row y.
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            val inks = ArrayList<Int>()
            val rows = ArrayList<Int>()
            val rowStart = IntArray(h + 1)
            for (y in 0 until h) {
                rowStart[y] = starts.size
                var x = 0
                while (x < w) {
                    if (!mask[x, y]) { x++; continue }
                    val start = x
                    var end = x + 1
                    var ink = 1
                    var last = x
                    x++
                    while (x < w && x - last - 1 <= gap) {
                        if (mask[x, y]) { ink++; last = x; end = x + 1 }
                        x++
                    }
                    starts += start; ends += end; inks += ink; rows += y
                }
            }
            rowStart[h] = starts.size
            val n = starts.size
            // Union-find over runs: each run joins the runs of the previous `reach` rows it comes
            // within `reach` columns of.
            val parent = IntArray(n) { it }
            fun find(a: Int): Int {
                var r = a
                while (parent[r] != r) r = parent[r]
                var c = a
                while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
                return r
            }
            for (r in 0 until n) {
                val y = rows[r]
                for (py in (y - reach).coerceAtLeast(0) until y) {
                    for (q in rowStart[py] until rowStart[py + 1]) {
                        if (starts[q] <= ends[r] - 1 + reach && starts[r] <= ends[q] - 1 + reach) {
                            val a = find(r)
                            val b = find(q)
                            if (a != b) parent[a] = b
                        }
                    }
                }
            }
            // Blocks in order of their first run.
            val index = IntArray(n) { -1 }
            val boxes = ArrayList<CellRect>()
            val blockInks = ArrayList<Int>()
            val labels = IntArray(w * h) { -1 }
            for (r in 0 until n) {
                val root = find(r)
                var b = index[root]
                if (b < 0) {
                    b = boxes.size
                    index[root] = b
                    boxes += CellRect(starts[r], rows[r], ends[r], rows[r] + 1)
                    blockInks += 0
                }
                val box = boxes[b]
                boxes[b] = CellRect(minOf(box.left, starts[r]), minOf(box.top, rows[r]), maxOf(box.right, ends[r]), maxOf(box.bottom, rows[r] + 1))
                blockInks[b] = blockInks[b] + inks[r]
                for (x in starts[r] until ends[r]) if (mask[x, rows[r]]) labels[rows[r] * w + x] = b
            }
            return PageBlocks(w, boxes, blockInks.toIntArray(), labels)
        }
    }
}

/** Whether this rect and [other] share any cell. */
internal fun CellRect.overlaps(other: CellRect): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom

/** Whether [other] lies entirely inside this rect. */
internal fun CellRect.contains(other: CellRect): Boolean =
    other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom
