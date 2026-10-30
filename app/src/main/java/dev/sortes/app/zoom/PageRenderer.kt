package dev.sortes.app.zoom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A document's pages as pixels: the one part of the crop pipeline that differs between the
 * reader (Android's PdfRenderer) and zoom-eval (mutool). Everything from the pixels on (mask
 * sizes, thresholds, which pages are rendered at what resolution) is shared, in [analyzePaper].
 */
interface PageRenderer {
    val pageCount: Int

    suspend fun pageSize(index: Int): PageSize

    /**
     * Page [index] squashed into exactly [width] x [height] pixels on white paper, as opaque ARGB
     * in row-major order (as from `Bitmap.getPixels`).
     */
    suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray
}

/** Page [index] rendered at [width] x [height] and thresholded into an ink mask ([InkMask.fromArgb]). */
suspend fun PageRenderer.renderMask(index: Int, width: Int, height: Int): InkMask {
    val pixels = renderPixels(index, width, height)
    require(pixels.size == width * height) { "Page $index: expected ${width * height} pixels, got ${pixels.size}" }
    return withContext(Dispatchers.Default) { InkMask.fromArgb(pixels, width, height) }
}

/**
 * How the pipeline renders one paper: one density for all its pages ([analysisDensity] over the
 * pages sampled), each page's analysis mask at that density, and, for the text model, a render
 * [TextWeights.factor] times finer in each dimension.
 */
class PaperRaster(val source: PageRenderer, val density: Float) {
    /** Page [index]'s analysis mask, as rendered (before the ignorable-ink rules). */
    suspend fun coarse(index: Int): InkMask {
        val (width, height) = maskSize(source.pageSize(index), density)
        return source.renderMask(index, width, height)
    }

    /** Page [index] rendered [factor] times finer than its analysis mask [coarse]. */
    suspend fun fine(index: Int, coarse: InkMask, factor: Int): InkMask =
        source.renderMask(index, coarse.width * factor, coarse.height * factor)
}
