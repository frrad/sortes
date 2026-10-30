package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.PageRenderer
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.PaperAnalysis
import dev.sortes.app.zoom.PlacedPaper
import dev.sortes.app.zoom.analyzePaper
import dev.sortes.app.zoom.renderMask

/**
 * A paper's crops exactly as the reader makes them: [analyzePaper] on [pages] (the reader's one
 * entry point), then every page placed ([PaperAnalysis.placeAll]); at the automatic zoom, or at
 * [scale] as a manual zoom. The only way the harness gets crops.
 */
suspend fun readerCrops(pages: PageRenderer, aspect: Float, config: CropConfig, scale: Float? = null): ReaderCrops {
    val analysis = analyzePaper(pages, aspect, config)
    val crops = if (scale == null) analysis.auto else analysis.cropsAt(scale)
    return ReaderCrops(analysis, analysis.placeAll(crops))
}

class ReaderCrops(val analysis: PaperAnalysis, placed: PlacedPaper) {
    val crops = placed.crops

    /** Each page's mask as the model saw it (the config's ignorable ink stripped). */
    val masks: List<InkMask> = placed.masks
}

/**
 * [pdf]'s pages, remembering every render of at most [maxPixels] pixels as its ink mask (a bit a
 * pixel: about 100 KB for a letter page's fine render), so a paper rendered once can be analysed
 * many times (a fit's trials, a benchmark) on the reader's code path. A remembered page is
 * served as black ink on white, which thresholds back to exactly the same mask. Not thread-safe.
 */
class MemoPages(private val pdf: PageRenderer, private val maxPixels: Int = Int.MAX_VALUE) : PageRenderer {
    private val masks = HashMap<Triple<Int, Int, Int>, java.util.BitSet>()

    override val pageCount: Int get() = pdf.pageCount

    override suspend fun pageSize(index: Int): PageSize = pdf.pageSize(index)

    override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray {
        if (width.toLong() * height > maxPixels) return pdf.renderPixels(index, width, height)
        val ink = masks.getOrPut(Triple(index, width, height)) {
            val mask = pdf.renderMask(index, width, height)
            java.util.BitSet(width * height).apply { for (i in 0 until width * height) if (mask[i % width, i / width]) set(i) }
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
        w = when (name) {
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
val WEIGHT_NAMES = setOf(
    "alpha", "beta", "epsilon", "allowance", "gamma", "delta", "delta2", "maxWindows",
    "kappa", "blockGap", "splitInk", "zeta", "autoMargin",
)
