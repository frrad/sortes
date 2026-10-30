package dev.sortes.zoomeval.fit

import dev.sortes.app.zoom.CellRect
import dev.sortes.app.zoom.CropWindow
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.ModelCrops
import dev.sortes.app.zoom.PaddedPage
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.padded
import dev.sortes.app.zoom.regionOn
import dev.sortes.zoomeval.labeller.PaperLabels
import dev.sortes.zoomeval.labeller.Point
import dev.sortes.zoomeval.labeller.Verdict
import java.io.File
import kotlin.math.hypot

/**
 * How labels turn into scores; see tools/zoom-eval/labels/README.md and `fit` in the zoom-eval
 * README. Lower is better; 1 unit is about "noticeably wrong once".
 */
object ScoreWeights {
    /** Points of window-centre error per unit, for a high-confidence target. */
    const val POINTS_PER_UNIT = 10.0

    /** Units per percent of a page's ink that the ideal crop keeps but the model cuts. */
    const val KEEP_UNITS_PER_PERCENT = 1.0

    /** Units per 0.01 of scale between the model's zoom and the paper's target. */
    const val ZOOM_UNITS_PER_HUNDREDTH = 2.0

    /** Confidence of each kind of position target. */
    const val HIGH = 1.0
    const val MEDIUM = 0.5
    const val LOW = 0.25

    /** Weight of a good page's own crop as a target: it should stay put. */
    const val GOOD = 0.5

    /** Weight of a zoom target the owner didn't set or affirm (the zoom they saw and left). */
    const val IMPLICIT_ZOOM = 0.5

    /** Scale a "tighter" or "looser" vote moves an unset zoom target by. */
    const val ZOOM_VOTE = 0.01

    /** Moves at least this far (points) read as deliberate when the table doesn't say. */
    const val DELIBERATE_MOVE = 8.0
}

/** One row of labels/interpretation.tsv. */
data class Interpretation(
    val move: String? = null,
    val keep: Boolean = false,
    val verdict: Verdict? = null,
    val zoom: String? = null,
)

/** Reads labels/interpretation.tsv: paper to page (0 for the paper's row) to its reading. */
fun readInterpretations(file: File): Map<String, Map<Int, Interpretation>> {
    if (!file.exists()) return emptyMap()
    val out = HashMap<String, MutableMap<Int, Interpretation>>()
    for (line in file.readLines()) {
        if (line.isBlank() || line.startsWith("#")) continue
        val f = line.split('\t').map { it.trim() }
        require(f.size >= 6) { "Bad interpretation line: $line" }
        fun dash(s: String) = s.takeIf { it != "-" && it.isNotEmpty() }
        val move = dash(f[2])?.also { require(it in setOf("high", "medium", "low", "none")) { "Bad move $it: $line" } }
        val zoom = dash(f[5])?.also { require(it in setOf("affirmed", "tighter", "looser")) { "Bad zoom $it: $line" } }
        require(f[3] in setOf("yes", "no", "-")) { "Bad keep ${f[3]}: $line" }
        out.getOrPut(f[0]) { HashMap() }[if (f[1] == "*") 0 else f[1].toInt()] =
            Interpretation(move, f[3] == "yes", dash(f[4])?.let { Verdict.of(it) }, zoom)
    }
    return out
}

/** What one labelled page asks of the model. */
data class PageTarget(
    val page: Int,
    /** Where the window's centre should be, in page points, and how much that counts. */
    val centre: Point?,
    val centreWeight: Double,
    /** The ideal crop's centre, for the ink it keeps, and how much keeping that ink counts. */
    val keepCentre: Point,
    val keepWeight: Double,
    val why: String,
)

/** What a labelled paper asks of the model. */
data class PaperTargets(
    val paper: String,
    val scale: Double,
    val scaleWeight: Double,
    val pages: List<PageTarget>,
)

/**
 * The targets [labels] set, read through [interpretation] (page 0 is the paper's row), given
 * the centres of the crops the owner judged ([judgedCentres], page points, by page index).
 */
fun targets(
    labels: PaperLabels,
    interpretation: Map<Int, Interpretation>,
    judgedScale: Double,
    judgedCentres: List<Point>,
): PaperTargets {
    val paperReading = interpretation[0]
    val zoomVotes = (listOf(paperReading) + labels.pages.keys.map { interpretation[it] })
        .sumOf { r -> when (r?.zoom) { "tighter" -> -1.0; "looser" -> 1.0; else -> 0.0 } }
    val affirmed = (listOf(paperReading) + labels.pages.keys.map { interpretation[it] }).any { it?.zoom == "affirmed" }
    val (scale, scaleWeight) = when {
        labels.idealScale != null -> labels.idealScale to 1.0
        affirmed -> judgedScale to 1.0
        else -> judgedScale + ScoreWeights.ZOOM_VOTE * zoomVotes to ScoreWeights.IMPLICIT_ZOOM
    }
    val pages = labels.pages.mapNotNull { (n, label) ->
        if (n > judgedCentres.size) return@mapNotNull null
        val reading = interpretation[n]
        val judged = judgedCentres[n - 1]
        val moved = label.idealCentre?.takeIf { hypot(it.x - judged.x, it.y - judged.y) > 0.05 }
        val confidence = when (reading?.move) {
            "high" -> ScoreWeights.HIGH
            "medium" -> ScoreWeights.MEDIUM
            "low" -> ScoreWeights.LOW
            "none" -> 0.0
            else -> if (moved == null) 0.0
            else if (hypot(moved.x - judged.x, moved.y - judged.y) >= ScoreWeights.DELIBERATE_MOVE) ScoreWeights.HIGH
            else ScoreWeights.LOW
        }
        val good = (label.verdict ?: reading?.verdict) == Verdict.GOOD
        val keep = reading?.keep == true
        when {
            moved != null && confidence > 0 -> PageTarget(
                n, moved, confidence, moved, if (keep) 1.0 else confidence,
                "moved (${"%.0f".format(confidence * 100)}%)" + if (keep) ", keep" else "",
            )
            good -> PageTarget(n, judged, ScoreWeights.GOOD, judged, if (keep) 1.0 else ScoreWeights.GOOD, "good: stay put")
            keep -> PageTarget(n, null, 0.0, judged, 1.0, "keep")
            else -> null
        }
    }
    return PaperTargets(labels.paper, scale, scaleWeight, pages)
}

