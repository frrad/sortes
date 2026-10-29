package dev.sortes.zoomeval

import dev.sortes.app.zoom.CellRect
import dev.sortes.app.zoom.InkMask

/**
 * Where a crop's edges slice through ink, in cells: for each edge, the cells on the window's edge
 * row (or column) that are inked with ink directly beyond them, in the next row (or column)
 * outside. A text line the top or bottom edge cuts through shows up as many such cells; an edge
 * in the white space between lines, or just clear of the ink, has none. A measure only: the crop
 * model doesn't use it.
 */
data class EdgeSlices(val top: Int, val bottom: Int, val left: Int, val right: Int) {
    /** Whether the top or bottom edge cuts through at least [min] cells of ink: a sliced line. */
    fun slicesLine(min: Int = MIN_SLICE_CELLS) = top >= min || bottom >= min

    /** Whether the left or right edge cuts through at least [min] cells of ink. */
    fun slicesSide(min: Int = MIN_SLICE_CELLS) = left >= min || right >= min
}

/**
 * Fewest sliced cells along an edge that count as slicing a line: fewer is a stray stroke, such
 * as one line of a figure crossing the edge.
 */
const val MIN_SLICE_CELLS = 3

/** [EdgeSlices] of a window covering [cells] of [mask]; cells outside the mask (padding) are blank. */
fun edgeSlices(mask: InkMask, cells: CellRect): EdgeSlices {
    fun ink(x: Int, y: Int) = x in 0 until mask.width && y in 0 until mask.height && mask[x, y]
    fun across(y: Int, beyond: Int) = (cells.left until cells.right).count { x -> ink(x, y) && ink(x, beyond) }
    fun down(x: Int, beyond: Int) = (cells.top until cells.bottom).count { y -> ink(x, y) && ink(beyond, y) }
    return EdgeSlices(
        top = across(cells.top, cells.top - 1),
        bottom = across(cells.bottom - 1, cells.bottom),
        left = down(cells.left, cells.left - 1),
        right = down(cells.right - 1, cells.right),
    )
}
