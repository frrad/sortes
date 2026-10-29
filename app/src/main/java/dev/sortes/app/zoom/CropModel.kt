package dev.sortes.app.zoom

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max

/**
 * Weights of the crop model's cost. The cost of giving each page p one of K windows is
 *
 *     Σ_p [ alpha·cut_p + beta·off_p + epsilon·max(0, pageCut_p − allowance)² ]
 *         + gamma·(K − 1) + delta·switches + delta2·breaks
 *
 * See tools/zoom-eval/SPEC.md for each term, and tools/zoom-eval/README.md for how to tune these
 * on the review board. The defaults here are what the reader uses.
 */
data class CropModelWeights(
    /** Per page of ink cut off: cut_p is cut cells / the paper's median ink per page. */
    val alpha: Double = 1.0,
    /** Per unit of off_p: the kept ink's offset from the window centre, in window widths + heights. */
    val beta: Double = 0.1,
    /**
     * On the square of each page's own cut fraction (pageCut_p, cut cells / that page's ink) above
     * [allowance]: lets a page that would lose a lot earn a window of its own.
     */
    val epsilon: Double = 300.0,
    /** Share of a page's own ink it may lose before [epsilon] applies. */
    val allowance: Double = 0.01,
    /** Per window beyond the first. */
    val gamma: Double = 0.05,
    /** Per page turn that changes window. */
    val delta: Double = 0.0,
    /**
     * Per page whose window differs from the one two pages back: penalises jitter and one-off
     * windows but not a steady left/right alternation.
     */
    val delta2: Double = 0.02,
    /** Most windows a paper may use. */
    val maxWindows: Int = 3,
) {
    init {
        require(listOf(alpha, beta, epsilon, allowance, gamma, delta, delta2).all { it >= 0.0 && it.isFinite() }) {
            "Weights must be finite and non-negative: $this"
        }
        require(maxWindows >= 1) { "maxWindows must be at least 1: $maxWindows" }
    }
}


/** One page's crop and its share of the cost. */
data class PageCrop(
    /** Index into [ModelCrops.windows]. */
    val windowIndex: Int,
    /**
     * The window as this page renders it: [ModelCrops.windows]' entry, centred exactly on [cells]
     * of this page's mask (pages of other sizes can differ from the shared entry by a fraction of a
     * cell).
     */
    val window: CropWindow,
    /** The mask cells the window covers, as the model counted them. */
    val cells: CellRect,
    val cutCells: Int,
    /** cut_p: cut cells / the paper's median ink per page. */
    val cut: Double,
    /** pageCut_p: cut cells / this page's ink (0 for a blank page). */
    val pageCut: Double,
    val offCentre: Double,
)

/** A paper's crops: one scale, a few windows, and each analysed page's window and costs. */
data class ModelCrops(
    val scale: Float,
    val windows: List<CropWindow>,
    val pages: List<PageCrop>,
    val weights: CropModelWeights,
    val totalInk: Long,
) {
    val k: Int get() = windows.size
    val switches: Int get() = pages.zipWithNext().count { (a, b) -> a.windowIndex != b.windowIndex }
    val breaks: Int get() = (2 until pages.size).count { pages[it].windowIndex != pages[it - 2].windowIndex }
    val cutCells: Long get() = pages.sumOf { it.cutCells.toLong() }

    /** Share of the paper's ink the crops cut off; 0 for a blank paper. */
    val cutFraction: Double get() = if (totalInk == 0L) 0.0 else cutCells.toDouble() / totalInk

    /** Pages that lose any ink. */
    val pagesCut: Int get() = pages.count { it.cutCells > 0 }

    val cutTerm: Double get() = weights.alpha * pages.sumOf { it.cut }
    val offTerm: Double get() = weights.beta * pages.sumOf { it.offCentre }
    val pageCutTerm: Double get() = pages.sumOf { weights.excess(it.pageCut) }
    val windowTerm: Double get() = weights.gamma * (k - 1)
    val switchTerm: Double get() = weights.delta * switches + weights.delta2 * breaks
    val total: Double get() = cutTerm + offTerm + pageCutTerm + windowTerm + switchTerm
}

private fun CropModelWeights.excess(pageCut: Double): Double {
    val over = pageCut - allowance
    return if (over > 0) epsilon * over * over else 0.0
}

