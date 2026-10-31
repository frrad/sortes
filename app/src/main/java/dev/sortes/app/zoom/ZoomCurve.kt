package dev.sortes.app.zoom

import kotlin.math.abs

/**
 * The crop model's lost-ink curve, as far as it has been computed: for some of [scales], the share
 * of ink the model's crops cut off and on how many pages.
 */
class ZoomCurve(
    /** Every scale the zoom slider offers, loosest (1) first. */
    val scales: FloatArray,
    /** Computed points, by index into [scales]. */
    val points: Map<Int, Point>,
    /** Pages the analysis solved for. */
    val pagesAnalyzed: Int,
    val autoScale: Float,
) {
    data class Point(val lostFraction: Float, val pagesCut: Int)

    /** Index into [scales] of the entry closest to [scale]. */
    fun nearestIndex(scale: Float): Int = scales.indices.minBy { abs(scales[it] - scale) }

    /** The computed point nearest scale index [i], or null if none is computed yet. */
    fun nearestPoint(i: Int): Point? = points.minByOrNull { abs(it.key - i) }?.value

    /** Computed points in order of index. */
    val sortedPoints: List<Pair<Int, Point>>
        get() = points.toSortedMap().map { it.key to it.value }
}
