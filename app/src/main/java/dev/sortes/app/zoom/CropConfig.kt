package dev.sortes.app.zoom

/**
 * Everything that decides a paper's crops besides its pages: the ink to ignore, the crop model's
 * weights and the text model's. The reader and zoom-eval both analyse papers with [analyzePaper],
 * by default with [CropConfig.DEFAULT]; zoom-eval's fit and experiments try variations of it.
 */
data class CropConfig(
    val name: String,
    /** Rules applied to each page on its own, in order. */
    val pageRules: List<IgnorableInkRule>,
    /** Whether to ignore page furniture ([PageFurniture]), learnt from the paper's (sampled) pages. */
    val furniture: Boolean,
    val weights: CropModelWeights,
    val text: TextWeights,
) {
    /** The paper-level rule for pages of a paper whose (sampled) pages, after [pageRules], are [pages]. */
    fun paperRule(pages: List<InkMask>): IgnorableInkRule =
        if (furniture) PageFurniture.of(pages) else IgnorableInkRule { it }

    /**
     * How a paper's pages are seen by the model, given its [sample] of rendered pages: without
     * [pageRules]' ink, then without the paper rule's (furniture, learnt from the sample). The
     * one place the rules are applied, to sampled pages and the rest alike.
     */
    fun preparer(sample: List<InkMask>): (InkMask) -> InkMask =
        preparer(paperRule(sample.map { it.withoutIgnorableInk(pageRules) }))

    /** As [preparer], given the paper rule ([paperRule]) learnt from the sample. */
    fun preparer(paperRule: IgnorableInkRule): (InkMask) -> InkMask = { paperRule.strip(it.withoutIgnorableInk(pageRules)) }

    /**
     * Everything in this config that decides a paper's crops, as stable text, for keying stored
     * analyses ([AnalysisCache]): changing any weight changes it. Rules are named by their class
     * (they're objects), the weights by their data classes' text.
     */
    fun fingerprint(): String = listOf(
        name,
        pageRules.joinToString(",") { rule -> rule.toString().takeUnless { '@' in it } ?: rule.javaClass.name },
        "furniture=$furniture",
        weights.toString(),
        text.toString(),
    ).joinToString("|")

    /** A paper's [rendered] pages (all of them, or its sample) as the model sees them. */
    fun prepare(rendered: List<InkMask>): List<InkMask> = rendered.map(preparer(rendered))

    companion object {
        /**
         * The crop model tuned on the owner's labels (rounds 1-4, tools/zoom-eval/BLOCKS.md) with
         * the text model (round 5, tools/zoom-eval/notes/text-size): ignores margin stamps,
         * isolated marks and page furniture; the block-aware cost (kappa, splitInk) so windows
         * keep or drop figures, tables and paragraphs whole; strong centring (beta) and a dear
         * second window (gamma); a small cost per switch (delta); no per-page convex term
         * (epsilon), since blocks do its job. The automatic zoom minimises the cost per page
         * plus a legibility cost that knows the paper's text size, then loosens by 0.01; at that
         * zoom, windows avoid dropping components and clipping lines. The component weight is
         * 0.016, not round 5's 0.01: re-fitted on pdfium's renders, which are the tablet's
         * (tools/zoom-eval/notes/pdfium-refit.md).
         */
        val DEFAULT = CropConfig(
            "tuned+text",
            IGNORABLE_INK_RULES + IsolatedMarks,
            furniture = true,
            CropModelWeights(
                beta = 0.5,
                epsilon = 0.0,
                gamma = 0.1,
                delta = 0.005,
                kappa = 0.2,
                splitInk = 0.005,
                zeta = 0.5,
                autoMargin = 0.01,
            ),
            TextWeights(elements = 0.016),
        )

        val ALL = listOf(DEFAULT)

        fun named(name: String): CropConfig =
            ALL.firstOrNull { it.name == name } ?: throw IllegalArgumentException("Unknown crop config $name")
    }
}
