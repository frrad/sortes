package dev.sortes.app.zoom

/**
 * The text model's parameters ([CropConfig.text]): a text-size-aware legibility cost for the
 * automatic zoom ([Legibility]), and two page costs that place windows at that zoom, measured on
 * each page rendered [factor] times finer than the analysis mask: lost components ([ElementLoss],
 * weight [elements]) and lines clipped at a side edge ([LineTruncation], weight [lines]). See
 * tools/zoom-eval/notes/text-size/README.md.
 */
data class TextWeights(
    /**
     * Body glyph height, in [screenWidth]-screen pixels, up to which a tighter zoom earns the full
     * zeta.
     */
    val comfortable: Double = 28.0,
    /** How fast the legibility reward falls once glyphs are bigger than [comfortable]. */
    val power: Double = 16.0,
    /** The least share of zeta a tighter zoom still earns, however big the text. */
    val floor: Double = 0.1,
    /** Per lost component equivalent. */
    val elements: Double = 0.01,
    /** Per truncated line segment. */
    val lines: Double = 0.1,
    /** How much finer than the analysis mask the pages are rendered for text, in each dimension. */
    val factor: Int = 4,
    /** Most glyph heights between the components of one line segment. */
    val lineGap: Double = 1.5,
    /** Lost components at which a truncated segment costs the whole [lines]. */
    val lineSaturation: Double = 2.0,
    /**
     * Screen width in pixels the text size is measured against: the Boox Note Air5 C's. Fixed
     * rather than the device's, so the crops depend only on the paper and the screen's shape, and
     * zoom-eval's are the reader's.
     */
    val screenWidth: Double = 1860.0,
) {
    init {
        require(comfortable > 0 && power > 0 && floor in 0.0..1.0) { "Bad legibility: $this" }
        require(elements >= 0 && elements.isFinite() && lines >= 0 && lines.isFinite()) {
            "Bad page cost weights: $this"
        }
        require(factor in 1..8) { "Bad factor: $factor" }
        require(lineGap > 0 && lineSaturation > 0 && screenWidth > 0) { "Bad text weights: $this" }
    }

    fun legibility(zeta: Double) = Legibility(zeta, comfortable, power, floor)

    /**
     * Whether windows are placed with any page costs (else the fine renders are only measured for
     * text size).
     */
    val placesWindows: Boolean
        get() = elements > 0.0 || lines > 0.0

    /** As labels record them (`labelledAgainst.parameters`), with the zoom search's range. */
    fun parameters(): Map<String, Double> =
        sortedMapOf(
            "textComfortablePx" to comfortable,
            "textPower" to power,
            "textFloor" to floor,
            "elementWeight" to elements,
            "lineWeight" to lines,
            "fineFactor" to factor.toDouble(),
            "lineGap" to lineGap,
            "lineSaturation" to lineSaturation,
            "screenWidthPx" to screenWidth,
            "minScale" to ZoomScales.SEARCH_MIN.toDouble(),
            "maxScale" to ZoomScales.SEARCH_MAX.toDouble(),
        )
}

/**
 * What the text model measures on one page's fine render: its body [glyphHeight] (fine pixels, null
 * without enough text) and its [PageCost], the weighted [ElementLoss] and [LineTruncation]. Neither
 * the fine render nor the costs' tables are kept: the components' masses per cell and the line
 * segments, roughly a quarter of a megabyte a letter page; the tables are built while solving.
 */
class PageText(
    val glyphHeight: Double?,
    val elements: ElementLoss,
    val lines: LineTruncation,
    private val weights: TextWeights,
) : PageCost {
    override fun open(): CellCost {
        val e = elements.table()
        val l = lines.table()
        return CellCost { cells ->
            weights.elements * e.lost(cells) + weights.lines * l.cost(cells)
        }
    }

    companion object {
        /**
         * Measures [fine], page rendered at [TextWeights.factor] times the size of [raw] (its
         * analysis mask before the ignorable-ink rules); [prepared] is [raw] after them.
         */
        fun of(fine: InkMask, raw: InkMask, prepared: InkMask, weights: TextWeights): PageText {
            require(
                fine.width == raw.width * weights.factor &&
                    fine.height == raw.height * weights.factor
            ) {
                "Fine render ${fine.width}x${fine.height} isn't ${weights.factor}x ${raw.width}x${raw.height}"
            }
            val components = FineComponents.of(fine, raw, prepared)
            val glyph = TextSize.glyphHeight(components.blocks, fine.height)
            return PageText(
                glyph,
                ElementLoss.of(components),
                LineTruncation.of(components, weights.lineGap, weights.lineSaturation, glyph),
                weights,
            )
        }

        /**
         * The paper's body glyph height in [TextWeights.screenWidth] pixels at scale 1 (the padded
         * frame filling the screen's width): the median over [pages] of each page's glyph height
         * against its frame, given its analysis [masks]. Null if no page has measurable text.
         */
        fun glyphPx(
            pages: List<PageText?>,
            masks: List<InkMask>,
            aspect: Float,
            weights: TextWeights,
        ): Double? =
            TextSize.median(
                pages.indices.map { i ->
                    pages[i]?.glyphHeight?.let {
                        it / weights.factor / PaddedPage.frameWidth(masks[i], aspect) *
                            weights.screenWidth
                    }
                }
            )
    }
}

/**
 * A fine render's 8-connected components ([PageBlocks] at gap 0), found once for the text model's
 * measures, and which of them are [ignored]: those whose ink mostly lies in cells the ignorable-ink
 * rules removed (in [raw], the analysis mask, but not in [prepared], the mask the model sees).
 * Segmenting before excluding matters: erasing the coarse cells first would turn the anti-aliased
 * fringe of an ignored stamp into dozens of new components.
 */
class FineComponents
private constructor(
    val fine: InkMask,
    val raw: InkMask,
    val blocks: PageBlocks,
    /** The component each fine pixel belongs to, row-major, or -1. */
    val labels: IntArray,
    val ignored: BooleanArray,
) {
    companion object {
        fun of(fine: InkMask, raw: InkMask, prepared: InkMask): FineComponents {
            require(raw.width == prepared.width && raw.height == prepared.height) {
                "Mask sizes differ"
            }
            require(fine.width >= raw.width && fine.height >= raw.height) {
                "Fine mask is smaller than the solver mask"
            }
            val blocks = PageBlocks.of(fine, 0)
            val labels = blocks.labels()
            val removed = IntArray(blocks.boxes.size)
            for (y in 0 until fine.height) for (x in 0 until fine.width) {
                val b = labels[y * fine.width + x]
                if (b < 0) continue
                val cx = x * raw.width / fine.width
                val cy = y * raw.height / fine.height
                if (raw[cx, cy] && !prepared[cx, cy]) removed[b]++
            }
            // Ignored if at least half its ink is: its fine fringe goes with it.
            return FineComponents(
                fine,
                raw,
                blocks,
                labels,
                BooleanArray(removed.size) { removed[it] * 2 >= blocks.inks[it] },
            )
        }
    }
}
