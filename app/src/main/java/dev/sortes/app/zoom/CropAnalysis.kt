package dev.sortes.app.zoom

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

/**
 * Where to crop one page: a window with the screen's aspect ratio, positioned within the page's
 * padded frame (the page centered in white margins until it has the screen's aspect ratio).
 * All values are fractions of the padded frame, so [scale] 1 shows the whole frame.
 */
data class CropWindow(val left: Float, val top: Float, val scale: Float)

/**
 * Where windows typically sit on a paper's pages, as fractions of the room each window has to
 * move in: (0, 0) is flush top-left, (1, 1) flush bottom-right.
 */
data class Anchor(val u: Float, val v: Float)

/**
 * Finds a single zoom level for a whole paper.
 *
 * Every page is padded to the screen's [aspect] ratio (width / height), so a window that is
 * `scale` times the padded frame in each dimension fills the screen exactly. For each candidate
 * scale, each page's window is slid to wherever it leaves the least ink outside it. Summed over
 * the paper, that lost ink rises as the window shrinks: slowly while it only trims margins,
 * page numbers and running heads, then steeply once it cuts into the body text.
 *
 * Long papers are analyzed from a sample of their pages; see [analyzePaper].
 */
class CropAnalysis private constructor(
    private val masks: List<InkMask>,
    val aspect: Float,
    /** Candidate scales, from loosest (1 = whole padded page) to tightest. */
    val scales: FloatArray,
    /** `pageLost[p][i]`: ink on page `p` that even the best window at `scales[i]` leaves out. */
    private val pageLost: Array<IntArray>,
) {
    /** Number of pages the curve was computed from. */
    val pagesAnalyzed: Int get() = masks.size

    val totalInk: Long = masks.sumOf { it.inkCount.toLong() }

    /** Ink left outside the window across the analyzed pages, for each of [scales]. */
    val lostInk: LongArray = LongArray(scales.size) { i -> pageLost.sumOf { it[i].toLong() } }

    /** [lostInk] as a fraction of all ink on the analyzed pages; 0 if they are blank. */
    fun lostFraction(i: Int): Float =
        if (totalInk == 0L) 0f else lostInk[i].toFloat() / totalInk

    /** Number of analyzed pages that lose any ink at `scales[i]`. */
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
     * Where windows of [scale] typically sit: the median of the analyzed pages' best positions.
     * Pages with several equally good positions take the one nearest this, so the text doesn't
     * jump around as you page through. Only exact ties count: any position that kept less ink
     * could be slid toward the ink it cuts off, with blank space still on the other side.
     */
    suspend fun anchor(scale: Float): Anchor {
        val centroids = masks.filter { it.inkCount > 0 }.map { mask ->
            currentCoroutineContext().ensureActive()
            PaddedPage(mask, aspect).fit(scale).centroid()
        }
        return Anchor(
            u = centroids.map { it.u }.median() ?: 0.5f,
            v = centroids.map { it.v }.median() ?: 0.5f,
        )
    }

    companion object {
        const val MIN_SCALE = 0.3f
        const val SCALE_STEP = 0.005f

        /** Share of the paper's ink the automatic zoom may cut off. */
        const val AUTO_MAX_LOST = 0.005f

        /** How much looser than the tightest acceptable scale the automatic zoom is. */
        const val AUTO_MARGIN = 0.02f

        fun defaultScales(): FloatArray {
            val n = ((1f - MIN_SCALE) / SCALE_STEP).roundToInt() + 1
            return FloatArray(n) { 1f - it * SCALE_STEP }
        }

        /** Analyzes [masks]; checks for cancellation between pages. */
        suspend fun of(
            masks: List<InkMask>,
            aspect: Float,
            scales: FloatArray = defaultScales(),
        ): CropAnalysis {
            require(masks.isNotEmpty()) { "No pages" }
            require(aspect > 0f && aspect.isFinite()) { "Bad aspect ratio: $aspect" }
            require(scales.isNotEmpty() && scales.all { it > 0f && it <= 1f }) { "Bad scales" }
            val pageLost = Array(masks.size) { p ->
                currentCoroutineContext().ensureActive()
                // Only one page's summed-area table is alive at a time.
                val page = PaddedPage(masks[p], aspect)
                IntArray(scales.size) { i -> page.inkCount - page.maxInside(scales[i]) }
            }
            return CropAnalysis(masks, aspect, scales, pageLost)
        }
    }
}

/**
 * Places a window of [scale] on one page, padded to [aspect], so it leaves out as little ink as
 * possible; among equally good positions, the one nearest [anchor].
 */
fun placeWindow(mask: InkMask, aspect: Float, scale: Float, anchor: Anchor): CropWindow =
    PaddedPage(mask, aspect).fit(scale).window(anchor, scale)

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
        if (inkCount == 0) return 0
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

    /** The positions for one window size that leave out the least ink. */
    class Fit(private val page: PaddedPage, private val w: Int, private val h: Int) {
        private val rangeX = page.width - w
        private val rangeY = page.height - h
        private val columns = rangeX + 1

        // Positions packed as y * columns + x. A blank page has no preference: left empty.
        private val good: IntArray = if (page.inkCount == 0) IntArray(0) else {
            val values = IntArray(columns * (rangeY + 1))
            for (y in 0..rangeY) for (x in 0..rangeX) values[y * columns + x] = page.inside(x, y, w, h)
            val best = values.max()
            values.indices.filter { values[it] == best }.toIntArray()
        }

        private fun norm(pos: Double, range: Int) = if (range == 0) 0.5f else (pos / range).toFloat()

        /** The middle of the good positions. */
        fun centroid() = Anchor(
            u = norm(good.sumOf { (it % columns).toLong() }.toDouble() / good.size, rangeX),
            v = norm(good.sumOf { (it / columns).toLong() }.toDouble() / good.size, rangeY),
        )

        /** The good position nearest [anchor], as a window on the padded frame. */
        fun window(anchor: Anchor, scale: Float): CropWindow {
            val refX = anchor.u * rangeX
            val refY = anchor.v * rangeY
            val (x, y) = if (good.isEmpty()) {
                refX.roundToInt() to refY.roundToInt()
            } else {
                val pos = good.minBy {
                    val dx = it % columns - refX
                    val dy = it / columns - refY
                    dx * dx + dy * dy
                }
                pos % columns to pos / columns
            }
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