/**
 * The crop model: one scale for the whole paper and a few windows shared by its pages, chosen to
 * keep ink, centre what's kept, and stay put from page to page. See tools/zoom-eval/SPEC.md.
 */
class CropModel(val aspect: Float, val weights: CropModelWeights = CropModelWeights()) {
    init {
        require(aspect > 0f && aspect.isFinite()) { "Bad aspect ratio: $aspect" }
    }

    /**
     * Crops at the automatic scale: the tightest of [scales] at which the model's own crops cut at
     * most [CropAnalysis.AUTO_MAX_LOST] of the ink, loosened by [CropAnalysis.AUTO_MARGIN].
     *
     * Each page's own best window loses least, so the model can't go tighter than the tightest
     * scale at which those best windows are within budget; that bound is found by bisection,
     * since what they lose only grows with zoom. From there the search gallops looser (1, 2, 4...
     * steps) until the model's crops are within budget, then bisects back, treating the model's
     * cut as rising with zoom too.
     */
    suspend fun auto(masks: List<InkMask>, scales: FloatArray = CropAnalysis.defaultScales()): ModelCrops {
        require(masks.isNotEmpty()) { "No pages" }
        require(scales.isNotEmpty() && scales.all { it > 0f && it <= 1f }) { "Bad scales" }
        val pages = masks.map { PaddedPage(it, aspect) }
        val totalInk = pages.sumOf { it.inkCount.toLong() }
        val buffer = Buffer()
        if (totalInk == 0L) return solve(pages, scales[0], buffer)
        fun fits(crops: ModelCrops) = crops.cutFraction <= CropAnalysis.AUTO_MAX_LOST
        suspend fun fitsAt(i: Int) = fits(solve(pages, scales[i], buffer))
        suspend fun boundFits(i: Int): Boolean {
            var lost = 0L
            for (page in pages) {
                currentCoroutineContext().ensureActive()
                lost += page.inkCount - page.maxInside(scales[i])
            }
            return lost.toDouble() / totalInk <= CropAnalysis.AUTO_MAX_LOST
        }

        // The bound: the tightest scale at which each page's own best window is within budget.
        var bound = 0
        var beyond = scales.size
        while (beyond - bound > 1) {
            val mid = (bound + beyond) / 2
            if (boundFits(mid)) bound = mid else beyond = mid
        }
        // Index of the tightest scale known to fit (0, the whole page, always does), and of the
        // loosest known not to, if any.
        var fit = bound
        var miss = -1
        var step = 1
        while (fit > 0 && !fitsAt(fit)) {
            miss = fit
            fit = (fit - step).coerceAtLeast(0)
            step *= 2
        }
        if (miss >= 0) {
            while (miss - fit > 1) {
                val mid = (fit + miss) / 2
                if (fitsAt(mid)) fit = mid else miss = mid
            }
        }
        val loosened = scales[fit] + CropAnalysis.AUTO_MARGIN
        val chosen = scales.indices.firstOrNull { scales[it] <= loosened + 1e-6f } ?: 0
        return solve(pages, scales[chosen], buffer)
    }

    /** The cheapest crops of [masks] at [scale], with 1 to [CropModelWeights.maxWindows] windows. */
    suspend fun solve(masks: List<InkMask>, scale: Float): ModelCrops {
        require(masks.isNotEmpty()) { "No pages" }
        return solve(masks.map { PaddedPage(it, aspect) }, scale, Buffer())
    }

    private suspend fun solve(pages: List<PaddedPage>, scale: Float, buffer: Buffer): ModelCrops =
        solverFor(pages, scale, buffer).best()

    private suspend fun solverFor(pages: List<PaddedPage>, scale: Float, buffer: Buffer): Solver {
        require(scale > 0f && scale <= 1f) { "Bad scale: $scale" }
        return Solver(pages, scale, buffer).apply { precompute() }
    }

    /** The solver for [masks] at [scale], for tests of its steps. */
    internal suspend fun solver(masks: List<InkMask>, scale: Float): Solver =
        solverFor(masks.map { PaddedPage(it, aspect) }, scale, Buffer())

