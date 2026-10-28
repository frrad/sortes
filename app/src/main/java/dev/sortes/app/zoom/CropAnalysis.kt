package dev.sortes.app.zoom

import kotlin.math.roundToInt

/**
 * Where to crop one page: a window with the screen's aspect ratio, positioned within the page's
 * padded frame (the page centered in white margins until it has the screen's aspect ratio).
 * All values are fractions of the padded frame, so [scale] 1 shows the whole frame.
 */
data class CropWindow(val left: Float, val top: Float, val scale: Float)

/**
 * Finds a single zoom level for a whole paper.
 *
 * Every page is padded to the screen's [aspect] ratio (width / height), so a window that is
 * `scale` times the padded frame in each dimension fills the screen exactly. For each candidate
 * scale, each page's window is slid to wherever it leaves the least ink outside it. Summed over
 * the paper, that lost ink rises as the window shrinks: slowly while it only trims margins,
 * page numbers and running heads, then steeply once it cuts into the body text.
 */
class CropAnalysis(
    masks: List<InkMask>,
    val aspect: Float,
    /** Candidate scales, from loosest (1 = whole padded page) to tightest. */
    val scales: FloatArray = defaultScales(),
) {
    init {
        require(masks.isNotEmpty()) { "No pages" }
        require(aspect > 0f) { "Bad aspect ratio: $aspect" }
        require(scales.isNotEmpty() && scales.all { it > 0f && it <= 1f }) { "Bad scales" }
    }

    private val pages = masks.map { PaddedPage(it, aspect) }

    val totalInk: Long = pages.sumOf { it.inkCount.toLong() }

    /** `pageLost[p][i]`: ink on page `p` that even the best window at `scales[i]` leaves out. */
    private val pageLost: Array<IntArray> = Array(pages.size) { p ->
        IntArray(scales.size) { i -> pages[p].let { it.inkCount - it.maxInside(scales[i]) } }
    }

    /** Ink left outside the window across the whole paper, for each of [scales]. */
    val lostInk: LongArray = LongArray(scales.size) { i -> pageLost.sumOf { it[i].toLong() } }

    /** [lostInk] as a fraction of all ink in the paper; 0 for a blank paper. */
    fun lostFraction(i: Int): Float =
        if (totalInk == 0L) 0f else lostInk[i].toFloat() / totalInk

    /** Number of pages that lose any ink at `scales[i]`. */
    fun pagesCropped(i: Int): Int = pageLost.count { it[i] > 0 }

    /** Index into [scales] of the entry closest to [scale]. */
    fun nearestIndex(scale: Float): Int =
        scales.indices.minBy { kotlin.math.abs(scales[it] - scale) }

    /** The zoom level picked automatically from the lost-ink curve. */
    val autoScale: Float = scales[autoIndex()]

    private fun autoIndex(): Int {
        // Nothing to fit: a blank paper (or one we couldn't see) stays whole.
        if (totalInk == 0L) return 0
        // Tightest scale that still keeps nearly all the ink: running heads, page numbers and
        // stray marks are a small share of a paper, so cutting them stays under the threshold,
        // while cutting into body text quickly exceeds it.
        val tightest = scales.indices.lastOrNull { lostFraction(it) <= AUTO_MAX_LOST } ?: 0
        // Then step back out a little, so text isn't flush against the screen edge.
        val loosened = scales[tightest] + AUTO_MARGIN
        return scales.indices.firstOrNull { scales[it] <= loosened + 1e-6f } ?: 0
    }

    /**
     * Places a window of [scale] on every page so it leaves out as little ink as possible.
     *
     * Pages often have several equally good positions (a short last page, a page with wide
     * margins). Among those, every page takes the one closest to where the paper's pages
     * typically sit, so the text doesn't jump around as you page through.
     */
    fun placements(scale: Float): List<CropWindow> {
        val fits = pages.map { it.fit(scale) }
        val inked = fits.filter { it.page.inkCount > 0 }
        val refU = inked.map { it.centroidU }.median() ?: 0.5f
        val refV = inked.map { it.centroidV }.median() ?: 0.5f
        return fits.map { it.window(refU, refV, scale) }
    }

    companion object {
        const val MIN_SCALE = 0.3f
        const val SCALE_STEP = 0.005f

        /** Share of the paper's ink the automatic zoom may cut off. */
        const val AUTO_MAX_LOST = 0.005f

        /** How much looser than the tightest acceptable scale the automatic zoom is. */
        const val AUTO_MARGIN = 0.02f

        /** Ink a page may lose beyond its optimum to keep the window steady across pages. */
        const val TIE_TOLERANCE = 0.001f

        fun defaultScales(): FloatArray {
            val n = ((1f - MIN_SCALE) / SCALE_STEP).roundToInt() + 1
            return FloatArray(n) { 1f - it * SCALE_STEP }
        }
    }
}

