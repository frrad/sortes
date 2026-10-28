package dev.sortes.app.zoom

/** Builds a synthetic page mask: white paper with inked rectangles. */
class TestPage(val width: Int, val height: Int) {
    private val ink = BooleanArray(width * height)

    /** Inks the rectangle from ([left], [top]) inclusive to ([right], [bottom]) exclusive. */
    fun rect(left: Int, top: Int, right: Int, bottom: Int) = apply {
        for (y in top until bottom) for (x in left until right) ink[y * width + x] = true
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
