package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropAnalysis
import dev.sortes.app.zoom.CropWindow
import dev.sortes.app.zoom.InkMask
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Weights of the crop model's cost (see tools/zoom-eval/SPEC.md):
 *
 *   cost = Σ_pages [ alpha·cut + beta·offCentre ] + gamma·(K − 1) + delta·switches + delta2·breaks
 *
 * where a switch is a page whose window differs from the previous page's, and a break is a page
 * whose window differs from the one two pages back: breaks penalise jitter and one-off windows
 * but not a steady left/right alternation.
 */
data class Weights(
    /** Per page-of-ink cut off (cut ink / the paper's median ink per page). */
    val alpha: Double = 1.0,
    /** Per screen of content-centre displacement (horizontal + vertical, see [FramedPage.offCentre]). */
    val beta: Double = 0.1,
    /** Per window beyond the first. */
    val gamma: Double = 0.05,
    /** Per page turn that changes window. */
    val delta: Double = 0.0,
    /** Per page whose window differs from the one two pages back. */
    val delta2: Double = 0.02,
    /** Most windows the solver tries. */
    val maxWindows: Int = 3,
)

/**
 * A page's ink mask centred in a frame with the screen's aspect ratio, padded exactly as
 * [CropAnalysis] pads it, with a summed-area table for counting ink in windows.
 */
class FramedPage(mask: InkMask, aspect: Float) {
    val width: Int
    val height: Int
    val inkCount = mask.inkCount
    private val sums: IntArray

    init {
        if (mask.width.toFloat() / mask.height < aspect) {
            width = (mask.height * aspect).roundToInt().coerceAtLeast(mask.width)
            height = mask.height
        } else {
            width = mask.width
            height = (mask.width / aspect).roundToInt().coerceAtLeast(mask.height)
        }
        val offX = (width - mask.width) / 2
        val offY = (height - mask.height) / 2
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

    fun windowWidth(scale: Float) = (scale * width).roundToInt().coerceIn(1, width)
    fun windowHeight(scale: Float) = (scale * height).roundToInt().coerceIn(1, height)

    /** Ink in the w × h cells at (x, y). */
    fun inside(x: Int, y: Int, w: Int, h: Int): Int {
        val stride = width + 1
        return sums[(y + h) * stride + x + w] - sums[y * stride + x + w] -
            sums[(y + h) * stride + x] + sums[y * stride + x]
    }

    /**
     * How far the ink kept by a window is from being centred in it: the distance from the kept
     * ink's bounding-box centre to the window centre, in window widths plus window heights.
     * 0 when centred (or nothing is kept); 0.5 + 0.5 at worst.
     */
    fun offCentre(x: Int, y: Int, w: Int, h: Int): Double {
        if (inside(x, y, w, h) == 0) return 0.0
        // Smallest k with ink in the first k rows (or columns) of the window, by binary search.
        fun first(n: Int, inked: (Int) -> Boolean): Int {
            var lo = 1
            var hi = n
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (inked(mid)) hi = mid else lo = mid + 1
            }
            return lo
        }
        val top = first(h) { inside(x, y, w, it) > 0 } - 1
        val bottom = first(h) { inside(x, y + h - it, w, it) > 0 } - 1
        val left = first(w) { inside(x, y, it, h) > 0 } - 1
        val right = first(w) { inside(x + w - it, y, it, h) > 0 } - 1
        return abs(left - right) / (2.0 * w) + abs(top - bottom) / (2.0 * h)
    }

    /** The cell position of a window of [scale] given as a [CropWindow] on this frame. */
    fun cellOf(window: CropWindow): Pair<Int, Int> {
        val w = windowWidth(window.scale)
        val h = windowHeight(window.scale)
        val x = ((window.left + window.scale / 2) * width - w / 2f).roundToInt().coerceIn(0, width - w)
        val y = ((window.top + window.scale / 2) * height - h / 2f).roundToInt().coerceIn(0, height - h)
        return x to y
    }

    /** The window at cell (x, y), as the reader's [CropWindow]; centred as CropAnalysis does. */
    fun windowAt(x: Int, y: Int, scale: Float): CropWindow {
        val w = windowWidth(scale)
        val h = windowHeight(scale)
        val left = (x + w / 2f) / width - scale / 2
        val top = (y + h / 2f) / height - scale / 2
        return CropWindow(left.coerceIn(0f, 1f - scale), top.coerceIn(0f, 1f - scale), scale)
    }
}

