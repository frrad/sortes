package dev.sortes.app.zoom

import dev.sortes.app.pdf.PageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * A paper's zoom analysis: the crop model's crops at the automatic scale, plus what's needed to
 * solve other scales and to place windows on any of the paper's pages.
 */
class PaperAnalysis(
    /** Each analysed page's best window at every scale: bounds the model's own search. */
    val crop: CropAnalysis,
    val model: CropModel,
    /** The analysed pages' masks, without ignorable ink, in the order of [sample]. */
    private val masks: List<InkMask>,
    /** Which page each of [masks] is: the pages that were sampled and could be rendered. */
    val sample: List<Int>,
    /** Pixels per point the masks were rendered at; per-page masks use the same. */
    private val density: Float,
    /** Total pages in the paper; the analysis may come from fewer. */
    val pageCount: Int,
    /** The crops at the automatic scale. */
    val auto: ModelCrops,
) {
    init {
        require(masks.size == sample.size && masks.size == auto.pages.size) { "One mask and crop per sampled page" }
    }

    val aspect: Float get() = model.aspect

    /** Pages the analysis rendered and solved for. */
    val pagesAnalyzed: Int get() = masks.size

    private val referenceInk = CropModel.referenceInk(masks)
    private val solved = HashMap<Float, ModelCrops>().apply { put(auto.scale, auto) }
    private val solving = Mutex()

    /** The crops at [scale]: the model re-solved for it, so a manual zoom still gets the model's windows. */
    suspend fun cropsAt(scale: Float): ModelCrops = solving.withLock {
        solved[scale] ?: withContext(Dispatchers.Default) { model.solve(masks, scale) }.also { solved[scale] = it }
    }

    private val curvePoints = HashMap<Int, ModelCrops>()

    /**
     * The model's lost-ink curve: the crops it would choose at every [CURVE_STRIDE]th scale (and
     * the automatic one), loosest first. Solving them all takes a while, so each partial curve is
     * emitted as it grows; points are kept, so a second collection is quick.
     */
    fun curve(): Flow<ZoomCurve> = flow {
        val scales = crop.scales
        val wanted = (scales.indices.filter { it % CURVE_STRIDE == 0 } + crop.nearestIndex(auto.scale)).distinct().sorted()
        emit(curveOf(scales))
        for (i in wanted) {
            if (i in curvePoints) continue
            val crops = cropsAt(scales[i])
            curvePoints[i] = crops
            emit(curveOf(scales))
        }
    }

    private fun curveOf(scales: FloatArray) = ZoomCurve(
        scales = scales,
        points = curvePoints.mapValues { (_, c) -> ZoomCurve.Point(c.cutFraction.toFloat(), c.pagesCut) },
        pagesAnalyzed = pagesAnalyzed,
        autoScale = auto.scale,
    )

    /** Places page [index] of [source] in [crops]' windows, with the ink its window cuts off. */
    suspend fun place(source: PageSource, index: Int, crops: ModelCrops): Placement {
        val page = source.pageSize(index)
        val analysed = sample.indexOf(index)
        if (analysed >= 0) {
            val crop = crops.pages[analysed]
            return withContext(Dispatchers.Default) {
                Placement(crop.window, cutInk(masks[analysed], page, crop.cells))
            }
        }
        val (width, height) = maskSize(page, density)
        val rendered = source.renderMask(index, width, height)
        return withContext(Dispatchers.Default) {
            // The same stripped mask for both, so ignored ink is never shown as cut off.
            val mask = rendered.withoutIgnorableInk()
            val crop = model.assign(mask, crops, referenceInk, preferred = preferredWindow(index, crops))
            Placement(crop.window, cutInk(mask, page, crop.cells))
        }
    }

    /** For a page that wasn't analysed: the window of the nearest analysed page on the same side. */
    private fun preferredWindow(index: Int, crops: ModelCrops): Int {
        val sameSide = sample.indices.filter { sample[it] % 2 == index % 2 }.ifEmpty { sample.indices.toList() }
        val nearest = sameSide.minBy { kotlin.math.abs(sample[it] - index) }
        return crops.pages[nearest].windowIndex
    }

    companion object {
        /** The curve has a point every this many of [CropAnalysis.scales]: every 0.02 of zoom. */
        const val CURVE_STRIDE = 4
    }
}

/** Where a page's crop window sits, and the ink it [cut]s off. */
data class Placement(val window: CropWindow, val cut: List<CutInk>)

/** Most pages rendered to build the curve: enough to be representative, bounded in time and memory. */
const val MAX_ANALYZED_PAGES = 60

/**
 * Renders a sample of at most [maxPages] evenly spaced pages of [source] at low resolution and
 * solves the crop model for a screen of [aspect] at its automatic scale. Pages that fail to
 * render are left out.
 */
suspend fun analyzePaper(
    source: PageSource,
    aspect: Float,
    maxPages: Int = MAX_ANALYZED_PAGES,
    weights: CropModelWeights = CropModelWeights(),
): PaperAnalysis = withContext(Dispatchers.Default) {
    val sample = samplePages(source.pageCount, maxPages)
    val sizes = sample.associateWith { index -> orNull { source.pageSize(index) } }
    val readable = sizes.filterValues { it != null }
    if (readable.isEmpty()) throw java.io.IOException("No readable pages")
    val density = analysisDensity(readable.values.map { it!! }, aspect)
    val rendered = readable.mapNotNull { (index, size) ->
        val (width, height) = maskSize(size!!, density)
        orNull { index to source.renderMask(index, width, height).withoutIgnorableInk() }
    }
    if (rendered.isEmpty()) throw java.io.IOException("No pages could be rendered")
    val masks = rendered.map { it.second }
    val crop = CropAnalysis.of(masks, aspect)
    val model = CropModel(aspect, weights)
    PaperAnalysis(crop, model, masks, rendered.map { it.first }, density, source.pageCount, model.auto(masks, crop))
}

/** Up to [max] page indices spread evenly over [count] pages, including the first and last. */
fun samplePages(count: Int, max: Int): List<Int> {
    require(max > 0) { "max must be positive" }
    if (count <= max) return List(count) { it }
    if (max == 1) return listOf(0)
    return List(max) { (it.toLong() * (count - 1) / (max - 1)).toInt() }
}

private inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