    /** Scores given per-page windows (e.g. another method's) with this model's cost. */
    fun score(masks: List<InkMask>, perPage: List<CropWindow>): ModelCrops {
        require(masks.isNotEmpty() && masks.size == perPage.size) { "Need one window per page" }
        val pages = masks.map { PaddedPage(it, aspect) }
        val refInk = referenceInkOf(pages.map { it.inkCount })
        val distinct = perPage.distinct()
        val terms = pages.indices.map { p ->
            val (x, y) = pages[p].positionOf(perPage[p])
            pageCrop(pages[p], refInk, distinct.indexOf(perPage[p]), x, y, perPage[p].scale)
        }
        return ModelCrops(perPage.first().scale, distinct, terms, weights, pages.sumOf { it.inkCount.toLong() })
    }

    /**
     * Which of [crops]' windows suits a page that wasn't analysed with the others, and its crop:
     * the window that costs it least, preferring [preferred] (e.g. the window analysed pages of
     * the same parity use) on a tie.
     */
    fun assign(mask: InkMask, crops: ModelCrops, referenceInk: Double, preferred: Int = 0): PageCrop {
        val page = PaddedPage(mask, aspect)
        val options = crops.windows.indices.map { j ->
            val (x, y) = page.positionOf(crops.windows[j])
            pageCrop(page, referenceInk, j, x, y, crops.scale)
        }
        fun cost(c: PageCrop) = weights.alpha * c.cut + weights.beta * c.offCentre + weights.excess(c.pageCut)
        return options.minWith(compareBy<PageCrop> { cost(it) }.thenBy { if (it.windowIndex == preferred) 0 else 1 })
    }

    private fun pageCrop(page: PaddedPage, refInk: Double, window: Int, x: Int, y: Int, scale: Float): PageCrop {
        val w = page.windowWidth(scale)
        val h = page.windowHeight(scale)
        val cut = page.inkCount - page.inside(x, y, w, h)
        return PageCrop(
            windowIndex = window,
            window = page.windowAt(x, y, scale),
            cells = page.cellsAt(x, y, scale),
            cutCells = cut,
            cut = cut / refInk,
            pageCut = if (page.inkCount == 0) 0.0 else cut.toDouble() / page.inkCount,
            offCentre = page.offCentre(x, y, w, h),
        )
    }

    /** Scratch space reused across solves at several scales, so they don't each allocate it. */
    internal class Buffer {
        private var array = DoubleArray(0)

        fun get(size: Int): DoubleArray {
            if (array.size < size) array = DoubleArray(size)
            return array
        }
    }

