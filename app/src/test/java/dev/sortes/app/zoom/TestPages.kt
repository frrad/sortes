package dev.sortes.app.zoom

import kotlin.math.roundToInt

/** Builds a synthetic page mask: white paper with inked rectangles. */
class TestPage(val width: Int, val height: Int) {
    private val ink = BooleanArray(width * height)

    /** Inks the rectangle from ([left], [top]) inclusive to ([right], [bottom]) exclusive. */
    fun rect(left: Int, top: Int, right: Int, bottom: Int) = apply {
        for (y in top until bottom) for (x in left until right) ink[y * width + x] = true
    }

    /** Clears the rectangle from ([left], [top]) inclusive to ([right], [bottom]) exclusive. */
    fun clear(left: Int, top: Int, right: Int, bottom: Int) = apply {
        for (y in top until bottom) for (x in left until right) ink[y * width + x] = false
    }

    fun mask() = InkMask(width, height, ink.copyOf())
}

/** A 60x80 page (exactly 3:4) with a 40x60 text block at (10, 10). */
fun textPage() = TestPage(60, 80).rect(10, 10, 50, 70)

/** True if [window] on a page of [width] x [height] cells, padded to [aspect], covers the rect. */
fun CropWindow.covers(
    width: Int,
    height: Int,
    aspect: Float,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
): Boolean {
    val r = regionOn(PageSize(width.toFloat(), height.toFloat()), aspect)
    val slack = 0.5f  // the analysis works in whole cells
    return r.left <= left + slack && r.top <= top + slack &&
        r.left + r.width >= right - slack && r.top + r.height >= bottom - slack
}

/** A mask saved as text, one row per line: `#` for ink, `.` for paper. */
fun maskFixture(name: String): InkMask {
    val text = requireNotNull(TestPage::class.java.getResource("/zoom/$name")) { "missing fixture $name" }
        .readText()
    val rows = text.lines().filter { it.isNotEmpty() }
    val ink = BooleanArray(rows.size * rows[0].length)
    rows.forEachIndexed { y, row ->
        require(row.length == rows[0].length) { "Ragged row $y in $name" }
        row.forEachIndexed { x, c -> ink[y * row.length + x] = c == '#' }
    }
    return InkMask(rows[0].length, rows.size, ink)
}

/**
 * Ink on a page that a window leaves out, beyond each of its edges. Ink past a corner counts
 * toward both edges, but once in [total].
 */
data class Clipped(val left: Int, val top: Int, val right: Int, val bottom: Int, val total: Int)

/** The ink on [mask], padded to [aspect], that this window leaves out, counted in whole cells. */
fun CropWindow.clipped(mask: InkMask, aspect: Float): Clipped {
    val r = regionOn(PageSize(mask.width.toFloat(), mask.height.toFloat()), aspect)
    // Windows are placed in whole cells and then sized exactly, which moves each edge by less
    // than half a cell: rounding recovers the cells.
    val left = r.left.roundToInt()
    val top = r.top.roundToInt()
    val right = (r.left + r.width).roundToInt()
    val bottom = (r.top + r.height).roundToInt()
    var l = 0
    var t = 0
    var rt = 0
    var b = 0
    var total = 0
    for (y in 0 until mask.height) for (x in 0 until mask.width) {
        if (!mask[x, y]) continue
        if (x < left) l++
        if (y < top) t++
        if (x >= right) rt++
        if (y >= bottom) b++
        if (x < left || y < top || x >= right || y >= bottom) total++
    }
    return Clipped(l, t, rt, b, total)
}

/**
 * A greyscale page saved as text (`scripts/render-ink-mask.py --gray`): one row per line, each
 * pixel two hex digits of luminance. Returns opaque ARGB pixels, as `Bitmap.getPixels` would.
 */
fun grayFixture(name: String): Triple<IntArray, Int, Int> {
    val text = requireNotNull(TestPage::class.java.getResource("/zoom/$name")) { "missing fixture $name" }
        .readText()
    val rows = text.lines().filter { it.isNotEmpty() }
    val width = rows[0].length / 2
    val pixels = IntArray(rows.size * width)
    rows.forEachIndexed { y, row ->
        require(row.length == width * 2) { "Ragged row $y in $name" }
        for (x in 0 until width) {
            val l = row.substring(2 * x, 2 * x + 2).toInt(16)
            pixels[y * width + x] = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
        }
    }
    return Triple(pixels, width, rows.size)
}
