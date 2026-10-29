package dev.sortes.app.zoom

/** Which pixels of a low-resolution page render carry ink (anything that isn't near-white). */
class InkMask(val width: Int, val height: Int, private val ink: BooleanArray) {

    init {
        require(width > 0 && height > 0) { "Empty mask: ${width}x$height" }
        require(ink.size == width * height) { "Expected ${width * height} pixels, got ${ink.size}" }
    }

    operator fun get(x: Int, y: Int): Boolean = ink[y * width + x]

    val inkCount: Int = ink.count { it }

    companion object {
        /**
         * The least a pixel must be darker than the paper to count as ink. Small, so the faint
         * anti-aliased edges of thin glyphs count: a page number whose edge cells are only a
         * few percent covered is still there on screen, and cutting them clips it visibly. On
         * white paper that makes light shading (a grey code listing or table row) ink too: it is
         * content, and cutting it shows.
         */
        const val PAPER_MARGIN = 6

        /** Extra margin beyond the paper's own noise, so its darkest grains stay paper. */
        const val NOISE_GUARD = 2

        /** Pixels at least this light are candidates for the paper colour. */
        const val PAPER_MIN = 192

        /**
         * Builds a mask from opaque ARGB pixels in row-major order, as from `Bitmap.getPixels`.
         * A pixel is ink if its luminance (0-255) is below [threshold], by default the page's
         * [inkThreshold].
         */
        fun fromArgb(
            pixels: IntArray,
            width: Int,
            height: Int,
            threshold: Int? = null,
        ): InkMask {
            val luminance = IntArray(pixels.size) { luminance(pixels[it]) }
            val limit = threshold ?: inkThreshold(luminance)
            return InkMask(width, height, BooleanArray(pixels.size) { luminance[it] < limit })
        }

        /**
         * Luminance below which a page's pixels are ink: [PAPER_MARGIN] below its paper colour,
         * or more on a grainy scan. The paper is the bulk of the light pixels (their median), and
         * its grain shows in how far they stray *above* it, since ink only darkens; the threshold
         * allows as much spread below the paper, plus [NOISE_GUARD]. Off-white or grainy scans so
         * stay paper, while on clean paper even faint glyph edges are ink.
         */
        fun inkThreshold(luminance: IntArray): Int {
            val counts = lightCounts(luminance)
            val paper = percentile(counts, 0.5) ?: return 255 - PAPER_MARGIN
            val spread = percentile(counts, 0.99)!! - paper
            return paper - maxOf(PAPER_MARGIN, spread + NOISE_GUARD)
        }

        /**
         * The page's paper colour: the median of its light pixels, or white if there are none
         * (a page that's all dark photo).
         */
        fun paperLuminance(luminance: IntArray): Int = percentile(lightCounts(luminance), 0.5) ?: 255

        /** How many pixels have each luminance of at least [PAPER_MIN]. */
        private fun lightCounts(luminance: IntArray): IntArray {
            val counts = IntArray(256)
            for (l in luminance) if (l >= PAPER_MIN) counts[l]++
            return counts
        }

        /** The least luminance at or below which [fraction] of the counted pixels lie; null if none. */
        private fun percentile(counts: IntArray, fraction: Double): Int? {
            val total = counts.sum()
            if (total == 0) return null
            var seen = 0
            for (l in counts.indices) {
                seen += counts[l]
                if (seen >= total * fraction) return l
            }
            return 255
        }

        private fun luminance(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000
        }
    }
}
