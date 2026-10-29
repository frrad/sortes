package dev.sortes.app.zoom

/** Most pages rendered to build the curve: enough to be representative, bounded in time and memory. */
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
) {
    init {
        require(masks.isNotEmpty() && masks.size == sample.size) { "One mask per sampled page" }
    }

    private val prepared = model.prepare(masks)

    /** The unit pages' cut ink is measured in: the sample's median ink per page. */
    val referenceInk = CropModel.referenceInk(masks)

    suspend fun auto(): ModelCrops = prepared.auto()

    suspend fun solve(scale: Float): ModelCrops = prepared.solve(scale)

    /** Index into [sample] of page [index], or -1 if it wasn't sampled. */
    fun sampled(index: Int): Int = sample.indexOf(index)

    /**
     * Page [index]'s crop in [crops]: the model's own choice for a sampled page. A page outside
     * the sample, given its [mask] (without ignorable ink), takes the window that costs it least,
     * ties going to the window of the nearest sampled page on the same side. If even that window
     * cuts more than [CropModelWeights.allowance] of its ink (a landscape table, an offset
     * figure the sample didn't see), it gets its own best window at the same scale instead.
     */
    fun cropFor(index: Int, crops: ModelCrops, mask: InkMask?): PageCrop {
        val analysed = sampled(index)
        if (analysed >= 0) return crops.pages[analysed]
        requireNotNull(mask) { "Page $index wasn't sampled: its mask is needed" }
        val shared = model.assign(mask, crops, referenceInk, preferred = preferredWindow(index, crops))
        if (shared.pageCut <= model.weights.allowance) return shared
        return model.ownCrop(mask, crops.scale, referenceInk).takeIf { it.pageCut < shared.pageCut } ?: shared
    }

    /** For a page that wasn't analysed: the window of the nearest analysed page on the same side. */
    private fun preferredWindow(index: Int, crops: ModelCrops): Int {
        val sameSide = sample.indices.filter { sample[it] % 2 == index % 2 }.ifEmpty { sample.indices.toList() }
        val nearest = sameSide.minBy { kotlin.math.abs(sample[it] - index) }
        return crops.pages[nearest].windowIndex
    }
}