private fun List<Float>.median(): Float? =
    if (isEmpty()) null else sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

/** A page's ink mask centered in a frame with the screen's aspect ratio, plus a summed-area table. */
private class PaddedPage(mask: InkMask, aspect: Float) {
    val width: Int
    val height: Int
    val inkCount = mask.inkCount

    // sums[y * (width + 1) + x] = ink in the frame above and left of (x, y).
    private val sums: IntArray

    init {
        if (mask.width.toFloat() / mask.height < aspect) {
            width = (mask.height * aspect).roundToInt().coerceAtLeast(mask.width)
            height = mask.height
        } else {
            width = mask.width
            height = (mask.width / aspect).roundToInt().coerceAtLeast(mask.height)
        }
        val offX = (width - mask.width) / 2
        val offY = (height - mask.height) / 2
        val stride = width + 1
        sums = IntArray(stride * (height + 1))
        for (y in 0 until height) {
            var row = 0
            for (x in 0 until width) {
                val mx = x - offX
                val my = y - offY
                if (mx in 0 until mask.width && my in 0 until mask.height && mask[mx, my]) row++
                sums[(y + 1) * stride + x + 1] = sums[y * stride + x + 1] + row
            }
        }
    }

    fun windowWidth(scale: Float) = (scale * width).roundToInt().coerceIn(1, width)
    fun windowHeight(scale: Float) = (scale * height).roundToInt().coerceIn(1, height)

    fun inside(x: Int, y: Int, w: Int, h: Int): Int {
        val stride = width + 1
        return sums[(y + h) * stride + x + w] - sums[y * stride + x + w] -
            sums[(y + h) * stride + x] + sums[y * stride + x]
    }

    fun maxInside(scale: Float): Int {
        val w = windowWidth(scale)
        val h = windowHeight(scale)
        var best = 0
        for (y in 0..height - h) for (x in 0..width - w) {
            val v = inside(x, y, w, h)
            if (v > best) best = v
        }
        return best
    }

    fun fit(scale: Float) = Fit(this, windowWidth(scale), windowHeight(scale))

    /** The near-optimal window positions for one window size. */
    class Fit(val page: PaddedPage, private val w: Int, private val h: Int) {
        private val rangeX = page.width - w
        private val rangeY = page.height - h
        private val good: List<Int>  // positions packed as y * (rangeX + 1) + x
        val centroidU: Float
        val centroidV: Float

        init {
            val values = IntArray((rangeX + 1) * (rangeY + 1))
            for (y in 0..rangeY) for (x in 0..rangeX) {
                values[y * (rangeX + 1) + x] = page.inside(x, y, w, h)
            }
            val best = values.max()
            val floor = best - (page.inkCount * CropAnalysis.TIE_TOLERANCE).toInt()
            good = values.indices.filter { values[it] >= floor }
            centroidU = norm(good.map { it % (rangeX + 1) }.average(), rangeX)
            centroidV = norm(good.map { it / (rangeX + 1) }.average(), rangeY)
        }

        private fun norm(pos: Double, range: Int) = if (range == 0) 0.5f else (pos / range).toFloat()

        /** The good position nearest the reference point, as a window on the padded frame. */
        fun window(refU: Float, refV: Float, scale: Float): CropWindow {
            val refX = refU * rangeX
            val refY = refV * rangeY
            val pos = good.minBy {
                val dx = it % (rangeX + 1) - refX
                val dy = it / (rangeX + 1) - refY
                dx * dx + dy * dy
            }
            val x = pos % (rangeX + 1)
            val y = pos / (rangeX + 1)
            // Center the exact-scale window on the cell window, which rounding made slightly off.
            val left = (x + w / 2f) / page.width - scale / 2
            val top = (y + h / 2f) / page.height - scale / 2
            return CropWindow(
                left = left.coerceIn(0f, 1f - scale),
                top = top.coerceIn(0f, 1f - scale),
                scale = scale,
            )
        }
    }
}
