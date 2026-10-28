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
         * How much darker than the paper a pixel must be to count as ink. Small, so the faint
         * anti-aliased edges of thin glyphs count: a page number whose edge cells are only a
         * few percent covered is still there on screen, and cutting them clips it visibly.
         */
        const val PAPER_MARGIN = 6

        /** Pixels at least this light are candidates for the paper colour. */
        const val PAPER_MIN = 192

        /**
         * Builds a mask from opaque ARGB pixels in row-major order, as from `Bitmap.getPixels`.
         * A pixel is ink if its luminance (0-255) is below [threshold]; by default that is
         * [PAPER_MARGIN] below the page's paper colour (see [paperLuminance]), so off-white
         * scans aren't all ink.
         */
        fun fromArgb(
            pixels: IntArray,
            width: Int,
            height: Int,
            threshold: Int? = null,
        ): InkMask {
            val luminance = IntArray(pixels.size) { luminance(pixels[it]) }
            val limit = threshold ?: (paperLuminance(luminance) - PAPER_MARGIN)
            return InkMask(width, height, BooleanArray(pixels.size) { luminance[it] < limit })
        }

        /**
         * The page's paper colour: the most common luminance among light pixels, or white if
         * there are none (a page that's all dark photo).
         */
        fun paperLuminance(luminance: IntArray): Int {
            val counts = IntArray(256)
            for (l in luminance) if (l >= PAPER_MIN) counts[l]++
            var best = 255
            for (l in PAPER_MIN..255) if (counts[l] > counts[best]) best = l
            return best
        }

        private fun luminance(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000
        }
    }
}