/** One page's share of the cost. */
data class PageTerms(val window: Int, val cutCells: Int, val cut: Double, val offCentre: Double)

/** A paper's crops: one window per page, drawn from [windows], with the cost broken down. */
data class Crops(
    val scale: Float,
    val windows: List<CropWindow>,
    val pages: List<PageTerms>,
    val weights: Weights,
    val totalInk: Long,
) {
    val k: Int get() = windows.size
    val switches: Int get() = pages.zipWithNext().count { (a, b) -> a.window != b.window }
    val breaks: Int get() = (2 until pages.size).count { pages[it].window != pages[it - 2].window }
    val cutCells: Long get() = pages.sumOf { it.cutCells.toLong() }
    val cutFraction: Double get() = if (totalInk == 0L) 0.0 else cutCells.toDouble() / totalInk
    val cutTerm: Double get() = weights.alpha * pages.sumOf { it.cut }
    val offTerm: Double get() = weights.beta * pages.sumOf { it.offCentre }
    val windowTerm: Double get() = weights.gamma * (k - 1)
    val switchTerm: Double get() = weights.delta * switches + weights.delta2 * breaks
    val total: Double get() = cutTerm + offTerm + windowTerm + switchTerm
}

/** The crop model: a fixed scale per paper, and a few shared windows that pages are assigned to. */
class CropModel(private val aspect: Float, private val weights: Weights = Weights()) {

    /**
     * Picks the scale, then the windows. The scale is the tightest at which the model's own crops
     * cut at most [CropAnalysis.AUTO_MAX_LOST] of the ink, loosened by [CropAnalysis.AUTO_MARGIN]:
     * the same rule as the reader's automatic zoom, but applied to the windows the pages will
     * actually get rather than to each page's own best window.
     */
    suspend fun crops(masks: List<InkMask>): Crops {
        val pages = masks.map { FramedPage(it, aspect) }
        val scales = CropAnalysis.defaultScales()
        // Each page's own best window loses least, so the reader's curve bounds where to start.
        val analysis = CropAnalysis.of(masks, aspect, scales)
        val totalInk = analysis.totalInk
        if (totalInk == 0L) return solve(pages, 1f)
        var i = scales.indices.lastOrNull { analysis.lostFraction(it) <= CropAnalysis.AUTO_MAX_LOST } ?: 0
        while (i > 0 && solve(pages, scales[i]).cutFraction > CropAnalysis.AUTO_MAX_LOST) i--
        val loosened = scales[i] + CropAnalysis.AUTO_MARGIN
        val chosen = scales.indices.firstOrNull { scales[it] <= loosened + 1e-6f } ?: 0
        return solve(pages, scales[chosen])
    }

    /** The cheapest crops at [scale], trying 1 to [Weights.maxWindows] windows. */
    fun solve(pages: List<FramedPage>, scale: Float): Crops = Solver(pages, scale).best()

    /** Scores given per-page windows (e.g. the reader's) with this model's cost. */
    fun score(masks: List<InkMask>, perPage: List<CropWindow>): Crops {
        require(masks.size == perPage.size)
        val pages = masks.map { FramedPage(it, aspect) }
        val distinct = perPage.distinct()
        val terms = pages.indices.map { p ->
            val (x, y) = pages[p].cellOf(perPage[p])
            pageTerms(pages, p, distinct.indexOf(perPage[p]), x, y, perPage[p].scale)
        }
        return Crops(perPage.first().scale, distinct, terms, weights, pages.sumOf { it.inkCount.toLong() })
    }

    private fun referenceInk(pages: List<FramedPage>): Double {
        val inked = pages.map { it.inkCount }.filter { it > 0 }.sorted()
        return if (inked.isEmpty()) 1.0 else inked[(inked.size - 1) / 2].toDouble()
    }

    private fun pageTerms(pages: List<FramedPage>, p: Int, window: Int, x: Int, y: Int, scale: Float): PageTerms {
        val page = pages[p]
        val w = page.windowWidth(scale)
        val h = page.windowHeight(scale)
        val cut = page.inkCount - page.inside(x, y, w, h)
        return PageTerms(window, cut, cut / referenceInk(pages), page.offCentre(x, y, w, h))
    }