/** One page's score against its target. */
data class PageScore(
    val page: Int,
    val why: String,
    /** Window-centre error in points, if the page has a position target. */
    val centreError: Double?,
    /** Percent of the page's ink the ideal crop keeps that the model cuts. */
    val keepCutPercent: Double,
    val positionUnits: Double,
    val keepUnits: Double,
) {
    val units: Double get() = positionUnits + keepUnits
}

/** A paper's score: its zoom against the target, and its labelled pages. */
data class PaperScore(
    val paper: String,
    val scale: Double,
    val targetScale: Double,
    val zoomUnits: Double,
    val pages: List<PageScore>,
) {
    val pageUnits: Double get() = pages.sumOf { it.units }
    val units: Double get() = zoomUnits + pageUnits
}

/** A window of [scale] centred at [centre] (page points) on a page of [size], as the reader's CropWindow. */
fun windowAt(centre: Point, scale: Double, size: PageSize, aspect: Float): CropWindow {
    val frame = size.padded(aspect)
    val s = scale.toFloat()
    val left = ((centre.x + (frame.width - size.width) / 2) / frame.width - s / 2).toFloat()
    val top = ((centre.y + (frame.height - size.height) / 2) / frame.height - s / 2).toFloat()
    return CropWindow(left.coerceIn(0f, 1f - s), top.coerceIn(0f, 1f - s), s)
}

/** The centre of [window] on a page of [size], in page points. */
fun centreOf(window: CropWindow, size: PageSize, aspect: Float): Point {
    val r = window.regionOn(size, aspect)
    return Point((r.left + r.width / 2).toDouble(), (r.top + r.height / 2).toDouble())
}

/** The mask cells [window] covers, as the model counts them. */
fun cellsOf(window: CropWindow, mask: InkMask, aspect: Float): CellRect {
    val page = PaddedPage(mask, aspect)
    val (x, y) = page.positionOf(window)
    return page.cellsAt(x, y, window.scale)
}

/** Ink in [mask] inside [keep] but outside [shown]. */
fun keptButCut(mask: InkMask, keep: CellRect, shown: CellRect): Int {
    var n = 0
    for (y in maxOf(0, keep.top) until minOf(mask.height, keep.bottom)) {
        for (x in maxOf(0, keep.left) until minOf(mask.width, keep.right)) {
            if (mask[x, y] && (x < shown.left || x >= shown.right || y < shown.top || y >= shown.bottom)) n++
        }
    }
    return n
}

/** Scores [crops] of a paper (every page, in order) against its [targets]. */
fun score(targets: PaperTargets, crops: ModelCrops, masks: List<InkMask>, sizes: List<PageSize>, aspect: Float): PaperScore {
    val pages = targets.pages.map { t ->
        val i = t.page - 1
        val crop = crops.pages[i]
        val centre = centreOf(crop.window, sizes[i], aspect)
        val error = t.centre?.let { hypot(centre.x - it.x, centre.y - it.y) }
        // The ideal crop: at the paper's target zoom, centred where the owner wants it.
        val ideal = cellsOf(windowAt(t.keepCentre, targets.scale, sizes[i], aspect), masks[i], aspect)
        val ink = masks[i].inkCount
        val cutPercent = if (ink == 0) 0.0 else 100.0 * keptButCut(masks[i], ideal, crop.cells) / ink
        PageScore(
            page = t.page,
            why = t.why,
            centreError = error,
            keepCutPercent = cutPercent,
            positionUnits = if (error == null) 0.0 else t.centreWeight * error / ScoreWeights.POINTS_PER_UNIT,
            keepUnits = t.keepWeight * cutPercent * ScoreWeights.KEEP_UNITS_PER_PERCENT,
        )
    }
    val zoomUnits = targets.scaleWeight * kotlin.math.abs(crops.scale - targets.scale) / 0.01 * ScoreWeights.ZOOM_UNITS_PER_HUNDREDTH
    return PaperScore(targets.paper, crops.scale.toDouble(), targets.scale, zoomUnits, pages)
}
