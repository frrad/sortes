package dev.sortes.zoomeval.content

import dev.sortes.app.zoom.CellRect
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.PageBlocks
import kotlin.math.max
import kotlin.math.min

/**
 * Experimental page cost: lines a window's left or right edge truncates.
 *
 * A "line segment" is a horizontal run of fine connected components (glyphs, roughly) that
 * overlap vertically by at least half the shorter one's height and are separated by at most
 * [gap] glyph heights: a line of a paragraph, an equation, a row of a table cell. Column gutters
 * and the space before an equation number are wider, so they split segments. A window's edge
 * TRUNCATES a segment when it keeps some of its components and loses others: the end of an
 * equation clipped off, rather than a whole line left out (which [ElementLoss] already counts).
 * Each truncated segment costs min(1, lost components / [saturation]), so clipping one or two
 * symbols costs nearly as much as clipping half the line: the reader can't see what's missing.
 *
 * Only the left and right edges count: a top or bottom edge slices a line through its glyphs,
 * which the ink and block costs already see. Components are kept only when wholly inside the
 * kept cells. The edges are costed separately (a segment truncated at both ends costs twice) so
 * the cost is a sum of per-edge tables, O(1) per window.
 */
class LineTruncation private constructor(
    private val width: Int,
    private val height: Int,
    /** left[x * (height + 1) + y]: cost of a left edge at column x, over segments centred above row y. */
    private val left: DoubleArray,
    private val right: DoubleArray,
    private val segments: List<IntArray>,
    private val cl: IntArray,
    private val cr: IntArray,
    private val segmentRow: IntArray,
) {
    /** The cost of keeping [cells]. */
    fun cost(cells: CellRect): Double {
        val t = cells.top.coerceIn(0, height)
        val b = cells.bottom.coerceIn(t, height)
        val l = cells.left.coerceIn(0, width)
        val r = cells.right.coerceIn(0, width)
        val s = height + 1
        return left[l * s + b] - left[l * s + t] + right[r * s + b] - right[r * s + t]
    }

    /** How many segments [cells] truncates at its left or right edge (for reporting). */
    fun truncated(cells: CellRect): Int = truncatedSegments(cells).size

    /** The segments [cells] truncates at its left or right edge, by index. */
    fun truncatedSegments(cells: CellRect): Set<Int> = segments.indices.filterTo(HashSet()) { i ->
        if (segmentRow[i] !in cells.top until cells.bottom) return@filterTo false
        val members = segments[i]
        val kept = members.count { cl[it] >= cells.left && cr[it] <= cells.right }
        kept in 1 until members.size
    }

    /**
     * Segments [shown] truncates that [ideal] keeps whole: lines the owner's crop shows in full
     * and the model's clips. A line [ideal] leaves out entirely, or clips too, doesn't count.
     */
    fun clippedAgainst(shown: CellRect, ideal: CellRect): Int {
        val idealCut = truncatedSegments(ideal)
        return truncatedSegments(shown).count { i ->
            i !in idealCut && segmentRow[i] in ideal.top until ideal.bottom &&
                segments[i].all { cl[it] >= ideal.left && cr[it] <= ideal.right }
        }
    }

    companion object {
        fun of(fine: InkMask, raw: InkMask, prepared: InkMask, gap: Double = 1.5, saturation: Double = 2.0): LineTruncation {
            val factorX = fine.width / raw.width
            val factorY = fine.height / raw.height
            require(factorX >= 1 && factorY >= 1 && fine.width == raw.width * factorX && fine.height == raw.height * factorY) {
                "Fine mask must be a whole multiple of the solver mask"
            }
            val glyph = TextSize.glyphHeight(fine) ?: (2.0 * factorY)
            val comps = PageBlocks.of(fine, 0)
            val labels = comps.labels()
            // Leave out components that are mostly ignorable ink, as ElementLoss does.
            val removed = IntArray(comps.boxes.size)
            for (y in 0 until fine.height) for (x in 0 until fine.width) {
                val c = labels[y * fine.width + x]
                if (c >= 0 && raw[x / factorX, y / factorY] && !prepared[x / factorX, y / factorY]) removed[c]++
            }
            val ids = comps.boxes.indices.filter { removed[it] * 2 < comps.inks[it] }
            val boxes = ids.map { comps.boxes[it] }
            val n = boxes.size
            // Union nearby components on a line; tall ones (figures) stay on their own.
            val parent = IntArray(n) { it }
            fun find(i: Int): Int {
                var r = i
                while (parent[r] != r) r = parent[r]
                var j = i
                while (parent[j] != r) { val next = parent[j]; parent[j] = r; j = next }
                return r
            }
            val tall = max(3.0 * glyph, 4.0)
            val maxGap = gap * glyph
            val band = max(1, glyph.toInt())
            val buckets = HashMap<Int, MutableList<Int>>()
            for (i in 0 until n) {
                val bx = boxes[i]
                if (bx.bottom - bx.top > tall) continue
                for (k in bx.top / band..(bx.bottom - 1) / band) buckets.getOrPut(k) { ArrayList() } += i
            }
            for (list in buckets.values) {
                list.sortBy { boxes[it].left }
                for (a in list.indices) {
                    val i = list[a]
                    val bi = boxes[i]
                    for (c in a + 1 until list.size) {
                        val j = list[c]
                        val bj = boxes[j]
                        if (bj.left - bi.right > maxGap) {
                            // Sorted by left, but a wide component can reach past a later one: only stop
                            // once nothing more can be close.
                            break
                        }
                        val overlap = min(bi.bottom, bj.bottom) - max(bi.top, bj.top)
                        val shorter = min(bi.bottom - bi.top, bj.bottom - bj.top)
                        if (overlap * 2 >= shorter) parent[find(i)] = find(j)
                    }
                }
            }
            val groups = (0 until n).groupBy { find(it) }.values.map { it.toIntArray() }
            val cl = IntArray(n) { boxes[it].left / factorX }
            val cr = IntArray(n) { (boxes[it].right + factorX - 1) / factorX }
            val w = raw.width
            val h = raw.height
            val s = h + 1
            val left = DoubleArray((w + 1) * s)
            val right = DoubleArray((w + 1) * s)
            val rows = IntArray(groups.size)
            for ((g, members) in groups.withIndex()) {
                val top = members.minOf { boxes[it].top }
                val bottom = members.maxOf { boxes[it].bottom }
                val row = ((top + bottom) / 2 / factorY).coerceIn(0, h - 1)
                rows[g] = row
                if (members.size < 2) continue
                val lefts = members.map { cl[it] }.sorted()
                val rights = members.map { cr[it] }.sortedDescending()
                // A left edge at x loses the components starting left of it.
                var lost = 0
                for (x in lefts.first() + 1..lefts.last()) {
                    while (lost < lefts.size && lefts[lost] < x) lost++
                    if (lost in 1 until members.size) left[x * s + row + 1] += min(1.0, lost / saturation)
                }
                lost = 0
                for (x in rights.first() - 1 downTo rights.last()) {
                    while (lost < rights.size && rights[lost] > x) lost++
                    if (lost in 1 until members.size) right[x * s + row + 1] += min(1.0, lost / saturation)
                }
            }
            for (x in 0..w) for (y in 1..h) {
                left[x * s + y] += left[x * s + y - 1]
                right[x * s + y] += right[x * s + y - 1]
            }
            return LineTruncation(w, h, left, right, groups, cl, cr, rows)
        }
    }
}
