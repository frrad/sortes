package dev.sortes.app.zoom

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A page's ink mask centred in a frame with the screen's [aspect] ratio, in whole cells, plus a
 * summed-area table for counting the ink in any window. Shared by [CropAnalysis] and [CropModel].
 */
internal class PaddedPage(mask: InkMask, private val aspect: Float) {
    val width: Int
    val height: Int
    val maskWidth = mask.width
    val maskHeight = mask.height
    val inkCount = mask.inkCount

    /** Where the mask sits in the frame: the padding left of and above it, in cells. */
    val offX: Int
    val offY: Int

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
        offX = (width - mask.width) / 2
        offY = (height - mask.height) / 2
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

    /** Ink in the [w] x [h] cells at ([x], [y]). */
    fun inside(x: Int, y: Int, w: Int, h: Int): Int {
        val stride = width + 1
        return sums[(y + h) * stride + x + w] - sums[y * stride + x + w] -
            sums[(y + h) * stride + x] + sums[y * stride + x]
    }

    /** The most ink any window of [scale] can keep. */
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

    /**
     * How far the ink a [w] x [h] window at ([x], [y]) keeps is from being centred in it: the
     * distance from the kept ink's bounding-box centre to the window's centre, in window widths
     * plus window heights. 0 when centred or when nothing is kept; at most 1.
     */
    fun offCentre(x: Int, y: Int, w: Int, h: Int): Double {
        if (inside(x, y, w, h) == 0) return 0.0
        // Blank rows (or columns) at one edge of the window: the most k with no ink in the first k.
        fun blank(n: Int, inked: (Int) -> Boolean): Int {
            var lo = 1
            var hi = n
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (inked(mid)) hi = mid else lo = mid + 1
            }
            return lo - 1
        }
        val top = blank(h) { inside(x, y, w, it) > 0 }
        val bottom = blank(h) { inside(x, y + h - it, w, it) > 0 }
        val left = blank(w) { inside(x, y, it, h) > 0 }
        val right = blank(w) { inside(x + w - it, y, it, h) > 0 }
        return abs(left - right) / (2.0 * w) + abs(top - bottom) / (2.0 * h)
    }

    /**
     * [offCentre] for every position of a [w] x [h] window at once, into [out] at
     * `y * (width - w + 1) + x`. Linear in the frame's size rather than a search per position:
     * for each window column it finds the next and previous inked row, and for each window row
     * the next and previous inked column.
     */
    fun offCentres(w: Int, h: Int, out: DoubleArray) {
        val cols = width - w + 1
        val rows = height - h + 1
        // Vertical: for each window column x, the rows with ink across [x, x + w).
        val next = IntArray(maxOf(width, height) + 1)
        val prev = IntArray(maxOf(width, height))
        for (x in 0 until cols) {
            next[height] = height
            for (r in height - 1 downTo 0) next[r] = if (inside(x, r, w, 1) > 0) r else next[r + 1]
            var last = -1
            for (r in 0 until height) {
                if (inside(x, r, w, 1) > 0) last = r
                prev[r] = last
            }
            for (y in 0 until rows) {
                val top = next[y]
                out[y * cols + x] = if (top >= y + h) Double.NaN else {
                    val bottom = prev[y + h - 1]
                    abs((top - y) - (y + h - 1 - bottom)) / (2.0 * h)
                }
            }
        }
        // Horizontal: for each window row y, the columns with ink across [y, y + h).
        for (y in 0 until rows) {
            next[width] = width
            for (c in width - 1 downTo 0) next[c] = if (inside(c, y, 1, h) > 0) c else next[c + 1]
            var last = -1
            for (c in 0 until width) {
                if (inside(c, y, 1, h) > 0) last = c
                prev[c] = last
            }
            for (x in 0 until cols) {
                val i = y * cols + x
                // Nothing kept: no content to centre.
                if (out[i].isNaN()) {
                    out[i] = 0.0
                    continue
                }
                val left = next[x]
                val right = prev[x + w - 1]
                out[i] += abs((left - x) - (x + w - 1 - right)) / (2.0 * w)
            }
        }
    }

    // The frame as a multiple of the page: what regionOn pads to, measured in page widths and
    // heights rather than in whole cells, so it doesn't inherit the cells' rounding.
    private val frameWidth = if (maskWidth.toFloat() / maskHeight < aspect) aspect * maskHeight / maskWidth else 1f
    private val frameHeight = if (maskWidth.toFloat() / maskHeight < aspect) 1f else maskWidth / (aspect * maskHeight)

    /**
     * The window of [scale] whose cells start at ([x], [y]), as the reader's [CropWindow]. It is
     * centred on those cells as they lie on the page: the cell frame centres the page in whole
     * cells, but the reader centres it exactly, so with an odd number of cells of padding the
     * two differ by half a cell, and converting via the cell frame would shift the crop by that.
     */
    fun windowAt(x: Int, y: Int, scale: Float): CropWindow {
        val centreX = (x - offX + windowWidth(scale) / 2f) / maskWidth
        val centreY = (y - offY + windowHeight(scale) / 2f) / maskHeight
        val left = (centreX + (frameWidth - 1) / 2) / frameWidth - scale / 2
        val top = (centreY + (frameHeight - 1) / 2) / frameHeight - scale / 2
        return CropWindow(left.coerceIn(0f, 1f - scale), top.coerceIn(0f, 1f - scale), scale)
    }

    /** The cell position whose [windowAt] is nearest [window]: its inverse. */
    fun positionOf(window: CropWindow): Pair<Int, Int> {
        val w = windowWidth(window.scale)
        val h = windowHeight(window.scale)
        val centreX = (window.left + window.scale / 2) * frameWidth - (frameWidth - 1) / 2
        val centreY = (window.top + window.scale / 2) * frameHeight - (frameHeight - 1) / 2
        val x = (centreX * maskWidth - w / 2f + offX).roundToInt().coerceIn(0, width - w)
        val y = (centreY * maskHeight - h / 2f + offY).roundToInt().coerceIn(0, height - h)
        return x to y
    }

    /** The mask cells a window of [scale] at ([x], [y]) covers. */
    fun cellsAt(x: Int, y: Int, scale: Float): CellRect =
        CellRect(x - offX, y - offY, x - offX + windowWidth(scale), y - offY + windowHeight(scale))
}
