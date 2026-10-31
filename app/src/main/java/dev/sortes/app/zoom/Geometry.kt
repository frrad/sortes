package dev.sortes.app.zoom

import kotlin.math.max
import kotlin.math.roundToInt

/** A page's size in PDF points. */
data class PageSize(val width: Float, val height: Float)

/** A rectangle in page points. It may extend past the page into the padding around it. */
data class Region(val left: Float, val top: Float, val width: Float, val height: Float)

/** The page padded with equal margins on two sides until its width / height is [aspect]. */
fun PageSize.padded(aspect: Float): PageSize =
    if (width / height < aspect) PageSize(height * aspect, height)
    else PageSize(width, width / aspect)

/** The part of [page], in page points, that this window shows. */
fun CropWindow.regionOn(page: PageSize, aspect: Float): Region {
    val frame = page.padded(aspect)
    return Region(
        left = left * frame.width - (frame.width - page.width) / 2,
        top = top * frame.height - (frame.height - page.height) / 2,
        width = scale * frame.width,
        height = scale * frame.height,
    )
}

/** Shows the whole padded page: what the reader looks like with no zoom. */
val FullPage = CropWindow(left = 0f, top = 0f, scale = 1f)

/**
 * Pixels per point at which to render pages for analysis: one density for the whole paper, so its
 * largest padded page is [longSide] pixels on its longer side.
 */
fun analysisDensity(
    pages: List<PageSize>,
    aspect: Float,
    longSide: Int = ANALYSIS_LONG_SIDE,
): Float = longSide / pages.maxOf { p -> p.padded(aspect).let { max(it.width, it.height) } }

/** Mask size for one page at [density] pixels per point. */
fun maskSize(page: PageSize, density: Float): Pair<Int, Int> =
    (page.width * density).roundToInt().coerceAtLeast(1) to
        (page.height * density).roundToInt().coerceAtLeast(1)

/** Coarse enough to analyze a paper in well under a second, fine enough to find text edges. */
const val ANALYSIS_LONG_SIDE = 256

/**
 * Where to crop one page: a window with the screen's aspect ratio, positioned within the page's
 * padded frame (the page centered in white margins until it has the screen's aspect ratio). All
 * values are fractions of the padded frame, so [scale] 1 shows the whole frame.
 */
data class CropWindow(val left: Float, val top: Float, val scale: Float)

/**
 * Mask cells from ([left], [top]) inclusive to ([right], [bottom]) exclusive. It may reach past the
 * mask into the padding around it.
 */
data class CellRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** The zoom levels: what the slider offers and what the automatic zoom searches. */
object ZoomScales {
    const val MIN_SCALE = 0.3f
    const val STEP = 0.005f

    /**
     * The automatic zoom tries every scale of [defaultScales] from [SEARCH_MIN] to [SEARCH_MAX]
     * (then loosens by the config's margin): no paper in the corpus reads well outside them.
     */
    const val SEARCH_MIN = 0.60f
    const val SEARCH_MAX = 0.97f

    /** The scales, loosest (1 = whole padded page) first, every [STEP] down to [MIN_SCALE]. */
    fun defaultScales(): FloatArray {
        val n = ((1f - MIN_SCALE) / STEP).roundToInt() + 1
        return FloatArray(n) { 1f - it * STEP }
    }
}
