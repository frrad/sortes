package dev.sortes.app.zoom

import kotlin.math.pow

/**
 * The automatic zoom's legibility cost, as a function of scale. The plain one is zeta·scale: every
 * step tighter earns the same, however big the text already is. This one earns zeta per unit of
 * scale while the body text is at most [comfortable] screen pixels tall, and less once it's bigger,
 * falling as (comfortable / text)^[power] but never below [floor]·zeta: bigger text is still worth
 * something, just less.
 *
 * Text size comes from the page images ([TextSize.glyphHeight]): `textAt(scale) = k / scale`, with
 * k the glyph height on screen at scale 1. So the cost's slope is
 *
 *     zeta · max(floor, min(1, (scale / sT)^power)),   sT = k / comfortable,
 *
 * and the cost is its integral from 0. With [comfortable] infinite it is zeta·scale.
 */
data class Legibility(
    val zeta: Double,
    val comfortable: Double,
    val power: Double,
    val floor: Double,
) {
    init {
        require(zeta >= 0 && comfortable > 0 && power > 0 && floor in 0.0..1.0)
    }

    /** The cost at [scale], for a paper whose glyphs are [k] screen pixels tall at scale 1. */
    fun cost(scale: Double, k: Double): Double {
        if (comfortable.isInfinite()) return zeta * scale
        val sT = k / comfortable
        val sF = sT * floor.pow(1 / power) // where the falling slope meets the floor
        fun steep(s: Double) = sT / (power + 1) * (s / sT).pow(power + 1)
        val c =
            when {
                scale <= sF -> floor * scale
                scale <= sT -> floor * sF + steep(scale) - steep(sF)
                else -> floor * sF + steep(sT) - steep(sF) + (scale - sT)
            }
        return zeta * c
    }
}