    internal inner class Solver(private val pages: List<PaddedPage>, private val scale: Float, buffer: Buffer) {
        private val refInk = referenceInkOf(pages.map { it.inkCount })

        // Windows are positions on a reference page (the most common mask size); other pages take
        // the same window as a fraction of their frame, so it renders where it does on the
        // reference.
        private val refSize = pages.groupingBy { it.maskWidth to it.maskHeight }.eachCount().maxBy { it.value }.key
        private val refPage = pages.first { (it.maskWidth to it.maskHeight) == refSize }
        private val cols = refPage.width - refPage.windowWidth(scale) + 1
        private val rows = refPage.height - refPage.windowHeight(scale) + 1

        /** Number of window positions on the reference page. */
        val positions = cols * rows

        // cost[p * positions + pos]: page p's cost with the window at reference position
        // pos = y * cols + x.
        private val cost = buffer.get(pages.size * positions)
        private val sums = DoubleArray(positions)

        suspend fun precompute() {
            var offCentres = DoubleArray(0)
            for (p in pages.indices) {
                currentCoroutineContext().ensureActive()
                val page = pages[p]
                val row = p * positions
                if (page.inkCount == 0) {
                    cost.fill(0.0, row, row + positions)
                    continue
                }
                val w = page.windowWidth(scale)
                val h = page.windowHeight(scale)
                val pageCols = page.width - w + 1
                val size = pageCols * (page.height - h + 1)
                if (offCentres.size < size) offCentres = DoubleArray(size)
                page.offCentres(w, h, offCentres)
                val reference = (page.maskWidth to page.maskHeight) == refSize
                for (pos in 0 until positions) {
                    val x: Int
                    val y: Int
                    if (reference) {
                        x = pos % cols
                        y = pos / cols
                    } else {
                        val xy = page.positionOf(windowAt(pos))
                        x = xy.first
                        y = xy.second
                    }
                    val cut = page.inkCount - page.inside(x, y, w, h)
                    cost[row + pos] = weights.alpha * cut / refInk +
                        weights.beta * offCentres[y * pageCols + x] +
                        weights.excess(cut.toDouble() / page.inkCount)
                }
            }
        }

        private fun cost(p: Int, pos: Int) = cost[p * positions + pos]

        private fun mapped(page: PaddedPage, pos: Int): Pair<Int, Int> =
            if ((page.maskWidth to page.maskHeight) == refSize) pos % cols to pos / cols
            else page.positionOf(windowAt(pos))

        private fun windowAt(pos: Int) = refPage.windowAt(pos % cols, pos / cols, scale)

        /** The position minimising the summed cost of [members]; on a tie, the most central. */
        fun bestFor(members: List<Int>): Int {
            sums.fill(0.0)
            for (p in members) {
                val row = p * positions
                for (pos in 0 until positions) sums[pos] += cost[row + pos]
            }
            val cx = (cols - 1) / 2.0
            val cy = (rows - 1) / 2.0
            fun offCentre(pos: Int): Double {
                val dx = pos % cols - cx
                val dy = pos / cols - cy
                return dx * dx + dy * dy
            }
            var best = 0
            for (pos in 1 until positions) {
                val d = sums[pos] - sums[best]
                if (d < -EPS || (d <= EPS && offCentre(pos) < offCentre(best))) best = pos
            }
            return best
        }

        /**
         * Each page's window among [windows] (positions), minimising [pathCost]: each page's cost
         * plus the switch and break penalties. With no penalties, ties keep the previous page's
         * window.
         */
        fun assign(windows: IntArray): IntArray {
            val n = pages.size
            val k = windows.size
            if (k == 1) return IntArray(n)
            if (weights.delta2 > 0.0 && n > 2) return assignPairs(windows)
            if (weights.delta > 0.0) return assignSingles(windows)
            val out = IntArray(n)
            for (p in 0 until n) {
                val prev = if (p > 0) out[p - 1] else 0
                var best = prev
                for (j in 0 until k) if (cost(p, windows[j]) < cost(p, windows[best]) - EPS) best = j
                out[p] = best
            }
            return out
        }

        /** Viterbi over pages, a change of window costing delta. */
        private fun assignSingles(windows: IntArray): IntArray {
            val n = pages.size
            val k = windows.size
            val acc = Array(n) { DoubleArray(k) }
            val from = Array(n) { IntArray(k) }
            for (j in 0 until k) acc[0][j] = cost(0, windows[j])
            for (p in 1 until n) for (j in 0 until k) {
                // Staying on the same window wins ties.
                var best = acc[p - 1][j]
                from[p][j] = j
                for (i in 0 until k) {
                    val c = acc[p - 1][i] + weights.delta
                    if (i != j && c < best - EPS) {
                        best = c
                        from[p][j] = i
                    }
                }
                acc[p][j] = best + cost(p, windows[j])
            }
            val out = IntArray(n)
            out[n - 1] = (0 until k).minBy { acc[n - 1][it] }
            for (p in n - 1 downTo 1) out[p - 1] = from[p][out[p]]
            return out
        }

        /**
         * Viterbi over pairs of consecutive windows, so a page's window can be compared with the
         * one two pages back: states are (previous page's window, this page's window). Ties go to
         * the window two pages back, continuing the pattern.
         */
        private fun assignPairs(windows: IntArray): IntArray {
            val n = pages.size
            val k = windows.size
            fun step(a: Int, b: Int) = if (a == b) 0.0 else weights.delta
            fun state(a: Int, b: Int) = a * k + b
            val acc = Array(n) { DoubleArray(k * k) { Double.MAX_VALUE } }
            val from = Array(n) { IntArray(k * k) }
            for (a in 0 until k) for (b in 0 until k) {
                acc[1][state(a, b)] = cost(0, windows[a]) + cost(1, windows[b]) + step(a, b)
            }
            for (p in 2 until n) for (b in 0 until k) for (c in 0 until k) {
                var best = acc[p - 1][state(c, b)]
                var arg = c
                for (a in 0 until k) {
                    val v = acc[p - 1][state(a, b)] + if (a == c) 0.0 else weights.delta2
                    if (a != c && v < best - EPS) {
                        best = v
                        arg = a
                    }
                }
                acc[p][state(b, c)] = best + step(b, c) + cost(p, windows[c])
                from[p][state(b, c)] = arg
            }
            val out = IntArray(n)
            val last = (0 until k * k).minBy { acc[n - 1][it] }
            out[n - 2] = last / k
            out[n - 1] = last % k
            for (p in n - 1 downTo 2) out[p - 2] = from[p][state(out[p - 1], out[p])]
            return out
        }

        /** Every page's cost in its window plus the switch and break penalties: [total] without γ. */
        fun pathCost(windows: IntArray, assignment: IntArray): Double {
            var c = 0.0
            for (p in pages.indices) {
                c += cost(p, windows[assignment[p]])
                if (p > 0 && assignment[p] != assignment[p - 1]) c += weights.delta
                if (p > 1 && assignment[p] != assignment[p - 2]) c += weights.delta2
            }
            return c
        }

        fun total(windows: IntArray, assignment: IntArray): Double =
            weights.gamma * (assignment.distinct().size - 1) + pathCost(windows, assignment)

        /**
         * Alternates re-placing each window for its pages and re-assigning pages, keeping only
         * rounds that lower the total, so the result never costs more than [start].
         */
        suspend fun refine(start: IntArray): Pair<IntArray, IntArray> {
            var windows = start.copyOf()
            var assignment = assign(windows)
            var current = total(windows, assignment)
            repeat(MAX_ROUNDS) {
                currentCoroutineContext().ensureActive()
                val used = windows.indices.filter { j -> assignment.any { it == j } }
                val next = used.map { j -> bestFor(pages.indices.filter { assignment[it] == j }) }.toIntArray()
                val nextAssignment = assign(next)
                val cost = total(next, nextAssignment)
                if (cost >= current - EPS) return windows to assignment
                windows = next
                assignment = nextAssignment
                current = cost
            }
            return windows to assignment
        }

        suspend fun best(): ModelCrops {
            val all = pages.indices.toList()
            var bestWindows = intArrayOf(bestFor(all))
            var bestAssignment = IntArray(pages.size)
            var bestCost = total(bestWindows, bestAssignment)
            var previous = bestWindows
            // Each page's own best position seeds candidates for an extra window.
            val seeds = if (weights.maxWindows > 1) pages.indices.map { bestFor(listOf(it)) }.distinct() else emptyList()
            for (k in 2..weights.maxWindows) {
                // Refining is the expensive part: only from the seeds that help most at once.
                val starts = seeds.map { previous + it }
                    .sortedBy { total(it, assign(it)) }
                    .take(MAX_SEEDS)
                    .toMutableList()
                if (k == 2 && pages.size > 1) {
                    // Two-sided layouts: odd and even pages.
                    starts += intArrayOf(bestFor(all.filter { it % 2 == 0 }), bestFor(all.filter { it % 2 == 1 }))
                }
                if (starts.isEmpty()) break
                val (windows, assignment) = starts.map { refine(it) }.minBy { total(it.first, it.second) }
                previous = windows
                val c = total(windows, assignment)
                if (c < bestCost - EPS) {
                    bestCost = c
                    bestWindows = windows
                    bestAssignment = assignment
                }
            }
            // Number windows in order of first use, dropping unused ones.
            val order = bestAssignment.distinct()
            val windows = order.map { windowAt(bestWindows[it]) }
            val crops = pages.indices.map { p ->
                val page = pages[p]
                val (x, y) = mapped(page, bestWindows[bestAssignment[p]])
                pageCrop(page, refInk, order.indexOf(bestAssignment[p]), x, y, scale)
            }
            return ModelCrops(scale, windows, crops, weights, pages.sumOf { it.inkCount.toLong() })
        }
    }

    companion object {
        private const val MAX_ROUNDS = 20
        private const val MAX_SEEDS = 6
        private const val EPS = 1e-12

        /** The median ink per page over pages with any ink: the unit cut_p is measured in. */
        fun referenceInk(masks: List<InkMask>): Double = referenceInkOf(masks.map { it.inkCount })

        private fun referenceInkOf(inks: List<Int>): Double {
            val inked = inks.filter { it > 0 }.sorted()
            return if (inked.isEmpty()) 1.0 else max(1, inked[(inked.size - 1) / 2]).toDouble()
        }
    }
}
