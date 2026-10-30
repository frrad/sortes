package dev.sortes.app.zoom

/**
 * Everything that decides a paper's crops besides its pages: the ink to ignore, and the crop
 * model's weights. The reader analyses papers with [CropConfig.READER]; zoom-eval's harness, fit
 * and labeller can use any config. [CropConfig.TUNED] is the candidate tuned on the owner's labels
 * (tools/zoom-eval/labels); moving the reader to it is a one-line change in [analyzePaper]'s
 * default.
 */
data class CropConfig(
    val name: String,
    /** Rules applied to each page on its own, in order. */
    val pageRules: List<IgnorableInkRule>,
    /** Whether to ignore page furniture ([PageFurniture]), learnt from the paper's (sampled) pages. */
    val furniture: Boolean,
    val weights: CropModelWeights,
) {
    /** The paper-level rule for pages of a paper whose (sampled) pages, after [pageRules], are [pages]. */
    fun paperRule(pages: List<InkMask>): IgnorableInkRule =
        if (furniture) PageFurniture.of(pages) else IgnorableInkRule { it }

    /** A paper's [rendered] pages (all of them, or its sample) as the model sees them. */
    fun prepare(rendered: List<InkMask>): List<InkMask> {
        val pages = rendered.map { it.withoutIgnorableInk(pageRules) }
        val rule = paperRule(pages)
        return pages.map { rule.strip(it) }
    }

    companion object {
        /** What the reader uses. */
        val READER = CropConfig("reader", IGNORABLE_INK_RULES, furniture = false, CropModelWeights())

        /**
         * Tuned on the owner's labels, rounds 1-3: ignore isolated marks and page furniture too,
         * and make a second window cost more (gamma 0.1), so a half-empty page keeps the paper's
         * window. See tools/zoom-eval/SPEC.md.
         */
        val TUNED = CropConfig(
            "tuned",
            IGNORABLE_INK_RULES + IsolatedMarks,
            furniture = true,
            CropModelWeights(gamma = 0.1),
        )

        val ALL = listOf(READER, TUNED)

        fun named(name: String): CropConfig =
            ALL.firstOrNull { it.name == name } ?: throw IllegalArgumentException("Unknown crop config $name")
    }
}
