package dev.sortes.app.zoom

/** Most pages the analysis renders and solves: enough to be representative, bounded in time and memory. */
const val MAX_ANALYZED_PAGES = 60

/**
 * Up to [max] page indices from [count] pages: all of them if they fit, else evenly spaced pairs
 * of facing pages (an even page and the odd one after it), from the first page to the last.
 *
 * Pairs keep both sides of a two-sided layout in the sample in equal numbers, alternating, as
 * the crop model needs to give each side its window. Plain even spacing can land on one side
 * only: 60 of 119 pages, every second page, are all even.
 */
fun samplePages(count: Int, max: Int): List<Int> {
    require(max > 0) { "max must be positive" }
    if (count <= max) return List(count) { it }
    if (max == 1) return listOf(0)
    val pairs = max / 2
    // Even starts from 0 to the last even page that has a page after it.
    val lastStart = (count - 2) / 2
    val starts = List(pairs) { 2 * (it.toLong() * lastStart / (pairs - 1).coerceAtLeast(1)).toInt() }.distinct()
    val pages = starts.flatMap { listOf(it, it + 1) }
    // An odd count's last page is even: it ends the sample if there's room.
    return if (pages.last() < count - 1 && pages.size < max) pages + (count - 1) else pages
}

/**
 * A paper's crop model solved on a [sample] of its pages: the crops at any scale, and any page's
 * crop, analysed or not. Not thread-safe: callers serialise solves.
 */
class SampledPaper(
    val model: CropModel,
    /** The sampled pages' masks, without ignorable ink, in the order of [sample]. */
    val masks: List<InkMask>,
    /** Which page each of [masks] is. */
    val sample: List<Int>,
    /** Each sampled page's extra cost (the text model's), if any, in the order of [sample]. */
    val extras: List<PageCost?>? = null,
    /** The automatic zoom's legibility cost of a scale; null for zeta·scale. */
    private val zoomCost: ((Float) -> Double)? = null,
) {
    init {
        require(masks.isNotEmpty() && masks.size == sample.size) { "One mask per sampled page" }
    }

    private val prepared = model.prepare(masks, extras)

    /** The unit pages' cut ink is measured in: the sample's median ink per page. */
    val referenceInk = CropModel.referenceInk(masks)

    suspend fun auto(): ModelCrops = if (zoomCost == null) prepared.auto() else prepared.auto(zoomCost = zoomCost)

    suspend fun solve(scale: Float): ModelCrops = prepared.solve(scale)

    /** Index into [sample] of page [index], or -1 if it wasn't sampled. */
    fun sampled(index: Int): Int = sample.indexOf(index)

    /**
     * The crop in [crops] of page [index], one outside the sample (a sampled page's is in
     * [crops]), given its [mask] (without ignorable ink) and its [extra] cost: the window that costs it least,
     * ties going to the window of the nearest sampled page on the same side. If even that window
     * cuts more than [CropModelWeights.allowance] of its ink, or (with blocks on) cuts into a
     * block (a landscape table, an offset figure the sample didn't see), it may get its own best
     * window at the same scale instead: with blocks, when that costs less by the model's own
     * per-page cost plus a window's worth ([CropModelWeights.gamma]); without, when it cuts less.
     * So with blocks an own window that cuts less can still lose, if what it saves (in cut,
     * centring and split blocks) is worth less than a window: the same trade-off the solver
     * makes for the sampled pages, deliberately, so an unsampled page is treated like its
     * neighbours rather than by a stricter rule.
     */
    fun cropFor(index: Int, crops: ModelCrops, mask: InkMask, extra: PageCost? = null): PageCrop {
        require(sampled(index) < 0) { "Page $index was sampled: its crop is in crops" }
        val weights = model.weights
        // Opened once for both: it builds the page's tables.
        val cost = extra?.open()
        val shared = model.assign(mask, crops, referenceInk, preferred = preferredWindow(index, crops), extra = cost)
        if (shared.pageCut <= weights.allowance && shared.split == 0.0) return shared
        val own = model.ownCrop(mask, crops.scale, referenceInk, cost)
        val better = if (weights.kappa > 0.0) model.pageCost(own) + weights.gamma < model.pageCost(shared) else own.pageCut < shared.pageCut
        return if (better) own else shared
    }

    /** For a page that wasn't analysed: the window of the nearest analysed page on the same side. */
    private fun preferredWindow(index: Int, crops: ModelCrops): Int {
        val sameSide = sample.indices.filter { sample[it] % 2 == index % 2 }.ifEmpty { sample.indices.toList() }
        val nearest = sameSide.minBy { kotlin.math.abs(sample[it] - index) }
        return crops.pages[nearest].windowIndex
    }
}
