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
    private val paper: SampledPaper,
    /** Pixels per point the masks were rendered at; per-page masks use the same. */
    private val density: Float,
    /** Total pages in the paper; the analysis may come from fewer. */
    val pageCount: Int,
    /** The crops at the automatic scale. */
    val auto: ModelCrops,
    /** Time spent finding [auto] once the pages were rendered, for diagnostics. */
    val solveMillis: Long = 0,
    /** A rendered page as the model sees it: without the config's ignorable ink. */
    private val prepare: (InkMask) -> InkMask = { it.withoutIgnorableInk() },
) {
    init {
        require(paper.masks.size == auto.pages.size) { "One crop per sampled page" }
    }

    val model: CropModel get() = paper.model
    val aspect: Float get() = model.aspect

    /** The pages that were sampled and could be rendered. */
    val sample: List<Int> get() = paper.sample

    /** The scales the zoom slider offers, loosest (1) first. */
    val scales: FloatArray = CropAnalysis.defaultScales()

    /** Pages the analysis rendered and solved for. */
    val pagesAnalyzed: Int get() = paper.masks.size

    private val solved = HashMap<Float, ModelCrops>().apply { put(auto.scale, auto) }
    private val solving = Mutex()

    /** The crops at [scale]: the model re-solved for it, so a manual zoom still gets the model's windows. */
    suspend fun cropsAt(scale: Float): ModelCrops = solving.withLock {
        solved[scale] ?: withContext(Dispatchers.Default) { paper.solve(scale) }.also { solved[scale] = it }
    }

    private val curvePoints = HashMap<Int, ModelCrops>()

    /**
     * The model's lost-ink curve: the crops it would choose at every [CURVE_STRIDE]th scale (and
     * the automatic one), loosest first, up to the first that cuts [CURVE_LIMIT] of the ink: the
     * tighter ones take longest to solve and nobody reads at them. Solving takes a while, so each
     * partial curve is emitted as it grows; points are kept, so a second collection is quick.
     */
    fun curve(): Flow<ZoomCurve> = flow {
        val autoIndex = scales.indices.minBy { kotlin.math.abs(scales[it] - auto.scale) }
        val wanted = (scales.indices.filter { it % CURVE_STRIDE == 0 } + autoIndex).distinct().sorted()
        emit(curveOf())
        for (i in wanted) {
            val crops = curvePoints[i] ?: cropsAt(scales[i]).also {
                curvePoints[i] = it
                emit(curveOf())
            }
            if (crops.cutFraction > CURVE_LIMIT) break
        }
    }

    private fun curveOf() = ZoomCurve(
        scales = scales,
        points = curvePoints.mapValues { (_, c) -> ZoomCurve.Point(c.cutFraction.toFloat(), c.pagesCut) },
        pagesAnalyzed = pagesAnalyzed,
        autoScale = auto.scale,
    )

    /** Places page [index] of [source] in [crops]' windows, with the ink its window cuts off. */
    suspend fun place(source: PageSource, index: Int, crops: ModelCrops): Placement {
        val page = source.pageSize(index)
        val analysed = paper.sampled(index)
        // The same stripped mask for the crop and the cut ink, so ignored ink is never shown cut.
        val mask = if (analysed >= 0) {
            paper.masks[analysed]
        } else {
            val (width, height) = maskSize(page, density)
            val rendered = source.renderMask(index, width, height)
            withContext(Dispatchers.Default) { prepare(rendered) }
        }
        return withContext(Dispatchers.Default) {
            val crop = paper.cropFor(index, crops, mask.takeIf { analysed < 0 })
            Placement(crop.window, cutInk(mask, page, crop.cells))
        }
    }

    companion object {
        /** The curve has a point every this many of [scales]: every 0.02 of zoom. */
        const val CURVE_STRIDE = 4

        /** The curve stops after the first point cutting more than this share of the ink. */
        const val CURVE_LIMIT = 0.2
    }
}

/** Where a page's crop window sits, and the ink it [cut]s off. */
data class Placement(val window: CropWindow, val cut: List<CutInk>)

/**
 * Renders a sample of at most [maxPages] pages of [source] ([samplePages]) at low resolution and
 * solves the crop model for a screen of [aspect] at its automatic scale. Pages that fail to
 * render are left out.
 */
suspend fun analyzePaper(
    source: PageSource,
    aspect: Float,
    maxPages: Int = MAX_ANALYZED_PAGES,
    config: CropConfig = CropConfig.READER,
): PaperAnalysis = withContext(Dispatchers.Default) {
    val sample = samplePages(source.pageCount, maxPages)
    val sizes = sample.associateWith { index -> orNull { source.pageSize(index) } }
    val readable = sizes.filterValues { it != null }
    if (readable.isEmpty()) throw java.io.IOException("No readable pages")
    val density = analysisDensity(readable.values.map { it!! }, aspect)
    val rendered = readable.mapNotNull { (index, size) ->
        val (width, height) = maskSize(size!!, density)
        orNull { index to source.renderMask(index, width, height).withoutIgnorableInk(config.pageRules) }
    }
    if (rendered.isEmpty()) throw java.io.IOException("No pages could be rendered")
    // Paper-level rules learn from the sample, then apply to any page.
    val paperRule = config.paperRule(rendered.map { it.second })
    val started = System.nanoTime()
    val paper = SampledPaper(CropModel(aspect, config.weights), rendered.map { paperRule.strip(it.second) }, rendered.map { it.first })
    val auto = paper.auto()
    val solveMillis = (System.nanoTime() - started) / 1_000_000
    PaperAnalysis(paper, density, source.pageCount, auto, solveMillis) { paperRule.strip(it.withoutIgnorableInk(config.pageRules)) }
}

private inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
