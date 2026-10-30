package dev.sortes.app.zoom

/**
 * Ignores page furniture: page numbers, running heads and running feet. The owner's labels
 * (tools/zoom-eval/labels/ROUND-1.md, ROUND-2.md) prefer losing them to letting them pull the
 * window off-centre, five papers over.
 *
 * Furniture is the outermost block of inked rows at the top or bottom of a page (a line, or a line
 * and a rule close to it, like DiLoCo's running head) that is
 *  - right at the page edge: within the outer [FURNITURE_MAX_EDGE] of the height,
 *  - short: at most [FURNITURE_MAX_HEIGHT] of the page height,
 *  - clear of the rest: no ink at all in the [FURNITURE_MIN_GAP] of the height next to it,
 * and either narrow (at most [NUMBER_MAX_WIDTH] of the width: a page number) or at a height that
 * recurs on at least [FURNITURE_MIN_REPEATS] of the paper's pages (a running head or foot).
 * A one-off line at the edge, like a title page's conference footer, is kept. A page that is only
 * furniture (a blank page with its number) ends up blank, and takes the paper's usual window.
 *
 * The recurring heights come from a paper's pages ([of]); the rule then strips any page,
 * including ones it wasn't built from.
 */
class PageFurniture private constructor(
    /** Centres of recurring top and bottom lines, as fractions of the page height. */
    private val top: List<Float>,
    private val bottom: List<Float>,
) : IgnorableInkRule {

    override fun strip(mask: InkMask): InkMask {
        val (topLine, bottomLine) = mask.edgeLines()
        val furniture = listOfNotNull(
            topLine?.takeIf { mask.isNarrow(it) || recurs(top, mask, it) },
            bottomLine?.takeIf { mask.isNarrow(it) || recurs(bottom, mask, it) },
        )
        if (furniture.isEmpty()) return mask
        return InkMask(mask.width, mask.height, BooleanArray(mask.width * mask.height) { i ->
            val y = i / mask.width
            mask[i % mask.width, y] && furniture.none { y in it }
        })
    }

    private fun recurs(centres: List<Float>, mask: InkMask, line: IntRange) =
        centres.any { kotlin.math.abs(it - mask.centreOf(line)) <= FURNITURE_REPEAT_TOLERANCE }

    companion object {
        /** Furniture as it recurs on [masks], the pages of one paper. */
        fun of(masks: List<InkMask>): PageFurniture {
            val lines = masks.map { mask -> mask.edgeLines().let { (t, b) -> t?.let { mask.centreOf(it) } to b?.let { mask.centreOf(it) } } }
            val needed = maxOf(FURNITURE_MIN_REPEATS_ABSOLUTE.toFloat(), masks.size * FURNITURE_MIN_REPEATS)
            fun recurring(centres: List<Float>) = centres.filter { c ->
                centres.count { kotlin.math.abs(it - c) <= FURNITURE_REPEAT_TOLERANCE } >= needed
            }
            return PageFurniture(recurring(lines.mapNotNull { it.first }), recurring(lines.mapNotNull { it.second }))
        }

        /** No recurring lines known: only narrow page numbers go. */
        val NUMBERS_ONLY = PageFurniture(emptyList(), emptyList())
    }
}

/** A paper's pages without their ignorable ink and their furniture. */
fun List<InkMask>.withoutFurniture(): List<InkMask> {
    val furniture = PageFurniture.of(this)
    return map { furniture.strip(it) }
}

/** The outermost top and bottom lines that could be furniture by their shape and place. */
private fun InkMask.edgeLines(): Pair<IntRange?, IntRange?> {
    val runs = inkedRowRuns()
    if (runs.isEmpty()) return null to null
    val top = edgeBlock(runs, top = true)
    val bottom = edgeBlock(runs.reversed(), top = false)
    // One block can't be both.
    return top to bottom.takeIf { it != top }
}

/**
 * The block at one edge, from [runs] ordered outermost first: the outermost run and any runs
 * close inside it, if together they're short, at the edge, and clear of what comes next.
 */
private fun InkMask.edgeBlock(runs: List<IntRange>, top: Boolean): IntRange? {
    val minGap = maxOf(2f, height * FURNITURE_MIN_GAP)
    val maxHeight = maxOf(1f, height * FURNITURE_MAX_HEIGHT)
    fun gapBetween(a: IntRange, b: IntRange) = if (top) b.first - a.last - 1 else a.first - b.last - 1
    var block = runs.first()
    var i = 1
    while (i < runs.size && gapBetween(block, runs[i]) < minGap) {
        block = minOf(block.first, runs[i].first)..maxOf(block.last, runs[i].last)
        if (block.last - block.first + 1 > maxHeight) return null
        i++
    }
    if (block.last - block.first + 1 > maxHeight) return null
    val edge = if (top) block.last + 1 else height - block.first
    if (edge > height * FURNITURE_MAX_EDGE) return null
    // A lone block has nothing to be close to.
    return block.takeIf { i == runs.size || gapBetween(block, runs[i]) >= minGap }
}

private fun InkMask.isNarrow(run: IntRange): Boolean {
    val columns = (0 until width).filter { x -> run.any { y -> this[x, y] } }
    return columns.last() - columns.first() + 1 <= width * NUMBER_MAX_WIDTH
}

private fun InkMask.centreOf(run: IntRange): Float = (run.first + run.last + 1) / 2f / height

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

/** How far into the page, as a fraction of its height, furniture may reach from the edge: PDLT's A4
 * book pages put their numbers 16% up. */
const val FURNITURE_MAX_EDGE = 0.18f

/** Tallest a line of furniture may be, as a fraction of the page height. */
const val FURNITURE_MAX_HEIGHT = 0.03f

/** Blank space furniture needs between it and the rest of the page, as a fraction of the height. */
const val FURNITURE_MIN_GAP = 0.015f

/**
 * Widest a page number may be, as a fraction of the page width: three digits at 10 pt are about
 * 2.5% of a letter page; a heading like "1 Introduction" is 11%.
 */
const val NUMBER_MAX_WIDTH = 0.06f

/** Share of a paper's pages a line must recur on, at the same height, to be a running head or foot. */
const val FURNITURE_MIN_REPEATS = 0.3f

/** ...and at least this many pages. */
const val FURNITURE_MIN_REPEATS_ABSOLUTE = 3

/** How close (fraction of page height) lines must be to count as the same running line. */
const val FURNITURE_REPEAT_TOLERANCE = 0.01f
