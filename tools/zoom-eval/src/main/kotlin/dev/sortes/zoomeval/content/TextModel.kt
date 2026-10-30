package dev.sortes.zoomeval.content

import dev.sortes.app.zoom.CropAnalysis
import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.CropModel
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.ModelCrops
import dev.sortes.app.zoom.PaddedPage
import kotlin.math.abs

/**
 * The experimental "tuned+text" model, offline only: TUNED's rules and weights, except that
 *
 * 1. the zoom minimises TUNED's cost per page plus a [Legibility] cost that knows the paper's
 *    text size (measured from the page images), over every scale of the default grid in
 *    [minScale]..[maxScale] (then TUNED's margin);
 * 2. at that zoom, windows are placed with two extra page costs: [ElementLoss] (component
 *    omissions, weight [elements]) and [LineTruncation] (lines clipped at a side edge, weight
 *    [lines]). They don't choose the zoom.
 *
 * Every page is solved (no sampling).
 */
data class TextModel(
    val comfortable: Double = 28.0,
    val power: Double = 16.0,
    val floor: Double = 0.1,
    val elements: Double = 0.01,
    val lines: Double = 0.1,
    val factor: Int = 4,
    val minScale: Float = 0.60f,
    val maxScale: Float = 0.97f,
) {
    val config: CropConfig = CropConfig.TUNED
    val name: String get() = "tuned+text"

    /** What the labels record besides TUNED's weights. */
    fun parameters(): Map<String, Double> = sortedMapOf(
        "textComfortablePx" to comfortable, "textPower" to power, "textFloor" to floor,
        "elementWeight" to elements, "lineWeight" to lines, "fineFactor" to factor.toDouble(),
        "minScale" to minScale.toDouble(), "maxScale" to maxScale.toDouble(),
    )

    /** Body glyph height on screen at scale 1, in screen px, or null without text. */
    fun glyphPx(masks: List<InkMask>, fine: List<InkMask>, aspect: Float): Double? = TextSize.median(fine.indices.map { i ->
        TextSize.glyphHeight(fine[i])?.let { it / factor / PaddedPage(masks[i], aspect).width * SCREEN_WIDTH }
    })

    /** The crops of pages [rendered] (as rendered, before rules), given their [fine] renders at [factor]×. */
    suspend fun crops(rendered: List<InkMask>, fine: List<InkMask>, aspect: Float): ModelCrops {
        require(rendered.size == fine.size)
        val masks = config.prepare(rendered)
        val weights = config.weights
        val legibility = Legibility(weights.zeta, comfortable, power, floor)
        val k = glyphPx(masks, fine, aspect)
        val scales = CropAnalysis.defaultScales().filter { it in minScale - 1e-6f..maxScale + 1e-6f }
        val plain = CropModel(aspect, weights).prepare(masks)
        val chosen = if (k == null) {
            plain.auto().scale // no text to measure: TUNED's zoom
        } else {
            val best = scales.minBy { s -> plain.solve(s).let { it.total / it.pages.size } + legibility.cost(s.toDouble(), k) }
            val loosened = best + weights.autoMargin.toFloat()
            CropAnalysis.defaultScales().minBy { abs(it - loosened) }
        }
        if (elements == 0.0 && lines == 0.0) return plain.solve(chosen)
        val element = masks.indices.map { ElementLoss.of(fine[it], rendered[it], masks[it]) }
        val line = masks.indices.map { LineTruncation.of(fine[it], rendered[it], masks[it]) }
        val byMask = masks.indices.associateBy { masks[it] }
        val placed = CropModel(aspect, weights, additionalPageCost = { mask, cells ->
            val i = byMask.getValue(mask)
            elements * element[i].lost(cells) + lines * line[i].cost(cells)
        })
        return placed.solve(masks, chosen)
    }
}
