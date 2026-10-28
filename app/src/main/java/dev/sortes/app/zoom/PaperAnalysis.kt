package dev.sortes.app.zoom

import dev.sortes.app.pdf.PageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** A paper's zoom analysis, plus what's needed to place windows on any of its pages. */
class PaperAnalysis(
    val crop: CropAnalysis,
    /** Pixels per point the masks were rendered at; per-page masks use the same. */
    private val density: Float,
    /** Total pages in the paper; the curve may come from fewer. */
    val pageCount: Int,
) {
    val aspect: Float get() = crop.aspect

    /** Places a window of [scale] on page [index] of [source], near [anchor]. */
    suspend fun window(source: PageSource, index: Int, scale: Float, anchor: Anchor): CropWindow =
        place(source, index, scale, anchor).window

    /** Like [window], plus the ink that window cuts off the page. */
    suspend fun place(source: PageSource, index: Int, scale: Float, anchor: Anchor): Placement {
        val page = source.pageSize(index)
        val (width, height) = maskSize(page, density)
        val rendered = source.renderMask(index, width, height)
        return withContext(Dispatchers.Default) {
            // The same stripped mask for both, so ignored ink is never shown as cut off.
            val mask = rendered.withoutIgnorableInk()
            val placed = placeWindowCells(mask, aspect, scale, anchor)
            Placement(placed.window, cutInk(mask, page, placed.cells))
        }
    }
}

/** Where a page's crop window sits, and the ink it [cut]s off. */
data class Placement(val window: CropWindow, val cut: List<CutInk>)

/** Most pages rendered to build the curve: enough to be representative, bounded in time and memory. */
const val MAX_ANALYZED_PAGES = 60

/**
 * Renders a sample of at most [maxPages] evenly spaced pages of [source] at low resolution and
 * analyzes crops for a screen of [aspect]. Pages that fail to render are left out.
 */
suspend fun analyzePaper(
    source: PageSource,
    aspect: Float,
    maxPages: Int = MAX_ANALYZED_PAGES,
): PaperAnalysis = withContext(Dispatchers.Default) {
    val sample = samplePages(source.pageCount, maxPages)
    val sizes = sample.associateWith { index -> orNull { source.pageSize(index) } }
    val readable = sizes.filterValues { it != null }
    if (readable.isEmpty()) throw java.io.IOException("No readable pages")
    val density = analysisDensity(readable.values.map { it!! }, aspect)
    val masks = readable.mapNotNull { (index, size) ->
        val (width, height) = maskSize(size!!, density)
        orNull { source.renderMask(index, width, height).withoutIgnorableInk() }
    }
    if (masks.isEmpty()) throw java.io.IOException("No pages could be rendered")
    PaperAnalysis(CropAnalysis.of(masks, aspect), density, source.pageCount)
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