    private inner class Solver(private val pages: List<FramedPage>, private val scale: Float) {
        private val refInk = referenceInk(pages)

        // Windows are positions on a reference frame (the most common page shape); other pages
        // take the same position as a fraction of their frame.
        private val ref = pages.groupingBy { it.width to it.height }.eachCount().maxBy { it.value }.key
        private val refW = ref.first
        private val refH = ref.second
        private val winW = (scale * refW).roundToInt().coerceIn(1, refW)
        private val winH = (scale * refH).roundToInt().coerceIn(1, refH)
        private val cols = refW - winW + 1
        private val rows = refH - winH + 1

        // cost[p][pos]: page p's cost with the window at reference position pos = y * cols + x.
        private val cost: Array<DoubleArray> = Array(pages.size) { p ->
            val page = pages[p]
            val w = page.windowWidth(scale)
            val h = page.windowHeight(scale)
            DoubleArray(cols * rows) { pos ->
                if (page.inkCount == 0) return@DoubleArray 0.0
                val (x, y) = mapped(page, pos)
                val cut = page.inkCount - page.inside(x, y, w, h)
                weights.alpha * cut / refInk + weights.beta * page.offCentre(x, y, w, h)
            }
        }

        private fun mapped(page: FramedPage, pos: Int): Pair<Int, Int> {
            val x = pos % cols
            val y = pos / cols
            if (page.width == refW && page.height == refH) return x to y
            return page.cellOf(windowAt(pos))
        }

        private fun windowAt(pos: Int): CropWindow {
            val left = ((pos % cols) + winW / 2f) / refW - scale / 2
            val top = ((pos / cols) + winH / 2f) / refH - scale / 2
            return CropWindow(left.coerceIn(0f, 1f - scale), top.coerceIn(0f, 1f - scale), scale)
        }

        /** The position minimising the summed cost of [members]. */
        private fun bestFor(members: List<Int>): Int {
            var best = 0
            var bestCost = Double.MAX_VALUE
            for (pos in 0 until cols * rows) {
                var c = 0.0
                for (p in members) c += cost[p][pos]
                if (c < bestCost - 1e-12) {
                    bestCost = c
                    best = pos
                }
            }
            return best
        }

        /** Each page's window for [windows], with the switch penalty if there is one. */
        private fun assign(windows: IntArray): IntArray {
            val n = pages.size
            val k = windows.size
            if (weights.delta2 > 0.0 && k > 1 && n > 2) return assignPairs(windows)
            if (weights.delta == 0.0 || k == 1) {
                val out = IntArray(n)
                for (p in 0 until n) {
                    // Ties (e.g. blank pages) keep the previous page's window.
                    val prev = if (p > 0) out[p - 1] else 0
                    var best = prev
                    for (j in 0 until k) if (cost[p][windows[j]] < cost[p][windows[best]] - 1e-12) best = j
                    out[p] = best
                }
                return out
            }
            // Viterbi over pages: states are windows, a change of window costs delta.
            val acc = Array(n) { DoubleArray(k) }
            val from = Array(n) { IntArray(k) }
            for (j in 0 until k) acc[0][j] = cost[0][windows[j]]
            for (p in 1 until n) for (j in 0 until k) {
                var bestPrev = j
                for (i in 0 until k) {
                    val c = acc[p - 1][i] + if (i == j) 0.0 else weights.delta
                    if (c < acc[p - 1][bestPrev] + (if (bestPrev == j) 0.0 else weights.delta) - 1e-12) bestPrev = i
                }
                from[p][j] = bestPrev
                acc[p][j] = acc[p - 1][bestPrev] + (if (bestPrev == j) 0.0 else weights.delta) + cost[p][windows[j]]
            }
            val out = IntArray(n)
            out[n - 1] = (0 until k).minBy { acc[n - 1][it] }
            for (p in n - 1 downTo 1) out[p - 1] = from[p][out[p]]
            return out
        }

        /**
         * Viterbi over pairs of consecutive windows, so a page's window can be compared with the
         * one two pages back: states are (previous page's window, this page's window).
         */
        private fun assignPairs(windows: IntArray): IntArray {
            val n = pages.size
            val k = windows.size
            fun step(a: Int, b: Int) = if (a == b) 0.0 else weights.delta
            fun state(a: Int, b: Int) = a * k + b
            val acc = Array(n) { DoubleArray(k * k) { Double.MAX_VALUE } }
            val from = Array(n) { IntArray(k * k) }
            for (a in 0 until k) for (b in 0 until k) {
                acc[1][state(a, b)] = cost[0][windows[a]] + cost[1][windows[b]] + step(a, b)
            }
            for (p in 2 until n) for (b in 0 until k) for (c in 0 until k) {
                var best = Double.MAX_VALUE
                var arg = 0
                for (a in 0 until k) {
                    val v = acc[p - 1][state(a, b)] + if (a == c) 0.0 else weights.delta2
                    if (v < best - 1e-12) {
                        best = v
                        arg = a
                    }
                }
                acc[p][state(b, c)] = best + step(b, c) + cost[p][windows[c]]
                from[p][state(b, c)] = arg
            }
            val out = IntArray(n)
            val last = (0 until k * k).minBy { acc[n - 1][it] }
            out[n - 2] = last / k
            out[n - 1] = last % k
            for (p in n - 1 downTo 2) out[p - 2] = from[p][state(out[p - 1], out[p])]
            return out
        }

        private fun total(windows: IntArray, assignment: IntArray): Double {
            var c = weights.gamma * (windows.size - 1)
            for (p in pages.indices) {
                c += cost[p][windows[assignment[p]]]
                if (p > 0 && assignment[p] != assignment[p - 1]) c += weights.delta
                if (p > 1 && assignment[p] != assignment[p - 2]) c += weights.delta2
            }
            return c
        }

        /** Alternates assigning pages and re-placing each window for its pages, until stable. */
        private fun refine(start: IntArray): Pair<IntArray, IntArray> {
            var windows = start.copyOf()
            var assignment = assign(windows)
            repeat(MAX_ITERATIONS) {
                val used = windows.indices.filter { j -> assignment.any { it == j } }
                val next = used.map { j -> bestFor(pages.indices.filter { assignment[it] == j }) }.toIntArray()
                val nextAssignment = assign(next)
                if (next.contentEquals(windows) && nextAssignment.contentEquals(assignment)) return windows to assignment
                if (total(next, nextAssignment) > total(windows, assignment) - 1e-12 && next.size == windows.size) {
                    return windows to assignment
                }
                windows = next
                assignment = nextAssignment
            }
            return windows to assignment
        }

        fun best(): Crops {
            val all = pages.indices.toList()
            var bestWindows = intArrayOf(bestFor(all))
            var bestAssignment = assign(bestWindows)
            var bestCost = total(bestWindows, bestAssignment)
            var previous = bestWindows
            // Each page's own best position seeds candidates for an extra window.
            val seeds = pages.indices.map { bestFor(listOf(it)) }.distinct()
            for (k in 2..weights.maxWindows) {
                // Refining is the expensive part: only from the seeds that help most at once.
                val starts = seeds.map { previous + it }
                    .sortedBy { total(it, assign(it)) }
                    .take(MAX_SEEDS)
                    .toMutableList()
                if (k == 2) {
                    // Two-sided layouts: odd and even pages.
                    starts += intArrayOf(bestFor(all.filter { it % 2 == 0 }), bestFor(all.filter { it % 2 == 1 }))
                }
                val candidates = starts.map { refine(it) }
                val (windows, assignment) = candidates.minBy { total(it.first, it.second) }
                previous = windows
                val c = total(windows, assignment)
                if (c < bestCost - 1e-12) {
                    bestCost = c
                    bestWindows = windows
                    bestAssignment = assignment
                }
            }
            // Number windows in order of first use.
            val order = bestAssignment.distinct()
            val windows = order.map { windowAt(bestWindows[it]) }
            val terms = pages.indices.map { p ->
                val page = pages[p]
                val window = windowAt(bestWindows[bestAssignment[p]])
                val (x, y) = page.cellOf(window)
                pageTerms(pages, p, order.indexOf(bestAssignment[p]), x, y, scale)
            }
            return Crops(scale, windows, terms, weights, pages.sumOf { it.inkCount.toLong() })
        }
    }

    private companion object {
        const val MAX_ITERATIONS = 20
        const val MAX_SEEDS = 6
    }
}
