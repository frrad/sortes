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
        /** Pixels with luminance (0-255) below this count as ink; above it is paper. */
        const val DEFAULT_THRESHOLD = 224

        /** Builds a mask from opaque ARGB pixels in row-major order, as from `Bitmap.getPixels`. */
        fun fromArgb(
            pixels: IntArray,
            width: Int,
            height: Int,
            threshold: Int = DEFAULT_THRESHOLD,
        ): InkMask = InkMask(width, height, BooleanArray(pixels.size) { luminance(pixels[it]) < threshold })

        private fun luminance(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000
        }
    }
}
