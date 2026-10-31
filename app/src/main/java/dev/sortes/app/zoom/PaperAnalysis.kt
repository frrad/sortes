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
    /** What was measured on each sampled page's fine render, in the order of [sample]; null where it couldn't be rendered. */
    private val sampleText: List<PageText?>,
    /** Time spent finding [auto] once the pages were rendered and measured, for diagnostics. */
    val solveMillis: Long,
    /** A rendered page as the model sees it: without the config's ignorable ink ([CropConfig.preparer]). */
    private val prepare: (InkMask) -> InkMask,
) {
    init {
        require(paper.masks.size == auto.pages.size && sampleText.size == auto.pages.size) { "One crop per sampled page" }
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

    /** Of those, the pages whose text could be measured (their fine render didn't fail). */
    val pagesMeasured: Int get() = sampleText.count { it != null }

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

    /** A page as the model sees it: its [mask] without ignorable ink, and its [text] (null if not measured). */
    class Page(val mask: InkMask, val text: PageText?)

    // Pages outside the sample, rendered and measured as they're placed, the latest few kept:
    // paging back, or changing the zoom, doesn't render them again.
    private val unsampled = object : LinkedHashMap<Int, Page>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Page>) = size > UNSAMPLED_KEPT
    }
    private val unsampledLock = Mutex()

    /**
     * Page [index] as the model sees it. A page outside the sample is rendered (and, for the
     * text model, measured) from the pages [analyzePaper] was given, the first time it's asked
     * for. Throws if it can't be rendered at all.
     */
    suspend fun page(index: Int): Page {
        val analysed = paper.sampled(index)
        if (analysed >= 0) return Page(paper.masks[analysed], sampleText[analysed])
        return unsampledLock.withLock {
            unsampled[index] ?: run {
                val raw = raster.coarse(index)
                val mask = withContext(Dispatchers.Default) { prepare(raw) }
                Page(mask, if (config.text.placesWindows) measure(raster, index, raw, mask, config.text) else null)
            }.also { unsampled[index] = it }
        }
    }

    /** Page [index]'s crop in [crops], and the page as the model sees it. */
    private suspend fun cropOf(index: Int, crops: ModelCrops): Pair<PageCrop, Page> {
        val page = page(index)
        val analysed = paper.sampled(index)
        if (analysed >= 0) return crops.pages[analysed] to page
        // Pure: safe alongside solves and other placements.
        return withContext(Dispatchers.Default) { paper.cropFor(index, crops, page.mask, page.text) } to page
    }

    /** Places page [index] of the paper in [crops]' windows, with the ink its window cuts off. */
    suspend fun place(index: Int, crops: ModelCrops): Placement {
        val (crop, page) = cropOf(index, crops)
        val size = raster.source.pageSize(index)
        return withContext(Dispatchers.Default) { Placement(crop.window, cutInk(page.mask, size, crop.cells)) }
    }

    /**
     * Every page's crop in [crops], placed as [place] places it, as one [ModelCrops] over the
     * whole paper: a page with a window of its own adds it to the windows. For tools that look at
     * every page; the reader places pages as they're shown.
     */
    suspend fun placeAll(crops: ModelCrops): PlacedPaper {
        val windows = crops.windows.toMutableList()
        val pages = ArrayList<Page>(pageCount)
        val placed = (0 until pageCount).map { i ->
            val (crop, page) = cropOf(i, crops)
            pages += page
            if (crop.windowIndex != CropModel.OWN_WINDOW) crop else {
                windows += crop.window
                crop.copy(windowIndex = windows.lastIndex)
            }
        }
        return PlacedPaper(this, ModelCrops(crops.scale, windows, placed, crops.weights, pages.sumOf { it.mask.inkCount.toLong() }), pages)
    }

    companion object {
        /** The curve has a point every this many of [scales]: every 0.02 of zoom. */
        const val CURVE_STRIDE = 4

        /** The curve stops after the first point cutting more than this share of the ink. */
        const val CURVE_LIMIT = 0.2

        /** Unsampled pages kept rendered and measured: a few page turns either way. */
        const val UNSAMPLED_KEPT = 8
    }
}

/** Where a page's crop window sits, and the ink it [cut]s off. */
data class Placement(val window: CropWindow, val cut: List<CutInk>)

/** Every page's crop in one [ModelCrops] ([PaperAnalysis.placeAll]), from [analysis], and each page as the model saw it. */
class PlacedPaper(val analysis: PaperAnalysis, val crops: ModelCrops, val pages: List<PaperAnalysis.Page>) {
    /** Each page's mask as the model saw it (the config's ignorable ink stripped). */
    val masks: List<InkMask> get() = pages.map { it.mask }
}

/**
 * The crop pipeline, shared by the reader and zoom-eval: renders a sample of at most [maxPages]
 * pages of [source] ([samplePages]) at low resolution, strips [config]'s ignorable ink, measures
 * each sampled page's text on a finer render (released page by page), and solves the crop model
 * for a screen of [aspect] at its automatic scale. Pages that fail to render are left out; a page
 * whose fine render fails is solved without its text.
 */
suspend fun analyzePaper(
    source: PageRenderer,
    aspect: Float,
    config: CropConfig = CropConfig.DEFAULT,
    maxPages: Int = MAX_ANALYZED_PAGES,
): PaperAnalysis = withContext(Dispatchers.Default) {
    val raster = paperRaster(source, aspect, maxPages)
    val rendered = raster.sample.mapNotNull { index -> orNull { index to raster.coarse(index) } }
    if (rendered.isEmpty()) throw java.io.IOException("No pages could be rendered")
    // Paper-level rules learn from the sample, then apply to any page.
    val prepare = config.preparer(rendered.map { it.second })
    val masks = rendered.map { prepare(it.second) }
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
        zoomCost = glyphPx?.let { k -> { s -> legibility.cost(s.toDouble(), k) } },
    )
    val auto = paper.auto()
    val solveMillis = (System.nanoTime() - started) / 1_000_000
    PaperAnalysis(paper, raster, config, source.pageCount, auto, glyphPx, text, solveMillis, prepare)
}

/**
 * How the pipeline renders [source]: its [PaperRaster.sample] ([samplePages], at most [maxPages],
 * those whose sizes can be read), at the density ([analysisDensity]) those pages give.
 */
suspend fun paperRaster(source: PageRenderer, aspect: Float, maxPages: Int = MAX_ANALYZED_PAGES): PaperRaster {
    val sample = samplePages(source.pageCount, maxPages)
    val sizes = sample.associateWith { index -> orNull { source.pageSize(index) } }
    val readable = sizes.filterValues { it != null }
    if (readable.isEmpty()) throw java.io.IOException("No readable pages")
    return PaperRaster(source, analysisDensity(readable.values.map { it!! }, aspect), readable.keys.toList())
}

/** Page [index]'s text, from a render [TextWeights.factor] times finer than [raw]; null if it can't be rendered. */
private suspend fun measure(raster: PaperRaster, index: Int, raw: InkMask, prepared: InkMask, text: TextWeights): PageText? =
    orNull { raster.fine(index, raw, text.factor) }?.let { fine ->
        withContext(Dispatchers.Default) { PageText.of(fine, raw, prepared, text) }
    }

/**
 * [block]'s result, or null if it fails as a page of a broken PDF can (pdfium throws various
 * exceptions). Cancellation and [IllegalArgumentException] (a bug, such as a render of the
 * wrong size) propagate.
 */
private inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: IllegalArgumentException) {
    throw e
} catch (e: Exception) {
    null
}
