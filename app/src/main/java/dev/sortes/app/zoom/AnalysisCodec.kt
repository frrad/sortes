package dev.sortes.app.zoom

import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

/**
 * A [PaperAnalysis] as bytes, for [AnalysisCache]: everything [analyzePaper] rendered and
 * measured (the sampled pages' masks and text measures, the paper's furniture and density) and
 * the automatic crops it solved for, and any crops solved since at other scales (a saved zoom's,
 * the zoom curve's), so opening at a saved zoom solves nothing either. Read back, it is the
 * analysis it was: the same crops at every scale, the same placements on every page, without
 * rendering the sample again.
 *
 * Doubles are written whole, so the costs and crops read back are bit-for-bit the ones computed.
 * The config isn't written: the cache's key names it, and [read] is given it. Reading checks
 * everything an index or size depends on, so corrupt bytes fail here, as an [IOException], and
 * not later while solving.
 */
internal object AnalysisCodec {

    fun write(analysis: PaperAnalysis, out: DataOutput) {
        out.writeInt(analysis.pageCount)
        out.writeFloat(analysis.density)
        out.writeNullableDouble(analysis.glyphPx)
        out.writeInts(analysis.raster.sample.toIntArray())
        out.writeInts(analysis.sample.toIntArray())
        for (mask in analysis.masks) writeMask(mask, out)
        for (text in analysis.sampleText) {
            out.writeBoolean(text != null)
            if (text != null) writeText(text, out)
        }
        when (val rule = analysis.paperRule) {
            is PageFurniture -> {
                out.writeByte(RULE_FURNITURE)
                out.writeFloats(rule.top)
                out.writeFloats(rule.bottom)
            }
            else -> {
                // The only paper rules are furniture, or none (CropConfig.paperRule).
                check(!analysis.config.furniture) { "Unknown paper rule $rule" }
                out.writeByte(RULE_NONE)
            }
        }
        writeCrops(analysis.auto, out)
        val others = analysis.otherCrops
        out.writeInt(others.size)
        for (crops in others) writeCrops(crops, out)
    }

    /**
     * The analysis [write] wrote to [input], for a screen of [aspect] with [config], placing
     * unsampled pages from [source].
     */
    suspend fun read(input: BytesInput, source: PageRenderer, aspect: Float, config: CropConfig): PaperAnalysis {
        val pageCount = input.readInt()
        if (pageCount != source.pageCount) throw IOException("Cached for $pageCount pages, the paper has ${source.pageCount}")
        val density = input.readFloat()
        if (!(density > 0f && density.isFinite())) throw IOException("Bad density $density")
        val glyphPx = input.readNullableDouble()
        if (glyphPx != null && !(glyphPx > 0 && glyphPx.isFinite())) throw IOException("Bad glyph size $glyphPx")
        val readable = input.readCountedInts(pageCount).asList()
        val sample = input.readCountedInts(pageCount).asList()
        if (sample.isEmpty() || !readable.containsAll(sample) || readable.any { it !in 0 until pageCount } ||
            readable.distinct().size != readable.size
        ) {
            throw IOException("Bad sample")
        }
        val masks = sample.map { readMask(input) }
        val texts = masks.map { mask -> if (input.readBoolean()) readText(input, mask, config.text) else null }
        val paperRule = when (val tag = input.readByte().toInt()) {
            RULE_FURNITURE -> PageFurniture(input.readFloats(), input.readFloats())
            RULE_NONE -> IgnorableInkRule { it }
            else -> throw IOException("Unknown paper rule $tag")
        }
        if ((paperRule is PageFurniture) != config.furniture) throw IOException("Paper rule doesn't match the config")
        val auto = readCrops(input, masks.size, config.weights)
        val otherCount = input.readInt()
        if (otherCount !in 0..MAX_SOLVED) throw IOException("Bad count of solved crops $otherCount")
        val others = List(otherCount) { readCrops(input, masks.size, config.weights) }
        if ((others.map { it.scale } + auto.scale).distinct().size != otherCount + 1) throw IOException("Crops solved twice")
        return assemble(PaperRaster(source, density, readable), aspect, config, pageCount, sample, masks, texts, glyphPx, paperRule, auto, others)
    }

