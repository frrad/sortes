package dev.sortes.app.zoom

/**
 * An image-space estimate of a page's body-text size, in pixels of a fine render. No PDF text is
 * used, so it works on whatever the reader renders.
 */
object TextSize {
    /**
     * Typical height (the mean of the middle half), in pixels of a fine render, of glyph-sized
     * connected components: roughly between the x-height and the cap height of the body font.
     * Components taller than [maxFraction] of the page, or more than [maxAspect] times wider than
     * tall (rules), are left out, as are specks under [minPixels].
     */
    fun glyphHeight(
        fine: InkMask,
        minPixels: Int = 4,
        maxFraction: Double = 0.03,
        maxAspect: Double = 3.0,
    ): Double? = glyphHeight(PageBlocks.of(fine, 0), fine.height, minPixels, maxFraction, maxAspect)

    /**
     * [glyphHeight] from the fine render's [blocks] (its 8-connected components, gap 0), [height]
     * pixels tall.
     */
    fun glyphHeight(
        blocks: PageBlocks,
        height: Int,
        minPixels: Int = 4,
        maxFraction: Double = 0.03,
        maxAspect: Double = 3.0,
    ): Double? {
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

    private const val MIN_GLYPHS = 50
}
