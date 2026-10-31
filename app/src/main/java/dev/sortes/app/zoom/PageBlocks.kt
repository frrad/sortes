package dev.sortes.app.zoom

/**
 * A page's ink grouped into blocks: the things on the page a crop should keep or drop whole rather
 * than cut through. Runs of ink along a row with at most [gap] blank cells between them are one
 * piece, and pieces in rows at most [gap] + 1 apart that come within [gap] + 1 columns of each
 * other are one block (which is the same as joining any two inked cells at most [gap] + 1 apart in
 * both directions). At the analysis resolution (about 3 pt per cell) a gap of 2 joins the lines of
 * a paragraph and the paragraphs of a section, but leaves a heading, a figure and its caption, or
 * two columns apart.
 *
 * Each block has its bounding box in mask cells, its ink, and its runs. Boxes can overlap or nest
 * (an L-shaped paragraph beside a figure, a frame around its contents), so what a window cuts off a
 * block is counted from its own cells, never from its box. The runs are the compact record of those
 * cells: a few kilobytes a page, against a label map's hundreds.
 */
class PageBlocks
private constructor(
    private val mask: InkMask,
    val boxes: List<CellRect>,
    /** Each block's ink, in cells. */
    val inks: IntArray,
    // Runs grouped by block: block b's runs are indices blockRuns[b] until blockRuns[b + 1];
    // each run is row runRow, columns runStart until runEnd (bridged blanks included).
    private val blockRuns: IntArray,
    private val runStart: IntArray,
    private val runEnd: IntArray,
    private val runRow: IntArray,
) {
    val maskWidth: Int
        get() = mask.width

    /** Block [b]'s ink inside mask cells [l, r) x [t, b): from its runs, so linear in them. */
    fun inkIn(b: Int, l: Int, t: Int, r: Int, bt: Int): Int {
        var n = 0
        for (i in blockRuns[b] until blockRuns[b + 1]) {
            val y = runRow[i]
            if (y < t || y >= bt) continue
            for (x in maxOf(l, runStart[i]) until minOf(r, runEnd[i])) if (mask[x, y]) n++
        }
        return n
    }

    /**
     * Fills [sums], a summed-area table over block [b]'s box ((bw + 1) x (bh + 1), from [offset]),
     * with its cells.
     */
    fun table(b: Int, sums: IntArray, offset: Int) {
        val box = boxes[b]
        val bw = box.right - box.left
        val bh = box.bottom - box.top
        val stride = bw + 1
        sums.fill(0, offset, offset + stride * (bh + 1))
        for (i in blockRuns[b] until blockRuns[b + 1]) {
            val y = runRow[i] - box.top
            for (x in runStart[i] until runEnd[i]) if (mask[x, runRow[i]])
                sums[offset + (y + 1) * stride + x - box.left + 1] = 1
        }
        for (y in 1..bh) {
            var row = 0
            for (x in 1..bw) {
                row += sums[offset + y * stride + x]
                sums[offset + y * stride + x] = sums[offset + (y - 1) * stride + x] + row
            }
        }
    }

    /** The block each mask cell belongs to, row-major, or -1 for paper (for tests and tools). */
    fun labels(): IntArray {
        val labels = IntArray(mask.width * mask.height) { -1 }
        for (b in boxes.indices) for (i in blockRuns[b] until blockRuns[b + 1]) {
            for (x in runStart[i] until runEnd[i]) if (mask[x, runRow[i]])
                labels[runRow[i] * mask.width + x] = b
        }
        return labels
    }

    /** Each block's ink inside [cells], by block. */
    fun kept(cells: CellRect): IntArray =
        IntArray(boxes.size) { inkIn(it, cells.left, cells.top, cells.right, cells.bottom) }

    /**
     * How many blocks of at least [MIN_BLOCK_INK] cells a window covering [cells] cuts into: some
     * of their ink in, some out.
     */
    fun split(cells: CellRect): Int =
        kept(cells).withIndex().count { (b, k) -> inks[b] >= MIN_BLOCK_INK && k > 0 && k < inks[b] }

    companion object {
        /**
         * Fewest cells of ink a block needs to count as a thing to keep whole: fewer is a speck of
         * scan dirt or a stray dot (about half a glyph at the analysis resolution), which still
         * counts as ink but not as something a window edge can cut through. Equation numbers and
         * short headings are 8 cells and more.
         */
        const val MIN_BLOCK_INK = 8

        fun of(mask: InkMask, gap: Int): PageBlocks {
            require(gap >= 0) { "gap must not be negative: $gap" }
            val w = mask.width
            val h = mask.height
            val reach = gap + 1
            // Runs of ink per row, bridging up to gap blanks; rowStart[y] indexes row y's first
            // run.
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            val inks = ArrayList<Int>()
            val rows = ArrayList<Int>()
            val rowStart = IntArray(h + 1)
            for (y in 0 until h) {
                rowStart[y] = starts.size
                var x = 0
                while (x < w) {
                    if (!mask[x, y]) {
                        x++
                        continue
                    }
                    val start = x
                    var end = x + 1
                    var ink = 1
                    var last = x
                    x++
                    while (x < w && x - last - 1 <= gap) {
                        if (mask[x, y]) {
                            ink++
                            last = x
                            end = x + 1
                        }
                        x++
                    }
                    starts += start
                    ends += end
                    inks += ink
                    rows += y
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
                while (parent[c] != r) {
                    val next = parent[c]
                    parent[c] = r
                    c = next
                }
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
            // Blocks in order of their first run; runs regrouped by block.
            val index = IntArray(n) { -1 }
            val blockOf = IntArray(n)
            val boxes = ArrayList<CellRect>()
            val blockInks = ArrayList<Int>()
            val counts = ArrayList<Int>()
            for (r in 0 until n) {
                val root = find(r)
                var b = index[root]
                if (b < 0) {
                    b = boxes.size
                    index[root] = b
                    boxes += CellRect(starts[r], rows[r], ends[r], rows[r] + 1)
                    blockInks += 0
                    counts += 0
                }
                blockOf[r] = b
                val box = boxes[b]
                boxes[b] =
                    CellRect(
                        minOf(box.left, starts[r]),
                        minOf(box.top, rows[r]),
                        maxOf(box.right, ends[r]),
                        maxOf(box.bottom, rows[r] + 1),
                    )
                blockInks[b] = blockInks[b] + inks[r]
                counts[b] = counts[b] + 1
            }
            val blockRuns = IntArray(boxes.size + 1)
            for (b in boxes.indices) blockRuns[b + 1] = blockRuns[b] + counts[b]
            val fill = blockRuns.copyOf()
            val runStart = IntArray(n)
            val runEnd = IntArray(n)
            val runRow = IntArray(n)
            for (r in 0 until n) {
                val i = fill[blockOf[r]]++
                runStart[i] = starts[r]
                runEnd[i] = ends[r]
                runRow[i] = rows[r]
            }
            return PageBlocks(
                mask,
                boxes,
                blockInks.toIntArray(),
                blockRuns,
                runStart,
                runEnd,
                runRow,
            )
        }
    }
}

/** Whether this rect and [other] share any cell. */
internal fun CellRect.overlaps(other: CellRect): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom

/** Whether [other] lies entirely inside this rect. */
internal fun CellRect.contains(other: CellRect): Boolean =
    other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

/**
 * Scratch space for the block costs, shared across pages and scales: the difference array over
 * window positions, which would otherwise be kept per page at the tightest scale's size.
 */
class BlockScratch {
    internal var diff = DoubleArray(0)

    internal fun diff(size: Int): DoubleArray {
        if (diff.size < size) diff = DoubleArray(size)
        return diff
    }
}
