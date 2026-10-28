package dev.sortes.app.zoom

/**
 * Ignores margin stamps: the vertical line of text arXiv prints down the left margin
 * of a paper's first page ("arXiv:1706.03762v7 [cs.CL] 2 Aug 2023"), and similar stamps other
 * servers put in either side margin. Left in, a stamp drags the crop window toward the page edge.
 *
 * A stamp is recognized purely by its shape, so the rule is conservative. Looking at the page's
 * inked columns, the outermost run of them on the left or right is a stamp only if it is
 *  - right at the page edge: within the outer [STAMP_MAX_EDGE] of the width,
 *  - thin: at most [STAMP_MAX_WIDTH] of the page width,
 *  - tall: at least [STAMP_MIN_HEIGHT] of the page height and [STAMP_MIN_ELONGATION] times its width,
 *  - one continuous line: ink on at least [STAMP_MIN_ROW_FILL] of the rows it spans,
 *  - clear of everything else: no ink at all in the [STAMP_MIN_GAP] of the width next to it,
 *    and something else on the page.
 * Line numbers fail the continuity test (they leave a gap between every line) and sit nearer the
 * text; margin notes and figures are too wide; anything touching the text block isn't separate.
 */
object MarginStampRule : IgnorableInkRule {
    override fun strip(mask: InkMask): InkMask {
        val bands = mask.inkedColumnRuns()
        if (bands.size < 2) return mask
        val stamps = listOf(bands.first(), bands.last()).filterIndexed { i, band ->
            val gap = if (i == 0) bands[1].first - band.last - 1 else band.first - bands[bands.size - 2].last - 1
            mask.isStamp(band, gap)
        }
        // Nothing but "stamps" means there's no text block for them to stand apart from.
        if (stamps.isEmpty() || stamps.size == bands.size) return mask
        return InkMask(mask.width, mask.height, BooleanArray(mask.width * mask.height) { i ->
            val x = i % mask.width
            mask[x, i / mask.width] && stamps.none { x in it }
        })
    }
}

private fun InkMask.isStamp(band: IntRange, gap: Int): Boolean {
    val bandWidth = band.last - band.first + 1
    val edge = minOf(band.last + 1, width - band.first)
    if (edge > width * STAMP_MAX_EDGE) return false
    if (bandWidth > width * STAMP_MAX_WIDTH) return false
    if (gap < maxOf(MIN_GAP_CELLS.toFloat(), width * STAMP_MIN_GAP)) return false
    val rows = (0 until height).filter { y -> band.any { x -> this[x, y] } }
    val span = rows.last() - rows.first() + 1
    if (span < height * STAMP_MIN_HEIGHT || span < bandWidth * STAMP_MIN_ELONGATION) return false
    return rows.size >= span * STAMP_MIN_ROW_FILL
}

/**
 * Runs of columns that hold ink, left to right. Single blank columns inside a run don't split
 * it, so a stamp rendered with a faint column in the middle still counts as one.
 */
private fun InkMask.inkedColumnRuns(): List<IntRange> {
    val inked = BooleanArray(width) { x -> (0 until height).any { y -> this[x, y] } }
    val runs = mutableListOf<IntRange>()
    var start = -1
    var end = -1
    for (x in 0 until width) {
        if (!inked[x]) continue
        if (start >= 0 && x - end - 1 > MAX_BLANK_IN_RUN) {
            runs += start..end
            start = -1
        }
        if (start < 0) start = x
        end = x
    }
    if (start >= 0) runs += start..end
    return runs
}

/** How far into the page, as a fraction of its width, a stamp may reach from the edge. */
const val STAMP_MAX_EDGE = 0.08f

/** Widest a stamp may be, as a fraction of the page width. */
const val STAMP_MAX_WIDTH = 0.05f

/** Shortest a stamp may be, as a fraction of the page height. */
const val STAMP_MIN_HEIGHT = 0.25f

/** How many times taller than wide a stamp must be. */
const val STAMP_MIN_ELONGATION = 6f

/** Share of the rows a stamp spans that must hold some of its ink. */
const val STAMP_MIN_ROW_FILL = 0.5f

/** Blank space a stamp needs between it and the rest of the page, as a fraction of the width. */
const val STAMP_MIN_GAP = 0.015f

private const val MIN_GAP_CELLS = 2
private const val MAX_BLANK_IN_RUN = 1
