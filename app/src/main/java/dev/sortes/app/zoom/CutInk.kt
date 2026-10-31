package dev.sortes.app.zoom

/** A box around ink that a crop window leaves out: [region] in page points, [cells] of ink inside. */
data class CutInk(val region: Region, val cells: Int)

/**
 * The ink on a page of [page] size, seen through its analysis [mask], that a window covering
 * [cells] of the mask leaves out, grouped into boxes. Cut cells with at most [gap] blank cells
 * between them share a box, so a cut-off running head is one box rather than one per letter.
 *
 * Boxes cover only the cut part of the ink: a line straddling the window's edge gets a box from
 * the edge outwards. Given a crop's [PageCrop.cells], the cells of all boxes add up to the
 * ink the model counts as cut.
 */
fun cutInk(
    mask: InkMask,
    page: PageSize,
    cells: CellRect,
    gap: Int = CUT_INK_GAP,
): List<CutInk> {
    require(gap >= 0) { "gap must not be negative: $gap" }
    val w = mask.width
    val h = mask.height
    fun cut(x: Int, y: Int) =
        mask[x, y] && (x < cells.left || y < cells.top || x >= cells.right || y >= cells.bottom)

    val seen = BooleanArray(w * h)
    val boxes = mutableListOf<CutInk>()
    val queue = IntArray(w * h)
    for (start in 0 until w * h) {
        if (seen[start] || !cut(start % w, start / w)) continue
        seen[start] = true
        var head = 0
        var tail = 0
        queue[tail++] = start
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        while (head < tail) {
            val cell = queue[head++]
            val cx = cell % w
            val cy = cell / w
            if (cx < x0) x0 = cx
            if (cx > x1) x1 = cx
            if (cy < y0) y0 = cy
            if (cy > y1) y1 = cy
            for (ny in (cy - gap - 1).coerceAtLeast(0)..(cy + gap + 1).coerceAtMost(h - 1)) {
                for (nx in (cx - gap - 1).coerceAtLeast(0)..(cx + gap + 1).coerceAtMost(w - 1)) {
                    val n = ny * w + nx
                    if (!seen[n] && cut(nx, ny)) {
                        seen[n] = true
                        queue[tail++] = n
                    }
                }
            }
        }
        val sx = page.width / w
        val sy = page.height / h
        boxes += CutInk(Region(x0 * sx, y0 * sy, (x1 + 1 - x0) * sx, (y1 + 1 - y0) * sy), cells = tail)
    }
    return boxes
}

/** Blank cells allowed between cut ink in one box: about the gap between words or lines. */
const val CUT_INK_GAP = 2
