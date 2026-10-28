package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropAnalysis
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.placeWindow

/** One way of choosing a paper's crops, compared side by side in the contact sheets. */
enum class Method(val label: String, val stripsStamps: Boolean) {
    /** What the reader does on main: CropAnalysis's auto scale and anchored per-page placement. */
    MAIN("main", false),

    /** The reader with PR #20's ignorable-ink rules (the arXiv margin stamp) applied first. */
    MAIN_STAMP("main + stamp rule", true),

    /** The crop model (SPEC.md) on the raw masks. */
    MODEL("model", false),

    /** The crop model with PR #20's ignorable-ink rules applied first. */
    MODEL_STAMP("model + stamp rule", true),
}

/**
 * Crops for every page of a paper by [method], scored with [model]'s cost. [masks] are the pages
 * as the method sees them: with ignorable ink already stripped if [Method.stripsStamps].
 */
suspend fun cropsFor(method: Method, masks: List<InkMask>, aspect: Float, model: CropModel): Crops =
    when (method) {
        Method.MAIN, Method.MAIN_STAMP -> {
            // As ReaderScreen does: one analysis, its auto scale, and the anchor for that scale.
            val analysis = CropAnalysis.of(masks, aspect)
            val scale = analysis.autoScale
            val anchor = analysis.anchor(scale)
            model.score(masks, masks.map { placeWindow(it, aspect, scale, anchor) })
        }
        Method.MODEL, Method.MODEL_STAMP -> model.crops(masks)
    }
