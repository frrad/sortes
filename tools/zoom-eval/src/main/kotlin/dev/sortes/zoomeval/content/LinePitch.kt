package dev.sortes.zoomeval.content

import dev.sortes.app.zoom.InkMask
import kotlin.math.floor
import kotlin.math.max

/**
 * A text-size estimate the reader doesn't use, kept for validating [dev.sortes.app.zoom.TextSize]
 * (`text-size`): it recovers the PDF's baseline-to-baseline distance, but leading isn't font size.
 */
object LinePitch {
    /**
     * The page's line pitch (baseline to baseline) in pixels of [mask], or null without enough
     * regularly spaced lines. The page is cut into vertical strips (so two columns with offset
     * baselines don't blur each other), and the autocorrelations of the strips' row profiles are
     * summed: autocorrelation ignores phase, so the strips add up coherently. The pitch is the
     * period p maximising the comb Σ_k ac(k·p) − ac((k − ½)·p), which, unlike the bare
     * autocorrelation peak, doesn't also fire at 2p and p/2.
     */
    fun of(
        mask: InkMask,
        strips: Int = 6,
        minPitch: Double = 2.5,
        maxPitchFraction: Double = 0.06,
    ): Double? {
        val w = mask.width
        val h = mask.height
        val maxPitch = max(minPitch + 1, h * maxPitchFraction)
        val maxLag = (maxPitch * HARMONICS).toInt() + 2
        if (maxLag >= h) return null
        val ac = DoubleArray(maxLag + 1)
        val profile = DoubleArray(h)
        for (s in 0 until strips) {
            val x0 = s * w / strips
            val x1 = (s + 1) * w / strips
            for (y in 0 until h) {
                var n = 0
                for (x in x0 until x1) if (mask[x, y]) n++
                profile[y] = n.toDouble()
            }
            val mean = profile.average()
            for (y in 0 until h) profile[y] -= mean
            for (lag in 0..maxLag) {
                var sum = 0.0
                for (y in 0 until h - lag) sum += profile[y] * profile[y + lag]
                ac[lag] += sum
            }
        }
        if (ac[0] <= 0.0) return null
        fun at(t: Double): Double {
            val i = floor(t).toInt()
            if (i >= maxLag) return 0.0
            val f = t - i
            return (ac[i] * (1 - f) + ac[i + 1] * f) / ac[0]
        }
        var best = Double.NEGATIVE_INFINITY
        var bestP = 0.0
        var p = minPitch
        while (p <= maxPitch) {
            var score = 0.0
            for (k in 1..HARMONICS) score += at(k * p) - at((k - 0.5) * p)
            score /= HARMONICS
            if (score > best) {
                best = score
                bestP = p
            }
            p += 0.02
        }
        return if (best >= MIN_COMB) bestP else null
    }

    private const val HARMONICS = 4
    private const val MIN_COMB = 0.05
}
