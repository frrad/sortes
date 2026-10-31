package dev.sortes.app.zoom

/**
 * Ignores margin stamps: the vertical line of text arXiv prints down the left margin of a paper's
 * first page ("arXiv:1706.03762v7 [cs.CL] 2 Aug 2023"). Left in, a stamp drags the crop window
 * toward the page edge. The rule looks at both side margins, so a stamp on the right is treated the
 * same way.
 *
 * A stamp is recognized purely by its shape, so the rule is conservative. Looking at the page's
 * inked columns, the outermost run of them on the left or right is a stamp only if it is
 * - right at the page edge: within the outer [STAMP_MAX_EDGE] of the width,
 * - thin: at most [STAMP_MAX_WIDTH] of the page width,
 * - clear of everything else: no ink at all in the [STAMP_MIN_GAP] of the width next to it, and
 *   something else on the page,
 * - tall: at least [STAMP_MIN_HEIGHT] of the page height and [STAMP_MIN_ELONGATION] times its
 *   width,
 * - one continuous line: ink on at least [STAMP_MIN_ROW_FILL] of the rows it spans, and an unbroken
 *   run of inked rows at least [STAMP_MIN_RUN] of the page height long. Line numbers fail the
 *   continuity tests (every line ends in blank rows, so no run is longer than a line) and usually
 *   sit nearer the text; margin notes and figures are too wide; anything touching the text block
 *   isn't separate. A rotated label right at the page edge, though, looks just like a stamp and is
 *   ignored too.
 */
object MarginStampRule : IgnorableInkRule {
    override fun strip(mask: InkMask): InkMask {
        val (left, right) = verdicts(mask)
        val bands = mask.inkedColumnRuns()
        val stamps =
            listOfNotNull(
                bands.firstOrNull()?.takeIf { left == null },
                bands.lastOrNull()?.takeIf { right == null },
            )
        if (stamps.isEmpty()) return mask
        return InkMask(
            mask.width,
            mask.height,
            BooleanArray(mask.width * mask.height) { i ->
                val x = i % mask.width
                mask[x, i / mask.width] && stamps.none { x in it }
            },
        )
    }

    /**
     * Why the outermost run of inked columns on the left and on the right of [mask] isn't a stamp,
     * or null for a side whose run is one. Checks run in the order of [NotAStamp], and the first
     * that fails is reported.
     */
    internal fun verdicts(mask: InkMask): Pair<NotAStamp?, NotAStamp?> {
        val bands = mask.inkedColumnRuns()
        if (bands.size < 2) return NotAStamp.NothingElse to NotAStamp.NothingElse
        val left = mask.check(bands.first(), gap = bands[1].first - bands.first().last - 1)
        val right =
            mask.check(bands.last(), gap = bands.last().first - bands[bands.size - 2].last - 1)
        // Two "stamps" and nothing else: there's no text block for them to stand apart from.
        if (left == null && right == null && bands.size == 2) {
            return NotAStamp.NothingElse to NotAStamp.NothingElse
        }
        return left to right
    }
}

/** The check that ruled a run of columns out as a stamp. */
internal enum class NotAStamp {
    NothingElse,
    AwayFromEdge,
    TooWide,
    TooClose,
    TooShort,
    TooSquat,
    Broken,
}

private fun InkMask.check(band: IntRange, gap: Int): NotAStamp? {
    val bandWidth = band.last - band.first + 1
    val edge = minOf(band.last + 1, width - band.first)
    if (edge > width * STAMP_MAX_EDGE) return NotAStamp.AwayFromEdge
    if (bandWidth > width * STAMP_MAX_WIDTH) return NotAStamp.TooWide
    if (gap < maxOf(MIN_GAP_CELLS.toFloat(), width * STAMP_MIN_GAP)) return NotAStamp.TooClose
    val inked = BooleanArray(height) { y -> band.any { x -> this[x, y] } }
    val top = inked.indexOfFirst { it }
    val span = inked.indexOfLast { it } - top + 1
    if (span < height * STAMP_MIN_HEIGHT) return NotAStamp.TooShort
    // Can only decide on landscape pages: on portrait ones, thin and tall already imply it.
    if (span < bandWidth * STAMP_MIN_ELONGATION) return NotAStamp.TooSquat
    var longest = 0
    var run = 0
    for (row in inked) {
        run = if (row) run + 1 else 0
        longest = maxOf(longest, run)
    }
    if (inked.count { it } < span * STAMP_MIN_ROW_FILL || longest < height * STAMP_MIN_RUN) {
        return NotAStamp.Broken
    }
    return null
}

/**
 * Runs of columns that hold ink, left to right. Single blank columns inside a run don't split it,
 * so a stamp rendered with a faint column in the middle still counts as one.
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

/**
 * Longest unbroken run of inked rows a stamp must have, as a fraction of the page height. A stamp's
 * letters run into each other for a word or more (9-35 cells on real pages at analysis size); line
 * numbers break after every line (2-3 cells).
 */
const val STAMP_MIN_RUN = 0.03f

/** Blank space a stamp needs between it and the rest of the page, as a fraction of the width. */
const val STAMP_MIN_GAP = 0.015f

/** Runs already merge across one blank column, so the gap between two runs is at least 2. */
private const val MIN_GAP_CELLS = 3
private const val MAX_BLANK_IN_RUN = 1
