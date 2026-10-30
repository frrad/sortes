package dev.sortes.app.zoom

import kotlin.math.floor
import kotlin.math.max

/**
 * Image-space estimates of a page's body-text size, in mask pixels. No PDF text is used, so
 * these work on whatever the reader renders.
 */
object TextSize {
    /**
     * The page's line pitch (baseline to baseline) in pixels of [mask], or null without
     * enough regularly spaced lines. The page is cut into vertical strips (so two columns with
     * offset baselines don't blur each other), and the autocorrelations of the strips' row
     * profiles are summed: autocorrelation ignores phase, so the strips add up coherently.
     * The pitch is the period p maximising the comb Σ_k ac(k·p) − ac((k − ½)·p), which, unlike
     * the bare autocorrelation peak, doesn't also fire at 2p and p/2.
     */
    fun linePitch(mask: InkMask, strips: Int = 6, minPitch: Double = 2.5, maxPitchFraction: Double = 0.06): Double? {
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

    /**
     * Typical height (the mean of the middle half), in pixels of a fine render, of glyph-sized connected components: roughly
     * between the x-height and the cap height of the body font. Components taller than
     * [maxFraction] of the page, or more than [maxAspect] times wider than tall (rules), are
     * left out, as are specks under [minPixels].
     */
    fun glyphHeight(fine: InkMask, minPixels: Int = 4, maxFraction: Double = 0.03, maxAspect: Double = 3.0): Double? =
        glyphHeight(PageBlocks.of(fine, 0), fine.height, minPixels, maxFraction, maxAspect)

    /** [glyphHeight] from the fine render's [blocks] (its 8-connected components, gap 0), [height] pixels tall. */
    fun glyphHeight(blocks: PageBlocks, height: Int, minPixels: Int = 4, maxFraction: Double = 0.03, maxAspect: Double = 3.0): Double? {
        val heights = ArrayList<Int>()
        for (b in blocks.boxes.indices) {
            val box = blocks.boxes[b]
            val bw = box.right - box.left
            val bh = box.bottom - box.top
            if (blocks.inks[b] < minPixels) continue
            if (bh > height * maxFraction) continue
            if (bw > bh * maxAspect) continue
            heights += bh
        }
        if (heights.size < MIN_GLYPHS) return null
        // The mean of the middle half: the median alone snaps to whole pixels.
        heights.sort()
        val mid = heights.subList(heights.size / 4, heights.size * 3 / 4)
        return mid.average()
    }

    /** The median of the non-null [values], or null if there are none. */
    fun median(values: List<Double?>): Double? {
        val v = values.filterNotNull().sorted()
        if (v.isEmpty()) return null
        return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
    }

    private const val HARMONICS = 4
    private const val MIN_COMB = 0.05
    private const val MIN_GLYPHS = 50
}
