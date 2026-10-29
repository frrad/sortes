package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropAnalysis
import dev.sortes.app.zoom.CropModel
import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.MAX_ANALYZED_PAGES
import dev.sortes.app.zoom.SampledPaper
import dev.sortes.app.zoom.samplePages
import dev.sortes.app.zoom.ModelCrops
import dev.sortes.app.zoom.placeWindow
import java.io.File

/** How a column chooses crops. */
enum class Algorithm {
    /** The reader before the crop model: CropAnalysis's auto scale and anchored placement. */
    ANCHOR,

    /** The crop model (CropModel) at its automatic scale, solved on every page. */
    MODEL,

    /**
     * As the reader does it: the model solved on [samplePages]' sample (at most
     * [MAX_ANALYZED_PAGES]), other pages placed by [SampledPaper.cropFor]. The same as [MODEL]
     * for papers of up to 60 pages.
     */
    READER,
}

/**
 * One column of the contact sheets: an algorithm, the ink threshold its masks use (null: the
 * reader's default, [InkMask.fromArgb]'s paper-relative one), and weight overrides for the model.
 * Every column's masks go through the reader's ignorable-ink rules.
 */
data class Column(
    val label: String,
    val algorithm: Algorithm,
    val threshold: Int?,
    val overrides: Map<String, String>,
)

/**
 * Reads columns.tsv: label, algorithm (anchor or model), threshold (a luminance, or "default"),
 * and weight overrides as `name=value` pairs separated by commas (or "-"); '#' starts a comment.
 */
fun readColumns(file: File): List<Column> = file.readLines()
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .map { line ->
        val f = line.split('\t').map { it.trim() }
        require(f.size == 4) { "Bad column line: $line" }
        Column(
            label = f[0],
            algorithm = Algorithm.valueOf(f[1].uppercase()),
            threshold = if (f[2] == "default") null else f[2].toInt(),
            overrides = if (f[3] == "-") emptyMap() else f[3].split(',').associate {
                require('=' in it) { "Bad override $it in: $line" }
                it.substringBefore('=').trim() to it.substringAfter('=').trim()
            },
        )
    }

/** [weights] with `name=value` [overrides] applied, by [CropModelWeights] property name. */
fun CropModelWeights.with(overrides: Map<String, String>): CropModelWeights {
    var w = this
    for ((name, value) in overrides) {
        w = when (name) {
            "alpha" -> w.copy(alpha = value.toDouble())
            "beta" -> w.copy(beta = value.toDouble())
            "epsilon" -> w.copy(epsilon = value.toDouble())
            "allowance" -> w.copy(allowance = value.toDouble())
            "gamma" -> w.copy(gamma = value.toDouble())
            "delta" -> w.copy(delta = value.toDouble())
            "delta2" -> w.copy(delta2 = value.toDouble())
            "maxWindows" -> w.copy(maxWindows = value.toInt())
            else -> throw IllegalArgumentException("Unknown weight $name")
        }
    }
    return w
}

/** The names [with] understands. */
val WEIGHT_NAMES = setOf("alpha", "beta", "epsilon", "allowance", "gamma", "delta", "delta2", "maxWindows")

/**
 * A column's crops for a paper whose pages (already stripped of ignorable ink) are [masks],
 * scored with [model]'s cost. Anchor placement is scored with the model's cost too, so the
 * columns compare on one scale.
 */
suspend fun cropsFor(algorithm: Algorithm, masks: List<InkMask>, model: CropModel): ModelCrops =
    when (algorithm) {
        Algorithm.ANCHOR -> {
            // As the reader did: one analysis, its auto scale, and the anchor for that scale.
            val analysis = CropAnalysis.of(masks, model.aspect)
            val scale = analysis.autoScale
            val anchor = analysis.anchor(scale)
            model.score(masks, masks.map { placeWindow(it, model.aspect, scale, anchor) })
        }
        Algorithm.MODEL -> model.auto(masks)
        Algorithm.READER -> {
            val sample = samplePages(masks.size, MAX_ANALYZED_PAGES)
            val paper = SampledPaper(model, sample.map { masks[it] }, sample)
            val crops = paper.auto()
            // Every page's crop, with any page's own window added as a window of its own.
            val windows = crops.windows.toMutableList()
            val pages = masks.indices.map { i ->
                val crop = paper.cropFor(i, crops, masks[i].takeIf { paper.sampled(i) < 0 })
                if (crop.windowIndex != CropModel.OWN_WINDOW) crop else {
                    windows += crop.window
                    crop.copy(windowIndex = windows.lastIndex)
                }
            }
            ModelCrops(crops.scale, windows, pages, model.weights, masks.sumOf { it.inkCount.toLong() })
        }
    }
