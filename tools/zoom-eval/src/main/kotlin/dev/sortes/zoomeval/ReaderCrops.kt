package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.app.zoom.PageRenderer
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.PaperAnalysis
import dev.sortes.app.zoom.PlacedPaper
import dev.sortes.app.zoom.TextWeights
import dev.sortes.app.zoom.analyzePaper
import dev.sortes.app.zoom.renderMask
import dev.sortes.zoomeval.labeller.toMap

/**
 * A paper's crops exactly as the reader makes them: [analyzePaper] on [pages] (the reader's one
 * entry point), then every page placed ([PaperAnalysis.placeAll]); at the automatic zoom, or at
 * [scale] as a manual zoom. The only way the harness gets crops.
 */
suspend fun readerCrops(
    pages: PageRenderer,
    aspect: Float,
    config: CropConfig,
    scale: Float? = null,
): PlacedPaper {
    val analysis = analyzePaper(pages, aspect, config)
    return analysis.placeAll(if (scale == null) analysis.auto else analysis.cropsAt(scale))
}

/**
 * [pdf]'s pages, remembering every render of at most [maxPixels] pixels as its ink mask (a bit a
 * pixel: about 100 KB for a letter page's fine render), so a paper rendered once can be analysed
 * many times (a fit's trials, a benchmark) on the reader's code path. A remembered page is served
 * as black ink on white, which thresholds back to exactly the same mask. Not thread-safe.
 */
class MemoPages(private val pdf: PageRenderer, private val maxPixels: Int = Int.MAX_VALUE) :
    PageRenderer {
    private val masks = HashMap<Triple<Int, Int, Int>, java.util.BitSet>()

    override val pageCount: Int
        get() = pdf.pageCount

    override suspend fun pageSize(index: Int): PageSize = pdf.pageSize(index)

    override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray {
        if (width.toLong() * height > maxPixels) return pdf.renderPixels(index, width, height)
        val ink =
            masks.getOrPut(Triple(index, width, height)) {
                val mask = pdf.renderMask(index, width, height)
                java.util.BitSet(width * height).apply {
                    for (i in 0 until width * height) if (mask[i % width, i / width]) set(i)
                }
            }
        return IntArray(width * height) { if (ink[it]) BLACK else WHITE }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}

/** [weights] with `name=value` [overrides] applied, by [CropModelWeights] property name. */
fun CropModelWeights.with(overrides: Map<String, String>): CropModelWeights {
    var w = this
    for ((name, value) in overrides) {
        w =
            when (name) {
                "alpha" -> w.copy(alpha = value.toDouble())
                "beta" -> w.copy(beta = value.toDouble())
                "epsilon" -> w.copy(epsilon = value.toDouble())
                "allowance" -> w.copy(allowance = value.toDouble())
                "gamma" -> w.copy(gamma = value.toDouble())
                "delta" -> w.copy(delta = value.toDouble())
                "delta2" -> w.copy(delta2 = value.toDouble())
                "maxWindows" -> w.copy(maxWindows = value.toInt())
                "kappa" -> w.copy(kappa = value.toDouble())
                "blockGap" -> w.copy(blockGap = value.toInt())
                "splitInk" -> w.copy(splitInk = value.toDouble())
                "zeta" -> w.copy(zeta = value.toDouble())
                "autoMargin" -> w.copy(autoMargin = value.toDouble())
                else -> throw IllegalArgumentException("Unknown weight $name")
            }
    }
    return w
}

/** The names [with] understands. */
val WEIGHT_NAMES =
    setOf(
        "alpha",
        "beta",
        "epsilon",
        "allowance",
        "gamma",
        "delta",
        "delta2",
        "maxWindows",
        "kappa",
        "blockGap",
        "splitInk",
        "zeta",
        "autoMargin",
    )

/**
 * The text model's tunable weights ([TextWeights]), by property name: what [CropConfig.with]
 * understands besides [WEIGHT_NAMES].
 */
val TEXT_WEIGHT_NAMES = setOf("comfortable", "power", "floor", "elements", "lines")

/** Every weight a config override may name: the crop model's and the text model's. */
val CONFIG_WEIGHT_NAMES = WEIGHT_NAMES + TEXT_WEIGHT_NAMES

/**
 * [text] with `name=value` [overrides] applied, by [TextWeights] property name
 * ([TEXT_WEIGHT_NAMES]).
 */
fun TextWeights.with(overrides: Map<String, String>): TextWeights {
    var t = this
    for ((name, value) in overrides) {
        t =
            when (name) {
                "comfortable" -> t.copy(comfortable = value.toDouble())
                "power" -> t.copy(power = value.toDouble())
                "floor" -> t.copy(floor = value.toDouble())
                "elements" -> t.copy(elements = value.toDouble())
                "lines" -> t.copy(lines = value.toDouble())
                else -> throw IllegalArgumentException("Unknown text weight $name")
            }
    }
    return t
}

/**
 * This config with `name=value` [overrides] of either model's weights ([CONFIG_WEIGHT_NAMES]); the
 * name is kept.
 */
fun CropConfig.with(overrides: Map<String, String>): CropConfig {
    overrides.keys
        .firstOrNull { it !in CONFIG_WEIGHT_NAMES }
        ?.let { throw IllegalArgumentException("Unknown weight $it") }
    return copy(
        weights = weights.with(overrides.filterKeys { it in WEIGHT_NAMES }),
        text = text.with(overrides.filterKeys { it in TEXT_WEIGHT_NAMES }),
    )
}

/**
 * Every weight [CropConfig.with] can set, with its value: the crop model's ([toMap]) and the text
 * model's.
 */
fun CropConfig.weightValues(): Map<String, Double> =
    (weights.toMap() +
            mapOf(
                "comfortable" to text.comfortable,
                "power" to text.power,
                "floor" to text.floor,
                "elements" to text.elements,
                "lines" to text.lines,
            ))
        .toSortedMap()
