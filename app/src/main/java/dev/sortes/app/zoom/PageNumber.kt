package dev.sortes.app.zoom

/**
 * Ignores page numbers: a short, narrow line on its own at the very top or bottom of the page,
 * clear of everything else. The owner's labels (tools/zoom-eval/labels/ROUND-1.md) prefer
 * losing page numbers to letting them pull the window off-centre.
 *
 * Looking at the page's inked rows, the outermost run of them at the top or bottom is a page
 * number only if it is
 *  - right at the page edge: within the outer [NUMBER_MAX_EDGE] of the height,
 *  - short: at most [NUMBER_MAX_HEIGHT] of the page height,
 *  - narrow: its ink spans at most [NUMBER_MAX_WIDTH] of the page width,
 *  - clear of the rest: no ink at all in the [NUMBER_MIN_GAP] of the height next to it,
 *  - and not alone on the page.
 * A section heading, a last line of text, a footnote or a running head is too wide (three digits
 * at 10 pt are about 2.5% of a letter page's width; "1 Introduction" is 11%); a caption sits too
 * close.
 *
 * Not one of [IGNORABLE_INK_RULES] yet: it is being evaluated against the owner's labels.
 */
object PageNumberRule : IgnorableInkRule {
    override fun strip(mask: InkMask): InkMask {
        val runs = mask.inkedRowRuns()
        if (runs.size < 2) return mask
        val numbers = listOfNotNull(
            runs.first().takeIf { mask.isPageNumber(it, gap = runs[1].first - it.last - 1, top = true) },
            runs.last().takeIf { mask.isPageNumber(it, gap = it.first - runs[runs.size - 2].last - 1, top = false) },
        )
        if (numbers.isEmpty() || numbers.size == runs.size) return mask
        return InkMask(mask.width, mask.height, BooleanArray(mask.width * mask.height) { i ->
            val y = i / mask.width
            mask[i % mask.width, y] && numbers.none { y in it }
        })
    }
}

private fun InkMask.isPageNumber(run: IntRange, gap: Int, top: Boolean): Boolean {
    val edge = if (top) run.last + 1 else height - run.first
    if (edge > height * NUMBER_MAX_EDGE) return false
    if (run.last - run.first + 1 > maxOf(1f, height * NUMBER_MAX_HEIGHT)) return false
    if (gap < maxOf(2f, height * NUMBER_MIN_GAP)) return false
    val columns = (0 until width).filter { x -> run.any { y -> this[x, y] } }
    return columns.last() - columns.first() + 1 <= width * NUMBER_MAX_WIDTH
}

/** Runs of rows that hold ink, top to bottom. */
private fun InkMask.inkedRowRuns(): List<IntRange> {
    val runs = mutableListOf<IntRange>()
    var start = -1
    for (y in 0..height) {
        val inked = y < height && (0 until width).any { x -> this[x, y] }
        if (inked && start < 0) start = y
        if (!inked && start >= 0) {
            runs += start until y
            start = -1
        }
    }
    return runs
}

/** How far into the page, as a fraction of its height, a page number may reach from the edge. */
const val NUMBER_MAX_EDGE = 0.12f

/** Tallest a page number may be, as a fraction of the page height. */
const val NUMBER_MAX_HEIGHT = 0.03f

/** Widest a page number may be, as a fraction of the page width. */
const val NUMBER_MAX_WIDTH = 0.06f

/** Blank space a page number needs between it and the rest of the page, as a fraction of the height. */
const val NUMBER_MIN_GAP = 0.015f
