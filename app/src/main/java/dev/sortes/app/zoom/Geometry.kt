package dev.sortes.app.zoom

import kotlin.math.max
import kotlin.math.roundToInt

/** A page's size in PDF points. */
data class PageSize(val width: Float, val height: Float)

/** A rectangle in page points. It may extend past the page into the padding around it. */
data class Region(val left: Float, val top: Float, val width: Float, val height: Float)

/** The page padded with equal margins on two sides until its width / height is [aspect]. */
fun PageSize.padded(aspect: Float): PageSize =
    if (width / height < aspect) PageSize(height * aspect, height) else PageSize(width, width / aspect)

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
 * Pixels per point at which to render pages for analysis: one density for the whole paper, so
 * its largest padded page is [longSide] pixels on its longer side.
 */
fun analysisDensity(pages: List<PageSize>, aspect: Float, longSide: Int = ANALYSIS_LONG_SIDE): Float =
    longSide / pages.maxOf { p -> p.padded(aspect).let { max(it.width, it.height) } }

/** Mask size for one page at [density] pixels per point. */
fun maskSize(page: PageSize, density: Float): Pair<Int, Int> =
    (page.width * density).roundToInt().coerceAtLeast(1) to
        (page.height * density).roundToInt().coerceAtLeast(1)

/** Coarse enough to analyze a paper in well under a second, fine enough to find text edges. */
const val ANALYSIS_LONG_SIDE = 256
