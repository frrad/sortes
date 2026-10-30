package dev.sortes.app.zoom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Weights of the crop model's cost. The cost of giving each page p one of K windows is
 *
 *     Σ_p [ alpha·cut_p + beta·off_p + epsilon·max(0, pageCut_p − allowance)² + kappa·split_p ]
 *
 * where split_p sums, over the blocks the window cuts through, the ink cut off each as a share of
 * [splitInk] of the paper's median page, capped at 1 per block,
 *         + gamma·(K − 1) + delta·switches + delta2·breaks
 *
 * and, when [zeta] is set, the automatic zoom minimises that cost per page plus zeta·scale.
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
    /**
     * Per block a window's edge cuts through (one the window overlaps but doesn't contain), in
     * full once the ink cut off the block reaches [splitInk]. A whole block kept or dropped costs
     * nothing here, only its ink; a figure, table or paragraph cut through costs this on top,
     * and a nibble off a block's edge a share of it. 0 turns blocks off.
     */
    val kappa: Double = 0.0,
    /** Blank cells that may separate the ink of one block ([PageBlocks]). */
    val blockGap: Int = 2,
    /**
     * The ink cut off one block at which the cut costs the whole [kappa], as a share of the
     * paper's median ink per page: 0.005 is about a quarter of a text line.
     */
    val splitInk: Double = 0.005,
    /**
     * The automatic zoom's legibility reward: when positive, the zoom is the scale minimising the
     * cost per page plus zeta·scale, instead of the tightest scale within the cut budget
     * ([autoMaxLost]). A tighter zoom then pays for itself in cut and split blocks.
     */
    val zeta: Double = 0.0,
    /** Share of the paper's ink the automatic zoom may cut off. */
    val autoMaxLost: Double = CropAnalysis.AUTO_MAX_LOST.toDouble(),
    /** How much looser (in scale) than the tightest acceptable scale the automatic zoom is. */
    val autoMargin: Double = CropAnalysis.AUTO_MARGIN.toDouble(),
    /**
     * Pages the automatic zoom's budget leaves out: the ones that would lose most. A title page
     * that can't fit at a good zoom for the rest of the paper then doesn't hold the paper back.
     */
    val zoomIgnoredPages: Int = 0,
    /**
     * Most of its own ink any one page may lose at the automatic zoom, besides the paper-wide
     * [autoMaxLost]; the [zoomIgnoredPages] pages that would lose the largest share are exempt.
     * 1 (the default) means no cap.
     */
    val autoMaxPageCut: Double = 1.0,
) {
    init {
        require(listOf(alpha, beta, epsilon, allowance, gamma, delta, delta2, kappa, zeta).all { it >= 0.0 && it.isFinite() }) {
            "Weights must be finite and non-negative: $this"
        }
        require(maxWindows >= 1) { "maxWindows must be at least 1: $maxWindows" }
        require(blockGap >= 0) { "blockGap must not be negative: $blockGap" }
        require(splitInk > 0.0 && splitInk.isFinite()) { "Bad splitInk: $splitInk" }
        require(autoMaxLost > 0.0 && autoMaxLost < 1.0) { "Bad autoMaxLost: $autoMaxLost" }
        require(autoMargin in -0.5..0.5) { "Bad autoMargin: $autoMargin" }
        require(zoomIgnoredPages >= 0) { "Bad zoomIgnoredPages: $zoomIgnoredPages" }
        require(autoMaxPageCut > 0.0 && autoMaxPageCut <= 1.0) { "Bad autoMaxPageCut: $autoMaxPageCut" }
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
    /** Blocks the window cuts into, some of their ink kept and some cut (0 unless [CropModelWeights.kappa] is set). */
    val splitBlocks: Int = 0,
    /** split_p: those blocks' cut shares, each capped at 1 (0 unless [CropModelWeights.kappa] is set). */
    val split: Double = 0.0,
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
    val splitTerm: Double get() = weights.kappa * pages.sumOf { it.split }
    val windowTerm: Double get() = weights.gamma * (k - 1)
    val switchTerm: Double get() = weights.delta * switches + weights.delta2 * breaks
    val total: Double get() = cutTerm + offTerm + pageCutTerm + splitTerm + windowTerm + switchTerm

    /** Blocks cut through, over all pages. */
    val splitBlocks: Int get() = pages.sumOf { it.splitBlocks }

    /** Pages with a block cut through. */
    val pagesSplit: Int get() = pages.count { it.splitBlocks > 0 }

    /** What the automatic zoom minimises when [CropModelWeights.zeta] is set: cost per page plus zeta·scale. */
    val zoomObjective: Double get() = total / pages.size + weights.zeta * scale
}

private fun CropModelWeights.excess(pageCut: Double): Double {
    val over = pageCut - allowance
    return if (over > 0) epsilon * over * over else 0.0
}

/**
 * The crop model: one scale for the whole paper and a few windows shared by its pages, chosen to
 * keep ink, centre what's kept, and stay put from page to page. See tools/zoom-eval/SPEC.md.
 */
class CropModel(
    val aspect: Float,
    val weights: CropModelWeights = CropModelWeights(),
    /**
     * How many threads a solve may spread its pages over, on [Dispatchers.Default]: each page's
     * costs are computed on their own and written to their own part of the table, so the result
     * is the same as on one thread.
     */
    val parallelism: Int = DEFAULT_PARALLELISM,
) {
    init {
        require(aspect > 0f && aspect.isFinite()) { "Bad aspect ratio: $aspect" }
        require(parallelism >= 1) { "parallelism must be at least 1: $parallelism" }
    }

    /**
     * Threads to spread [pages] pages of [cells] frame cells in all over: one for a paper too
     * small for the threads to pay for themselves (under [MIN_PARALLEL_CELLS]: a 15-page paper
     * lost a few milliseconds to them, a 45-page one gained a quarter), else [parallelism], but
     * never more than the pages.
     */
    internal fun threadsFor(pages: Int, cells: Long): Int =
        if (cells < MIN_PARALLEL_CELLS) 1 else parallelism.coerceAtMost(pages).coerceAtLeast(1)

    /**
     * [masks] ready to solve at any scale: their padded pages and the solver's scratch space are
     * kept, so repeated solves (a curve, manual zooms) don't rebuild them. Not thread-safe:
     * callers serialise solves.
     */
    inner class Prepared internal constructor(masks: List<InkMask>) {
        init {
            require(masks.isNotEmpty()) { "No pages" }
        }

        private val pages: List<PaddedPage> = runBlockingPrepare(masks)

        /**
         * The pages padded and, with blocks on, segmented, up to [parallelism] at a time on
         * [Dispatchers.Default]: each page on its own, so the result is the same in any order.
         */
        private fun runBlockingPrepare(masks: List<InkMask>): List<PaddedPage> {
            val threads = threadsFor(masks.size, masks.sumOf { PaddedPage.frameCells(it, aspect) })
            if (threads == 1) return masks.map { PaddedPage(it, aspect).also { p -> if (weights.kappa > 0.0) p.blocks(weights.blockGap) } }
            return kotlinx.coroutines.runBlocking {
                masks.chunked((masks.size + threads - 1) / threads).map { chunk ->
                    async(Dispatchers.Default) {
                        chunk.map { PaddedPage(it, aspect).also { p -> if (weights.kappa > 0.0) p.blocks(weights.blockGap) } }
                    }
                }.awaitAll().flatten()
            }
        }

        private val totalInk = pages.sumOf { it.inkCount.toLong() }
        private val buffer = Buffer()

        /** How many scales have been solved, for measuring the zoom search. */
        val solves: Int get() = solveCount.get()
        private val solveCount = AtomicInteger()

        /** Nanoseconds spent solving, summed over the threads that took part, for measuring. */
        val solveNanos: Long get() = solvedNanos.get()
        private val solvedNanos = AtomicLong()

        /** The cheapest crops at [scale], with 1 to [CropModelWeights.maxWindows] windows. */
        suspend fun solve(scale: Float): ModelCrops {
            val started = System.nanoTime()
            solveCount.incrementAndGet()
            val solver = solverFor(pages, scale, buffer)
            val crops = solver.best()
            solvedNanos.addAndGet(System.nanoTime() - started + solver.extraNanos)
            return crops
        }

        /** Solves [scales]' entries at [indices] that aren't in [solved] yet, into [solved]. */
        internal suspend fun solveAll(scales: FloatArray, indices: List<Int>, solved: MutableMap<Int, ModelCrops>) {
            for (i in indices.distinct()) if (i in scales.indices && i !in solved) solved[i] = solve(scales[i])
        }

        /** Whether pages losing [lost] cells each (in page order) are within the automatic zoom's budget. */
        private fun withinBudget(lost: List<Long>): Boolean {
            val counted = lost.sortedDescending().drop(weights.zoomIgnoredPages).sum()
            if (counted.toDouble() / totalInk > weights.autoMaxLost) return false
            if (weights.autoMaxPageCut >= 1.0) return true
            val shares = lost.indices.map { if (pages[it].inkCount == 0) 0.0 else lost[it].toDouble() / pages[it].inkCount }
            return shares.sortedDescending().drop(weights.zoomIgnoredPages).all { it <= weights.autoMaxPageCut }
        }

        /**
         * The index into [scales] of the tightest scale at which each page's own best window is
         * within the automatic zoom's budget ([CropModelWeights.autoMaxLost]): no crops the model
         * chooses can do better. Found by bisection, since what those best windows lose only
         * grows with zoom; with the default weights it agrees with [CropAnalysis]' whole curve.
         */
        internal suspend fun bound(scales: FloatArray): Int {
            if (totalInk == 0L) return 0
            suspend fun fits(i: Int): Boolean = withinBudget(pages.map { page ->
                currentCoroutineContext().ensureActive()
                (page.inkCount - page.maxInside(scales[i])).toLong()
            })
            var bound = 0
            var beyond = scales.size
            while (beyond - bound > 1) {
                val mid = (bound + beyond) / 2
                if (fits(mid)) bound = mid else beyond = mid
            }
            return bound
        }

        /**
         * Crops at the automatic scale: the tightest of [scales] at which the model's own crops
         * are within the budget (at most [CropModelWeights.autoMaxLost] of the ink, not counting
         * the [CropModelWeights.zoomIgnoredPages] pages that lose most), loosened by
         * [CropModelWeights.autoMargin]. The search starts at the [bound], gallops looser (1, 2, 4...
         * steps) until the model's crops are within budget, then bisects back, treating the
         * model's cut as rising with zoom.
         */
        suspend fun auto(scales: FloatArray = CropAnalysis.defaultScales()): ModelCrops {
            require(scales.isNotEmpty() && scales.all { it > 0f && it <= 1f }) { "Bad scales" }
            if (totalInk == 0L) return solve(scales[0])
            if (weights.zeta > 0.0) return autoByCost(scales)
            suspend fun fitsAt(i: Int) = withinBudget(solve(scales[i]).pages.map { it.cutCells.toLong() })
            // Index of the tightest scale known to fit (0, the whole page, always does), and of
            // the loosest known not to, if any.
            var fit = bound(scales)
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
            // In Float, as CropAnalysis does, so the default margin picks exactly the same scale.
            val loosened = scales[fit] + weights.autoMargin.toFloat()
            val chosen = scales.indices.firstOrNull { scales[it] <= loosened + 1e-6f }
                ?: if (loosened > scales[0]) 0 else scales.lastIndex
            return solve(scales[chosen])
        }
    }

    /** [masks] prepared for solving at several scales. */
    fun prepare(masks: List<InkMask>): Prepared = Prepared(masks)

    /**
     * Crops at the scale minimising [ModelCrops.zoomObjective] over [scales] (loosest first),
     * loosened by [CropModelWeights.autoMargin]. The objective falls as the zoom tightens while
     * only margins go, then climbs once windows cut and split the pages' blocks, with a step
     * wherever a page's blocks start to fit. So: every [COARSE_STEP]th scale (0.02) from
     * [GRID_TIGHTER] steps (0.02) tighter than the cut budget's [Prepared.bound] down to the
     * bound, then looser in the same steps for as long as the objective improves, allowing
     * [LOOKAHEAD] worse step (a page's blocks that fit a step later), then the scales 0.01
     * either side of the best, then 0.005 either side of the best of those. Seven or eight
     * solves; on the corpus the optimum is within 0.02 of the bound, and the refinement can
     * still reach 0.03 tighter.
     */
    private suspend fun Prepared.autoByCost(scales: FloatArray): ModelCrops {
        val solved = HashMap<Int, ModelCrops>()
        suspend fun at(i: Int): ModelCrops {
            solveAll(scales, listOf(i), solved)
            return solved.getValue(i)
        }
        val bound = bound(scales)
        var best = -1
        var bestValue = Double.MAX_VALUE
        suspend fun consider(i: Int) {
            if (i !in scales.indices) return
            val v = at(i).zoomObjective
            if (v < bestValue - EPS) {
                best = i
                bestValue = v
            }
        }
        // Tighter is a larger index. The tighter side is a short grid; the looser side a walk
        // that goes on while the objective improves, allowing LOOKAHEAD worse coarse steps to get
        // past a bump (a page's blocks fitting a step or two later).
        for (i in (bound + GRID_TIGHTER) downTo bound step COARSE_STEP) consider(i)
        var i = bound
        var worse = 0
        while (worse <= LOOKAHEAD && i - COARSE_STEP >= 0) {
            i -= COARSE_STEP
            val before = best
            consider(i)
            worse = if (best == i) 0 else worse + 1
            if (before == best && worse > LOOKAHEAD) break
        }
        // Then halve the step twice around the best so far.
        val coarse = best
        consider(coarse - 2)
        consider(coarse + 2)
        val fine = best
        consider(fine - 1)
        consider(fine + 1)
        val loosened = scales[best] + weights.autoMargin.toFloat()
        val chosen = scales.indices.firstOrNull { scales[it] <= loosened + 1e-6f }
            ?: if (loosened > scales[0]) 0 else scales.lastIndex
        return at(chosen)
    }

    /** Crops at the automatic scale; see [Prepared.auto]. */
    suspend fun auto(masks: List<InkMask>, scales: FloatArray = CropAnalysis.defaultScales()): ModelCrops =
        prepare(masks).auto(scales)

    /** The cheapest crops of [masks] at [scale], with 1 to [CropModelWeights.maxWindows] windows. */
    suspend fun solve(masks: List<InkMask>, scale: Float): ModelCrops = prepare(masks).solve(scale)

    private suspend fun solverFor(pages: List<PaddedPage>, scale: Float, buffer: Buffer): Solver {
        require(scale > 0f && scale <= 1f) { "Bad scale: $scale" }
        return Solver(pages, scale, buffer).apply { precompute() }
    }

    /** The solver for [masks] at [scale], for tests of its steps. */
    internal suspend fun solver(masks: List<InkMask>, scale: Float): Solver =
        solverFor(masks.map { PaddedPage(it, aspect) }, scale, Buffer())

    /**
     * A page's own best crop at [scale], ignoring every other page: where a page that fits none of
     * a paper's windows can sit. Its [PageCrop.windowIndex] is [OWN_WINDOW].
     */
    fun ownCrop(mask: InkMask, scale: Float, referenceInk: Double): PageCrop {
        val page = PaddedPage(mask, aspect)
        val w = page.windowWidth(scale)
        val h = page.windowHeight(scale)
        val cols = page.width - w + 1
        val rows = page.height - h + 1
        val offCentres = DoubleArray(cols * rows)
        page.offCentres(w, h, offCentres)
        val splits = if (weights.kappa > 0.0) DoubleArray(cols * rows).also { page.splitCosts(w, h, weights.blockGap, splitUnit(referenceInk), it, scratch.blocks) } else null
        var best = 0
        var bestCost = Double.MAX_VALUE
        for (pos in 0 until cols * rows) {
            val cut = page.inkCount - page.inside(pos % cols, pos / cols, w, h)
            val c = weights.alpha * cut / referenceInk + weights.beta * offCentres[pos] + (if (splits != null) weights.kappa * splits[pos] else 0.0) +
                if (page.inkCount == 0) 0.0 else weights.excess(cut.toDouble() / page.inkCount)
            if (c < bestCost - EPS) {
                bestCost = c
                best = pos
            }
        }
        return pageCrop(page, referenceInk, OWN_WINDOW, best % cols, best / cols, scale, scratch)
    }

    /** Scores given per-page windows (e.g. another method's) with this model's cost. */
    fun score(masks: List<InkMask>, perPage: List<CropWindow>): ModelCrops {
        require(masks.isNotEmpty() && masks.size == perPage.size) { "Need one window per page" }
        val pages = masks.map { PaddedPage(it, aspect) }
        val refInk = referenceInkOf(pages.map { it.inkCount })
        val distinct = perPage.distinct()
        val terms = pages.indices.map { p ->
            val (x, y) = pages[p].positionOf(perPage[p])
            pageCrop(pages[p], refInk, distinct.indexOf(perPage[p]), x, y, perPage[p].scale, scratch)
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
            pageCrop(page, referenceInk, j, x, y, crops.scale, scratch)
        }
        return options.minWith(compareBy<PageCrop> { pageCost(it) }.thenBy { if (it.windowIndex == preferred) 0 else 1 })
    }

    /** A page's own share of the cost in its window: every per-page term, none of the per-paper ones. */
    fun pageCost(c: PageCrop): Double =
        weights.alpha * c.cut + weights.beta * c.offCentre + weights.excess(c.pageCut) + weights.kappa * c.split

    private fun pageCrop(page: PaddedPage, refInk: Double, window: Int, x: Int, y: Int, scale: Float, buffer: Buffer): PageCrop {
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
            splitBlocks = if (weights.kappa > 0.0) page.split(x, y, w, h, weights.blockGap) else 0,
            split = if (weights.kappa > 0.0) page.splitCost(x, y, w, h, weights.blockGap, splitUnit(refInk)) else 0.0,
        )
    }

    /** Cells of ink cut off one block at which it costs the whole kappa. */
    private fun splitUnit(refInk: Double): Int = (weights.splitInk * refInk).roundToInt().coerceAtLeast(1)

    /** Scratch space reused across solves at several scales, so they don't each allocate it. */
    internal class Buffer {
        private var array = DoubleArray(0)

        /** Scratch for the pages' block costs, shared across pages and scales. */
        val blocks = BlockScratch()

        fun get(size: Int): DoubleArray {
            if (array.size < size) array = DoubleArray(size)
            return array
        }
    }

    // Scratch for the entry points outside a [Prepared] solve (ownCrop, assign, score), which
    // callers serialise like solves.
    private val scratch = Buffer()

    internal inner class Solver(private val pages: List<PaddedPage>, private val scale: Float, private val buffer: Buffer) {
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

        /** Nanoseconds spent in [precompute], in seeding the extra windows, and in refining them, for measuring. */
        var precomputeNanos = 0L
        val splitNanos = AtomicLong()
        val offNanos = AtomicLong()
        var seedNanos = 0L
        var refineNanos = 0L

        /** Nanoseconds the other threads spent in [precompute], for the CPU total. */
        var extraNanos = 0L
        /** Whether the search for a second window was skipped because it couldn't pay for itself. */
        var prunedAt = 0

        // cost[p * positions + pos]: page p's cost with the window at reference position
        // pos = y * cols + x.
        private val cost = buffer.get(pages.size * positions)
        private val sums = DoubleArray(positions)

        /**
         * Fills the cost table, the pages spread over [CropModel.parallelism] threads on
         * [Dispatchers.Default]: each page's row of the table is its own, and each thread has its
         * own scratch, so the table is the same however the pages are shared out.
         */
        suspend fun precompute() {
            val started = System.nanoTime()
            val threads = threadsFor(pages.size, pages.sumOf { it.width.toLong() * it.height })
            if (threads == 1) {
                precomputePages(0, pages.size, buffer.blocks)
            } else {
                val chunk = (pages.size + threads - 1) / threads
                val spent = coroutineScope {
                    (0 until pages.size step chunk).map { from ->
                        async(Dispatchers.Default) {
                            val t = System.nanoTime()
                            precomputePages(from, minOf(pages.size, from + chunk), BlockScratch())
                            System.nanoTime() - t
                        }
                    }.awaitAll()
                }
                extraNanos += spent.sum() - (System.nanoTime() - started)
            }
            precomputeNanos += System.nanoTime() - started
        }

        private suspend fun precomputePages(from: Int, until: Int, scratch: BlockScratch) {
            var offCentres = DoubleArray(0)
            var splits = DoubleArray(0)
            for (p in from until until) {
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
                val offStart = System.nanoTime()
                page.offCentres(w, h, offCentres)
                offNanos.addAndGet(System.nanoTime() - offStart)
                if (weights.kappa > 0.0) {
                    if (splits.size < size) splits = DoubleArray(size)
                    val splitStart = System.nanoTime()
                    page.splitCosts(w, h, weights.blockGap, splitUnit(refInk), splits, scratch)
                    splitNanos.addAndGet(System.nanoTime() - splitStart)
                }
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
                        weights.excess(cut.toDouble() / page.inkCount) +
                        (if (weights.kappa > 0.0) weights.kappa * splits[y * pageCols + x] else 0.0)
                }
            }
        }

        /** The least any page can cost at any position: a lower bound on what any windows can achieve. */
        private fun minimum(p: Int): Double {
            val row = p * positions
            var m = Double.MAX_VALUE
            for (pos in 0 until positions) if (cost[row + pos] < m) m = cost[row + pos]
            return m
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
            val seedStart = System.nanoTime()
            val seeds = if (weights.maxWindows > 1) pages.indices.map { bestFor(listOf(it)) }.distinct() else emptyList()
            // What more windows could save at most: every page at its own cheapest position. If
            // that doesn't cover one more window's price, no extra window can pay for itself.
            val floor = if (weights.maxWindows > 1) pages.indices.sumOf { minimum(it) } else 0.0
            seedNanos += System.nanoTime() - seedStart
            for (k in 2..weights.maxWindows) {
                if (bestCost - floor <= weights.gamma * (k - 1) + EPS) {
                    prunedAt = k
                    break
                }
                // Refining is the expensive part: only from the seeds that help most at once.
                val refineStart = System.nanoTime()
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
                refineNanos += System.nanoTime() - refineStart
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
                pageCrop(page, refInk, order.indexOf(bestAssignment[p]), x, y, scale, buffer)
            }
            return ModelCrops(scale, windows, crops, weights, pages.sumOf { it.inkCount.toLong() })
        }
    }

    companion object {
        private const val MAX_ROUNDS = 20
        private const val COARSE_STEP = 4
        private const val GRID_TIGHTER = 4
        private const val LOOKAHEAD = 1
        private const val MAX_SEEDS = 6
        private const val EPS = 1e-12

        /** [PageCrop.windowIndex] of a page's [ownCrop], outside the paper's shared windows. */
        const val OWN_WINDOW = -1

        /** Threads a solve may use by default: up to four, as the host has cores. */
        val DEFAULT_PARALLELISM: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        /**
         * Fewest frame cells over a paper's pages for a solve to use more than one thread: about
         * 30 letter pages at the analysis resolution (192 x 256 cells each).
         */
        const val MIN_PARALLEL_CELLS = 1_500_000L

        /** The median ink per page over pages with any ink: the unit cut_p is measured in. */
        fun referenceInk(masks: List<InkMask>): Double = referenceInkOf(masks.map { it.inkCount })

        private fun referenceInkOf(inks: List<Int>): Double {
            val inked = inks.filter { it > 0 }.sorted()
            return if (inked.isEmpty()) 1.0 else max(1, inked[(inked.size - 1) / 2]).toDouble()
        }
    }
}
