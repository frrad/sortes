package dev.sortes.app.zoom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * A paper's zoom analysis: the crop model's crops at the automatic scale, plus what's needed to
 * solve other scales and to place windows on any of the paper's pages. Made by [analyzePaper],
 * the one entry point the reader and zoom-eval share.
 */
class PaperAnalysis internal constructor(
    private val paper: SampledPaper,
    private val raster: PaperRaster,
    val config: CropConfig,
    /** Total pages in the paper; the analysis may come from fewer. */
    val pageCount: Int,
    /** The crops at the automatic scale. */
    val auto: ModelCrops,
    /** The paper's body glyph height on the reference screen at scale 1 ([PageText.glyphPx]), or null without text. */
    val glyphPx: Double?,
    /** Time spent finding [auto] once the pages were rendered and measured, for diagnostics. */
    val solveMillis: Long = 0,
    /** A rendered page as the model sees it: without the config's ignorable ink. */
    private val prepare: (InkMask) -> InkMask,
) {
    init {
        require(paper.masks.size == auto.pages.size) { "One crop per sampled page" }
    }

    val model: CropModel get() = paper.model
    val aspect: Float get() = model.aspect

    /** Pixels per point the masks are rendered at. */
    val density: Float get() = raster.density

    /** The pages that were sampled and could be rendered. */
    val sample: List<Int> get() = paper.sample

    /** The scales the zoom slider offers, loosest (1) first. */
    val scales: FloatArray = ZoomScales.defaultScales()

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

    /**
     * Places page [index] of the paper in [crops]' windows, with the ink its window cuts off.
     * A page outside the sample is rendered (and, for the text model, measured) here, from the
     * pages [analyzePaper] was given.
     */
    suspend fun place(index: Int, crops: ModelCrops): Placement {
        val (crop, mask) = cropOf(index, crops)
        val page = raster.source.pageSize(index)
        return withContext(Dispatchers.Default) { Placement(crop.window, cutInk(mask, page, crop.cells), crop) }
    }

    /** Page [index]'s crop in [crops] and its mask as the model sees it. */
    private suspend fun cropOf(index: Int, crops: ModelCrops): Pair<PageCrop, InkMask> {
        val analysed = paper.sampled(index)
        if (analysed >= 0) return crops.pages[analysed] to paper.masks[analysed]
        val raw = raster.coarse(index)
        val mask = withContext(Dispatchers.Default) { prepare(raw) }
        val extra = if (config.text.placesWindows) measure(raster, index, raw, mask, config.text) else null
        val crop = withContext(Dispatchers.Default) { paper.cropFor(index, crops, mask, extra) }
        return crop to mask
    }

    /**
     * Every page's crop in [crops], placed as [place] places it, as one [ModelCrops] over the
     * whole paper: a page with a window of its own adds it to the windows. With each page's mask
     * as the model sees it. For tools that look at every page; the reader places pages as they're
     * shown.
     */
    suspend fun placeAll(crops: ModelCrops): PlacedPaper {
        val windows = crops.windows.toMutableList()
        val masks = ArrayList<InkMask>(pageCount)
        val pages = (0 until pageCount).map { i ->
            val (crop, mask) = cropOf(i, crops)
            masks += mask
            if (crop.windowIndex != CropModel.OWN_WINDOW) crop else {
                windows += crop.window
                crop.copy(windowIndex = windows.lastIndex)
            }
        }
        return PlacedPaper(ModelCrops(crops.scale, windows, pages, crops.weights, masks.sumOf { it.inkCount.toLong() }), masks)
    }

    companion object {
        /** The curve has a point every this many of [scales]: every 0.02 of zoom. */
        const val CURVE_STRIDE = 4

        /** The curve stops after the first point cutting more than this share of the ink. */
        const val CURVE_LIMIT = 0.2
    }
}

/** Where a page's crop window sits, the ink it [cut]s off, and the model's [crop] of the page. */
data class Placement(val window: CropWindow, val cut: List<CutInk>, val crop: PageCrop)

/** Every page's crop ([PaperAnalysis.placeAll]) and each page's mask as the model saw it. */
class PlacedPaper(val crops: ModelCrops, val masks: List<InkMask>)

/**
 * The crop pipeline, shared by the reader and zoom-eval: renders a sample of at most [maxPages]
 * pages of [source] ([samplePages]) at low resolution, strips [config]'s ignorable ink, measures
 * each sampled page's text on a finer render (released page by page), and solves the crop model
 * for a screen of [aspect] at its automatic scale. Pages that fail to render are left out.
 */
suspend fun analyzePaper(
    source: PageRenderer,
    aspect: Float,
    config: CropConfig = CropConfig.DEFAULT,
    maxPages: Int = MAX_ANALYZED_PAGES,
): PaperAnalysis = withContext(Dispatchers.Default) {
    val (readable, raster) = paperRaster(source, aspect, maxPages)
    val rendered = readable.mapNotNull { index -> orNull { index to raster.coarse(index) } }
    if (rendered.isEmpty()) throw java.io.IOException("No pages could be rendered")
    val stripped = rendered.map { it.second.withoutIgnorableInk(config.pageRules) }
    // Paper-level rules learn from the sample, then apply to any page.
    val paperRule = config.paperRule(stripped)
    val masks = stripped.map { paperRule.strip(it) }
    // One fine render alive at a time: each page keeps only what's measured on it.
    val text = rendered.indices.map { i -> measure(raster, rendered[i].first, rendered[i].second, masks[i], config.text) }
    val glyphPx = PageText.glyphPx(text, masks, aspect, config.text)
    val started = System.nanoTime()
    val legibility = config.text.legibility(config.weights.zeta)
    val paper = SampledPaper(
        CropModel(aspect, config.weights),
        masks,
        rendered.map { it.first },
        extras = text.takeIf { config.text.placesWindows },
        zoomCost = if (glyphPx == null) { s -> config.weights.zeta * s } else { s -> legibility.cost(s.toDouble(), glyphPx) },
    )
    val auto = paper.auto()
    val solveMillis = (System.nanoTime() - started) / 1_000_000
    PaperAnalysis(paper, raster, config, source.pageCount, auto, glyphPx, solveMillis) {
        paperRule.strip(it.withoutIgnorableInk(config.pageRules))
    }
}

/**
 * The pages of [source] the pipeline samples ([samplePages], at most [maxPages]) whose sizes can
 * be read, and how it renders the paper: at the density ([analysisDensity]) those pages give.
 */
suspend fun paperRaster(source: PageRenderer, aspect: Float, maxPages: Int = MAX_ANALYZED_PAGES): Pair<List<Int>, PaperRaster> {
    val sample = samplePages(source.pageCount, maxPages)
    val sizes = sample.associateWith { index -> orNull { source.pageSize(index) } }
    val readable = sizes.filterValues { it != null }
    if (readable.isEmpty()) throw java.io.IOException("No readable pages")
    return readable.keys.toList() to PaperRaster(source, analysisDensity(readable.values.map { it!! }, aspect))
}

/** Page [index]'s text, from a render [TextWeights.factor] times finer than [raw]; null if it can't be rendered. */
private suspend fun measure(raster: PaperRaster, index: Int, raw: InkMask, prepared: InkMask, text: TextWeights): PageText? =
    orNull { raster.fine(index, raw, text.factor) }?.let { fine ->
        withContext(Dispatchers.Default) { PageText.of(fine, raw, prepared, text) }
    }

private inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