    internal fun writeMask(mask: InkMask, out: DataOutput) {
        out.writeInt(mask.width)
        out.writeInt(mask.height)
        val bits = ByteArray((mask.width * mask.height + 7) / 8)
        var i = 0
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (mask[x, y]) bits[i / 8] = (bits[i / 8].toInt() or (1 shl (i % 8))).toByte()
            i++
        }
        out.write(bits)
    }

    internal fun readMask(input: DataInput): InkMask {
        val width = input.readInt()
        val height = input.readInt()
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) throw IOException("Bad mask size ${width}x$height")
        val bits = ByteArray((width * height + 7) / 8).also { input.readFully(it) }
        // Byte by byte: in a process just started this runs interpreted, a bit at a time slowly.
        val cells = BooleanArray(width * height)
        val whole = cells.size / 8
        for (b in 0 until whole) {
            val v = bits[b].toInt()
            if (v == 0) continue
            val i = b * 8
            cells[i] = v and 1 != 0
            cells[i + 1] = v and 2 != 0
            cells[i + 2] = v and 4 != 0
            cells[i + 3] = v and 8 != 0
            cells[i + 4] = v and 16 != 0
            cells[i + 5] = v and 32 != 0
            cells[i + 6] = v and 64 != 0
            cells[i + 7] = v and 128 != 0
        }
        for (i in whole * 8 until cells.size) cells[i] = (bits[i / 8].toInt() shr (i % 8)) and 1 == 1
        return InkMask(width, height, cells)
    }

    private fun writeText(text: PageText, out: DataOutput) {
        out.writeNullableDouble(text.glyphHeight)
        val e = text.elements
        out.writeInt(e.width)
        out.writeInt(e.height)
        out.writeInt(e.elements)
        out.writeInts(e.cells)
        for (m in e.masses) out.writeDouble(m)
        val l = text.lines
        out.writeInt(l.width)
        out.writeInt(l.height)
        out.writeDouble(l.saturation)
        out.writeInt(l.segments.size)
        for (segment in l.segments) out.writeInts(segment)
        out.writeInts(l.cl)
        out.writeInts(l.cr)
        out.writeInts(l.segmentRow)
    }

    /** A page's text measures, checked against its analysis [mask]. */
    private fun readText(input: BytesInput, mask: InkMask, weights: TextWeights): PageText {
        val glyph = input.readNullableDouble()
        if (glyph != null && !(glyph > 0 && glyph.isFinite())) throw IOException("Bad glyph height $glyph")
        val cellCount = mask.width * mask.height
        val ew = input.readInt()
        val eh = input.readInt()
        if (ew != mask.width || eh != mask.height) throw IOException("Element costs are ${ew}x$eh, the mask ${mask.width}x${mask.height}")
        val elements = input.readInt()
        if (elements < 0) throw IOException("Bad element count $elements")
        val cells = input.readCountedInts(cellCount)
        if (cells.any { it !in 0 until cellCount }) throw IOException("Bad element cell")
        val masses = input.readDoubles(cells.size)
        if (masses.any { !(it >= 0.0 && it.isFinite()) }) throw IOException("Bad element mass")
        val lw = input.readInt()
        val lh = input.readInt()
        if (lw != mask.width || lh != mask.height) throw IOException("Line costs are ${lw}x$lh, the mask ${mask.width}x${mask.height}")
        val saturation = input.readDouble()
        if (!(saturation > 0 && saturation.isFinite())) throw IOException("Bad saturation $saturation")
        val segmentCount = input.readInt()
        if (segmentCount !in 0..MAX_COUNT) throw IOException("Bad segment count $segmentCount")
        val segments = List(segmentCount) { input.readCountedInts() }
        val cl = input.readCountedInts()
        val cr = input.readCountedInts(cl.size)
        val rows = input.readCountedInts(segmentCount)
        if (cr.size != cl.size || rows.size != segmentCount) throw IOException("Line tables don't match")
        if (segments.any { s -> s.any { it !in cl.indices } }) throw IOException("Bad segment member")
        if (cl.any { it !in 0..lw } || cr.any { it !in 0..lw } || rows.any { it !in 0 until lh }) throw IOException("Bad line position")
        return PageText(
            glyph,
            ElementLoss(ew, eh, elements, cells, masses),
            LineTruncation(lw, lh, saturation, segments, cl, cr, rows),
            weights,
        )
    }

    private fun writeCrops(crops: ModelCrops, out: DataOutput) {
        out.writeFloat(crops.scale)
        out.writeInt(crops.windows.size)
        for (w in crops.windows) writeWindow(w, out)
        out.writeInt(crops.pages.size)
        for (p in crops.pages) {
            out.writeInt(p.windowIndex)
            writeWindow(p.window, out)
            out.writeInt(p.cells.left)
            out.writeInt(p.cells.top)
            out.writeInt(p.cells.right)
            out.writeInt(p.cells.bottom)
            out.writeInt(p.cutCells)
            out.writeDouble(p.cut)
            out.writeDouble(p.pageCut)
            out.writeDouble(p.offCentre)
            out.writeInt(p.splitBlocks)
            out.writeDouble(p.split)
            out.writeDouble(p.additionalCost)
        }
        out.writeLong(crops.totalInk)
    }

    private fun readCrops(input: DataInput, pages: Int, weights: CropModelWeights): ModelCrops {
        val scale = input.readFloat()
        if (!(scale > 0f && scale <= 1f)) throw IOException("Bad scale $scale")
        val windowCount = input.readInt()
        if (windowCount !in 1..weights.maxWindows) throw IOException("Bad window count $windowCount")
        val windows = List(windowCount) { readWindow(input) }
        if (input.readInt() != pages) throw IOException("Not one crop per sampled page")
        val crops = List(pages) {
            val windowIndex = input.readInt()
            if (windowIndex !in windows.indices) throw IOException("Bad window index $windowIndex")
            PageCrop(
                windowIndex = windowIndex,
                window = readWindow(input),
                cells = CellRect(input.readInt(), input.readInt(), input.readInt(), input.readInt()),
                cutCells = input.readInt(),
                cut = input.readDouble(),
                pageCut = input.readDouble(),
                offCentre = input.readDouble(),
                splitBlocks = input.readInt(),
                split = input.readDouble(),
                additionalCost = input.readDouble(),
            )
        }
        return ModelCrops(scale, windows, crops, weights, input.readLong())
    }

    private fun writeWindow(w: CropWindow, out: DataOutput) {
        out.writeFloat(w.left)
        out.writeFloat(w.top)
        out.writeFloat(w.scale)
    }

    private fun readWindow(input: DataInput): CropWindow {
        val w = CropWindow(input.readFloat(), input.readFloat(), input.readFloat())
        if (!(w.scale > 0f && w.scale <= 1f) || !w.left.isFinite() || !w.top.isFinite()) throw IOException("Bad window $w")
        return w
    }

    private fun DataOutput.writeNullableDouble(value: Double?) {
        writeBoolean(value != null)
        if (value != null) writeDouble(value)
    }

    private fun DataInput.readNullableDouble(): Double? = if (readBoolean()) readDouble() else null

    private fun DataOutput.writeInts(values: IntArray) {
        writeInt(values.size)
        for (v in values) writeInt(v)
    }

    /** The values [writeInts] wrote, at most [max] of them, in one call. */
    private fun BytesInput.readCountedInts(max: Int = MAX_COUNT): IntArray {
        val n = readInt()
        if (n !in 0..max) throw IOException("Bad count $n")
        if (n.toLong() * 4 > remaining) throw IOException("Truncated")
        return readInts(n)
    }

    private fun DataOutput.writeFloats(values: List<Float>) {
        writeInt(values.size)
        for (v in values) writeFloat(v)
    }

    private fun DataInput.readFloats(): List<Float> {
        val n = readInt()
        if (n !in 0..MAX_COUNT) throw IOException("Bad count $n")
        return List(n) { readFloat().also { if (!it.isFinite()) throw IOException("Bad value $it") } }
    }

    private const val RULE_NONE = 0
    private const val RULE_FURNITURE = 1

    /** Longest mask side read back: analysis masks are [ANALYSIS_LONG_SIDE] on their longer side. */
    private const val MAX_SIDE = 4 * ANALYSIS_LONG_SIDE

    /** Most crops at other scales read back: there are fewer zoom scales than this. */
    private const val MAX_SOLVED = 1024

    /** Most entries in any list read back, so corrupt counts can't ask for gigabytes. */
    private const val MAX_COUNT = 1 shl 22
}
