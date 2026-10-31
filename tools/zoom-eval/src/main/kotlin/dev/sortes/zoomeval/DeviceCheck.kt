package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.PageRenderer
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.PlacedPaper
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * A tablet dump replayed: every render the reader's pipeline asked PdfRenderer for, as
 * `p{page}_{w}x{h}.pgm` luminance files in [dir] (written by the app's `CropDumpTest`). Page
 * sizes are the tablet's whole points, which [PdfiumPdf] reports too. A render the dump doesn't
 * have is an [IOException].
 */
class DumpPages(private val dir: File, private val sizes: List<PageSize>) : PageRenderer {
    override val pageCount: Int get() = sizes.size

    override suspend fun pageSize(index: Int): PageSize = sizes[index]

    override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray {
        val file = File(dir, "p${index + 1}_${width}x$height.pgm")
        if (!file.isFile) throw IOException("The dump has no ${width}x$height render of page ${index + 1}: $file")
        val pgm = Pgm.parse(file.readBytes())
        if (pgm.width != width || pgm.height != height) throw IOException("$file is ${pgm.width}x${pgm.height}")
        return IntArray(width * height) { i -> pgm.argb(i) }
    }
}

/** A binary 8-bit greyscale PGM (P5), as `CropDumpTest` writes it. */
internal class Pgm(val width: Int, val height: Int, private val data: ByteArray) {
    fun grey(i: Int): Int = data[i].toInt() and 0xFF

    fun argb(i: Int): Int = grey(i).let { g -> (0xFF shl 24) or (g shl 16) or (g shl 8) or g }

    companion object {
        fun parse(bytes: ByteArray): Pgm {
            var i = 0
            val tokens = mutableListOf<String>()
            while (tokens.size < 4) {
                if (i >= bytes.size) throw IOException("Truncated PGM header")
                val c = bytes[i].toInt().toChar()
                when {
                    c.isWhitespace() -> i++
                    c == '#' -> while (i < bytes.size && bytes[i] != '\n'.code.toByte()) i++
                    else -> {
                        val start = i
                        while (i < bytes.size && !bytes[i].toInt().toChar().isWhitespace()) i++
                        tokens += String(bytes, start, i - start)
                    }
                }
            }
            i++ // the single whitespace after maxval
            if (tokens[0] != "P5") throw IOException("Not a binary PGM: ${tokens[0]}")
            val w = tokens[1].toIntOrNull()
            val h = tokens[2].toIntOrNull()
            if (w == null || h == null || w <= 0 || h <= 0) throw IOException("Bad PGM size: ${tokens[1]} x ${tokens[2]}")
            if (tokens[3] != "255") throw IOException("Unsupported PGM maxval ${tokens[3]}")
            if (bytes.size - i < w * h) throw IOException("Truncated PGM: ${bytes.size - i} of ${w * h} pixels")
            return Pgm(w, h, bytes.copyOfRange(i, i + w * h))
        }
    }
}

/** One row of a `crops`-format TSV: the page's window scale and cells, as the tablet's dump records them. */
internal fun cropKey(scale: Float, cells: String) = "$scale\t$cells"

internal fun PlacedPaper.cropKeys(): List<String> = crops.pages.map { c ->
    cropKey(c.window.scale, "${c.cells.left},${c.cells.top},${c.cells.right},${c.cells.bottom}")
}

/** The tablet's crops from a dump's `<id>.tsv` (`crops` format: scale and cells in columns 7 and 8). */
internal fun readDumpCrops(file: File): List<String> = file.readLines().filter { it.isNotBlank() && !it.startsWith("paper\t") }.map { line ->
    val f = line.split('\t')
    if (f.size < 8) throw IOException("Bad crops line in $file: $line")
    cropKey(f[6].toFloat(), f[7])
}

/** How far two renders of a page are apart: differing mask pixels (at the pipeline's threshold), device ink, mean |grey| difference. */
internal data class RenderDiff(val maskXor: Int, val deviceInk: Int, val meanAbsGrey: Double)

internal fun renderDiff(device: IntArray, other: IntArray, width: Int, height: Int): RenderDiff {
    require(device.size == width * height && other.size == device.size) { "Renders of different sizes" }
    val a = InkMask.fromArgb(device, width, height)
    val b = InkMask.fromArgb(other, width, height)
    var xor = 0
    for (y in 0 until height) for (x in 0 until width) if (a[x, y] != b[x, y]) xor++
    var sum = 0L
    for (i in device.indices) sum += abs(luma(device[i]) - luma(other[i]))
    return RenderDiff(xor, a.inkCount, sum.toDouble() / device.size)
}

/** Luminance as [InkMask] computes it (the dump stores it; pdfium's colour pages need it computed). */
internal fun luma(argb: Int): Int =
    (((argb shr 16) and 0xFF) * 299 + ((argb shr 8) and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000

/**
 * `device-check --device=DIR [--papers=a,b] [--pdfs=] [--aspect=0.75]`: keeps pdfium honest
 * against a fresh tablet dump (DIR is the pulled `fidelity/out` of the app's `CropDumpTest`; see
 * notes/renderer-fidelity.md). Per paper: whether replaying the dump gives the tablet's crops
 * (it should, exactly), whether pdfium gives them, and how far pdfium's renders are from the
 * tablet's. A pdfium that drifts (a mediaprovider update on the tablet, another pdfium pin)
 * shows here first.
 */
fun deviceCheckMain(args: List<String>) = runBlocking {
    val opts = args.associate { arg ->
        require(arg.startsWith("--") && '=' in arg) { "Bad argument: $arg" }
        arg.removePrefix("--").substringBefore('=') to arg.substringAfter('=')
    }
    val known = setOf("device", "papers", "pdfs", "aspect")
    opts.keys.firstOrNull { it !in known }?.let { throw IllegalArgumentException("Unknown option --$it") }
    val device = File(requireNotNull(opts["device"]) { "--device=DIR is required" })
    val pdfs = File(opts["pdfs"] ?: "${System.getProperty("user.home")}/.cache/sortes-zoom-eval/pdf")
    val aspect = (opts["aspect"] ?: "0.75").toFloat()
    val papers = opts["papers"]?.split(',') ?: File(device, "renders").list().orEmpty().sorted()
    if (papers.isEmpty()) throw IOException("No dumped papers in $device/renders")
    println("paper\tpages\tdevice zoom\treplay\tpdfium zoom\tpdfium pages differing\tcoarse mask xor\tfine mask xor\tmean |grey|")
    var replayOk = 0
    var pdfiumOk = 0
    for (id in papers) {
        val pdf = PdfiumPdf(File(pdfs, "$id.pdf"))
        val renders = File(device, "renders/$id")
        val tablet = readDumpCrops(File(device, "$id.tsv"))
        val replay = readerCrops(DumpPages(renders, pdf.pageSizes), aspect, CropConfig.DEFAULT).cropKeys()
        val ours = readerCrops(pdf, aspect, CropConfig.DEFAULT).cropKeys()
        val differing = tablet.indices.count { tablet[it] != ours.getOrNull(it) }
        if (replay == tablet) replayOk++
        if (differing == 0) pdfiumOk++
        val diffs = renders.listFiles().orEmpty().mapNotNull { f -> RENDER.matchEntire(f.name)?.let { m -> f to m.groupValues.drop(1).map(String::toInt) } }
        val coarseWidth = diffs.groupBy { it.second[0] }.mapValues { (_, l) -> l.minOf { it.second[1] } }
        val sums = HashMap<Boolean, Triple<Long, Long, Double>>()
        for ((f, key) in diffs) {
            val (page, w, h) = key
            val pgm = Pgm.parse(f.readBytes())
            val d = renderDiff(IntArray(w * h) { pgm.argb(it) }, pdf.argb(page - 1, w, h), w, h)
            val fine = w != coarseWidth[page]
            val s = sums[fine] ?: Triple(0L, 0L, 0.0)
            sums[fine] = Triple(s.first + d.maskXor, s.second + d.deviceInk, s.third + d.meanAbsGrey)
        }
        fun pct(fine: Boolean) = sums[fine]?.let { "%.1f%%".format(100.0 * it.first / maxOf(1, it.second)) } ?: "-"
        val meanGrey = sums.values.sumOf { it.third } / maxOf(1, diffs.size)
        println(listOf(id, tablet.size, tablet.first().substringBefore('\t'), if (replay == tablet) "same" else "DIFFERS",
            ours.first().substringBefore('\t'), differing, pct(false), pct(true), "%.2f".format(meanGrey)).joinToString("\t"))
    }
    println("${papers.size} papers: the replay gives the tablet's crops on $replayOk, pdfium on $pdfiumOk")
}

private val RENDER = Regex("""p(\d+)_(\d+)x(\d+)\.pgm""")
