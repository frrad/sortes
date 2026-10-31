package dev.sortes.app.zoom

/**
 * The text model's component cost ([PageText]): every 8-connected component at a finer resolution has
 * mass one, distributed uniformly over its ink. A rule is one element, as is a connected
 * glyph; disconnected dots/accents count separately. No OCR or semantic classification.
 *
 * Projected onto the solver's existing cells so the kept cells, solving and report agree.
 * This deliberately cannot distinguish two crop edges within the same coarse cell.
 */
class ElementLoss internal constructor(
    val width: Int,
    val height: Int,
    val elements: Int,
    // The cells with any mass, and their masses: about a tenth of a page's cells, against the
    // summed-area table's (width + 1) x (height + 1), which is built only while it's used.
    internal val cells: IntArray,
    internal val masses: DoubleArray,
) {
    /** The summed-area table over the cells' masses, for O(1) queries; build per use, then drop. */
    fun table(): Table {
        val dense = DoubleArray(width * height)
        for (i in cells.indices) dense[cells[i]] = masses[i]
        val sums = DoubleArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var row = 0.0
            for (x in 0 until width) {
                row += dense[y * width + x]
                sums[(y + 1) * (width + 1) + x + 1] = sums[y * (width + 1) + x + 1] + row
            }
        }
        return Table(sums)
    }

    inner class Table internal constructor(private val sums: DoubleArray) {
        /** Lost element equivalents: whole drops count too, partial losses count proportionally. */
        fun lost(cells: CellRect): Double {
            val l = cells.left.coerceIn(0, width)
            val r = cells.right.coerceIn(l, width)
            val t = cells.top.coerceIn(0, height)
            val b = cells.bottom.coerceIn(t, height)
            val stride = width + 1
            val kept = sums[b * stride + r] - sums[b * stride + l] - sums[t * stride + r] + sums[t * stride + l]
            return (elements - kept).coerceIn(0.0, elements.toDouble())
        }
    }

    companion object {
        /**
         * Every component of the fine render that isn't [FineComponents.ignored] has mass one.
         * Fine ink in a coarse blank still counts: dots/thin symbols can disappear on downsampling.
         */
        fun of(c: FineComponents): ElementLoss {
            val fine = c.fine
            val raw = c.raw
            val masses = DoubleArray(raw.width * raw.height)
            for (y in 0 until fine.height) for (x in 0 until fine.width) {
                val b = c.labels[y * fine.width + x]
                if (b < 0 || c.ignored[b]) continue
                val cell = (y * raw.height / fine.height) * raw.width + x * raw.width / fine.width
                masses[cell] += 1.0 / c.blocks.inks[b]
            }
            val inked = masses.indices.filter { masses[it] != 0.0 }.toIntArray()
            return ElementLoss(raw.width, raw.height, c.ignored.count { !it }, inked, DoubleArray(inked.size) { masses[inked[it]] })
        }
    }
}
