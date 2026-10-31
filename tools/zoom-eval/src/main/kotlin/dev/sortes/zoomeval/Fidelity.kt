package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.PageRenderer
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.TextSize
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import kotlin.math.abs

/*
 * Investigation only (notes/renderer-fidelity.md): compares the reader's pipeline on the device's
 * PdfRenderer pixels (dumped by the app's CropDumpTest) with the same pipeline on offline renderers.
 */

/** Integer page sizes, as Android's PdfRenderer reports them (pdfClient's Page::Width() truncates). */
fun deviceSizes(sizes: List<PageSize>): List<PageSize> =
    sizes.map { PageSize(it.width.toInt().toFloat(), it.height.toInt().toFloat()) }

/** Replays a device dump: `p{page}_{w}x{h}.pgm`, the luminance of every render the device made. */
class DumpPages(private val dir: File, private val sizes: List<PageSize>) : PageRenderer {
    override val pageCount get() = sizes.size
    override suspend fun pageSize(index: Int) = sizes[index]
    override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray {
        val f = File(dir, "p${index + 1}_${width}x$height.pgm")
        if (!f.exists()) throw IOException("No device render $f")
        return greyToArgb(Pnm.parse(f.readBytes()))
    }
}

/** mutool, but with the device's integer page sizes and its matrix (the page's fractional points cropped off). */
class MutoolAsDevice(private val pdf: File, private val mutool: MutoolPdf) : PageRenderer {
    private val sizes = deviceSizes(mutool.pageSizes)
    override val pageCount get() = sizes.size
    override suspend fun pageSize(index: Int) = sizes[index]
    override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray {
        val real = mutool.pageSizes[index]
        // Scale = width / int width, so the whole page is a little larger; render it and keep the top left.
        val w = Math.round(real.width * width / sizes[index].width)
        val h = Math.round(real.height * height / sizes[index].height)
        val full = mutool.grey(index, maxOf(w, width), maxOf(h, height))
        val fw = maxOf(w, width)
        return IntArray(width * height) { i -> full[(i / width) * fw + i % width] }
    }
}

/** mutool drawing with the device's exact matrix (mutool run fidelity/mutool-render.js), grey or RGB then luma. */
class MutoolExact(private val pdf: File, mutool: MutoolPdf, private val script: File, private val rgb: Boolean) : PageRenderer {
    private val sizes = deviceSizes(mutool.pageSizes)
    override val pageCount get() = sizes.size
    override suspend fun pageSize(index: Int) = sizes[index]
    override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray {
        val out = java.nio.file.Files.createTempFile("fid", if (rgb) ".ppm" else ".pgm").toFile()
        try {
            val p = ProcessBuilder(listOfNotNull("mutool", "run", script.path, pdf.path, index.toString(), width.toString(),
                height.toString(), sizes[index].width.toString(), sizes[index].height.toString(), out.path, if (rgb) "rgb" else null))
                .redirectErrorStream(true).start()
            p.inputStream.readBytes()
            if (p.waitFor() != 0) throw IOException("mutool run failed")
            val pnm = Pnm.parse(out.readBytes())
            require(pnm.width == width && pnm.height == height)
            return IntArray(width * height) { i ->
                val x = i % width; val y = i / width
                val g = if (rgb) (pnm.pixel(x, y, 0) * 299 + pnm.pixel(x, y, 1) * 587 + pnm.pixel(x, y, 2) * 114) / 1000 else pnm.pixel(x, y, 0)
                (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        } finally {
            out.delete()
        }
    }
}

/** pdfium through tools/zoom-eval/fidelity/pdfium-render.py, run by [python] (a venv with pypdfium2). */
class PdfiumPages(private val pdf: File, python: String, script: File, noLcd: Boolean = false, floatSize: Boolean = false) : PageRenderer, AutoCloseable {
    private val process = ProcessBuilder(listOfNotNull(python, script.path, if (noLcd) "--no-lcd" else null, if (floatSize) "--float-size" else null))
        .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val input = DataInputStream(process.inputStream.buffered())
    private val output = OutputStreamWriter(process.outputStream)
    private val sizes = MutoolPdf(pdf).pageSizes.let { if (floatSize) it else deviceSizes(it) }
    override val pageCount get() = sizes.size
    override suspend fun pageSize(index: Int) = sizes[index]

    fun pdfiumSize(index: Int): PageSize {
        output.write("size ${pdf.path} $index\n"); output.flush()
        val (w, h) = readLine().split(' ').map { it.toFloat() }
        return PageSize(w, h)
    }

    override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray {
        output.write("render ${pdf.path} $index $width $height\n"); output.flush()
        check(readLine() == "OK")
        val bytes = ByteArray(width * height)
        input.readFully(bytes)
        return IntArray(bytes.size) { val g = bytes[it].toInt() and 0xFF; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("pdfium-render.py exited")
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
        }
    }

    override fun close() {
        output.close(); process.waitFor()
    }
}

/** [source] with every pixel at least [white] made paper white: the pipeline's mask is then "darker than [white]". */
class Whiten(private val source: PageRenderer, private val white: Int) : PageRenderer {
    override val pageCount get() = source.pageCount
    override suspend fun pageSize(index: Int) = source.pageSize(index)
    override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray =
        source.renderPixels(index, width, height).map { if (lum(it) >= white) -1 else it }.toIntArray()
}

/** Coarse renders from [coarse], fine ones (any other size than a page's first render) from [fine]. */
class HybridPages(private val coarse: PageRenderer, private val fine: PageRenderer) : PageRenderer {
    private val coarseWidth = HashMap<Int, Int>()
    override val pageCount get() = coarse.pageCount
    override suspend fun pageSize(index: Int) = coarse.pageSize(index)
    override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray {
        val first = coarseWidth.getOrPut(index) { width }
        return (if (width == first) coarse else fine).renderPixels(index, width, height)
    }
}

private fun greyToArgb(p: Pnm) = IntArray(p.width * p.height) { i ->
    val g = p.pixel(i % p.width, i / p.width, 0); (0xFF shl 24) or (g shl 16) or (g shl 8) or g
}

private fun lum(argb: Int) = ((argb shr 16) and 0xFF) // grey renders: r = g = b

/**
 * `fidelity --device=DIR [--papers=a,b] [--renderers=...] [--pixels] [--python5731=] [--python8076=]`
 * DIR is the pulled `fidelity/out`: `<id>.tsv` crops and `renders/<id>/` PGMs per paper.
 */
fun fidelityMain(args: List<String>) = runBlocking {
    val opts = args.associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=', "true") }
    val home = System.getProperty("user.home")
    val device = File(opts.getValue("device"))
    val pdfs = File(opts["pdfs"] ?: "$home/.cache/sortes-zoom-eval/pdf")
    val script = File(opts["script"] ?: "fidelity/pdfium-render.py")
    val pythons = mapOf(
        "pdfium5731" to (opts["python5731"] ?: "/tmp/pdf4.8.0/bin/python"),
        "pdfium8076" to (opts["python8076"] ?: "/tmp/pdfenv/bin/python"),
    )
    val renderers = (opts["renderers"] ?: "device,mutool,mutool-int,pdfium5731,pdfium8076,coarseDev+fineMutool,coarseMutool+fineDev")
        .split(',')
    val papers = opts["papers"]?.split(',') ?: device.listFiles()!!.filter { it.name.endsWith(".tsv") && it.name != "summary.tsv" }
        .map { it.name.removeSuffix(".tsv") }.sorted()
    val aspect = (opts["aspect"] ?: "0.75").toFloat()
    val out = File(opts["out"] ?: "fidelity-crops.tsv")
    val pixOut = File(opts["pixels-out"] ?: "fidelity-pixels.tsv")
    if (!out.exists()) out.writeText("paper\trenderer\tscale\tk\tglyphPx\tpages\tpagesDiffering\tcellsOff\tdiffering\n")
    if (opts.containsKey("pixels") && !pixOut.exists()) {
        pixOut.writeText("paper\trenderer\tpage\tkind\twidth\theight\tthrDev\tthrOther\tinkDev\tinkOther\tmaskXor\tmeanAbsGrey\tmaxAbsGrey\tglyphDev\tglyphOther\n")
    }
    for (id in papers) {
        val pdf = File(pdfs, "$id.pdf")
        val mutool = MutoolPdf(pdf)
        val sizes = deviceSizes(mutool.pageSizes)
        val dumpDir = File(device, "renders/$id")
        val deviceCrops = File(device, "$id.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        val opened = ArrayList<AutoCloseable>()
        fun make(name: String): PageRenderer = when (name) {
            "device" -> DumpPages(dumpDir, sizes)
            "mutool" -> mutool
            "mutool-int" -> MutoolAsDevice(pdf, mutool)
            "mutool-exact" -> MutoolExact(pdf, mutool, File("fidelity/mutool-render.js"), rgb = false)
            "mutool-exact-rgb" -> MutoolExact(pdf, mutool, File("fidelity/mutool-render.js"), rgb = true)
            "coarseDev+fineMutoolExact" -> HybridPages(DumpPages(dumpDir, sizes), MutoolExact(pdf, mutool, File("fidelity/mutool-render.js"), rgb = true))
            "coarseMutoolExact+fineDev" -> HybridPages(MutoolExact(pdf, mutool, File("fidelity/mutool-render.js"), rgb = true), DumpPages(dumpDir, sizes))
            "pdfium5731", "pdfium8076" -> PdfiumPages(pdf, pythons.getValue(name), script).also { opened += it }
            "pdfium5731-float" -> PdfiumPages(pdf, pythons.getValue("pdfium5731"), script, floatSize = true).also { opened += it }
            "pdfium5731-nolcd" -> PdfiumPages(pdf, pythons.getValue("pdfium5731"), script, noLcd = true).also { opened += it }
            "coarseDev+fineMutool" -> HybridPages(DumpPages(dumpDir, sizes), mutool)
            "coarseMutool+fineDev" -> HybridPages(mutool, DumpPages(dumpDir, sizes))
            "coarseDev+fineMutoolInt" -> HybridPages(DumpPages(dumpDir, sizes), MutoolAsDevice(pdf, mutool))
            "coarseMutoolInt+fineDev" -> HybridPages(MutoolAsDevice(pdf, mutool), DumpPages(dumpDir, sizes))
            else -> Regex("""(\w+)@(\d+)""").matchEntire(name)?.let { m -> Whiten(make(m.groupValues[1]), m.groupValues[2].toInt()) }
                ?: throw IllegalArgumentException(name)
        }
        try {
            for (name in renderers) {
                if (name.startsWith("coarse") || name == "device") if (!dumpDir.exists()) continue
                val r = try {
                    readerCrops(make(name), aspect, CropConfig.DEFAULT)
                } catch (e: Exception) {
                    println("$id $name failed: $e"); continue
                }
                val pages = r.crops.pages
                opts["crops-out"]?.let { dir ->
                    File(dir).mkdirs()
                    File(dir, "$id.$name.tsv").writeText(pages.joinToString("") { c ->
                        "${c.window.scale}\t${c.cells.left},${c.cells.top},${c.cells.right},${c.cells.bottom}\n" })
                }
                var differing = 0
                var cellsOff = 0
                val which = ArrayList<Int>()
                pages.forEachIndexed { i, c ->
                    val d = deviceCrops[i]
                    val cells = "${c.cells.left},${c.cells.top},${c.cells.right},${c.cells.bottom}"
                    if (cells != d[7] || c.window.scale.toString() != d[6]) {
                        differing++; which += i + 1
                        val dc = d[7].split(',').map { it.toInt() }
                        cellsOff += abs(dc[0] - c.cells.left) + abs(dc[1] - c.cells.top)
                    }
                }
                out.appendText(listOf(id, name, r.crops.scale, r.crops.k, r.analysis.glyphPx?.let { "%.3f".format(it) },
                    pages.size, differing, cellsOff, which.joinToString(",")).joinToString("\t") + "\n")
                println("$id $name scale=${r.crops.scale} k=${r.crops.k} glyph=${r.analysis.glyphPx} differing=$differing")
            }
            if (opts.containsKey("pixels") && dumpDir.exists()) pixelStats(id, dumpDir, renderers.filter { !it.startsWith("coarse") && it != "device" }, ::make, pixOut)
        } finally {
            opened.forEach { it.close() }
        }
    }
}

private suspend fun pixelStats(id: String, dir: File, renderers: List<String>, make: (String) -> PageRenderer, out: File) {
    val files = dir.listFiles()!!.filter { it.name.endsWith(".pgm") }
    val name = Regex("""p(\d+)_(\d+)x(\d+)\.pgm""")
    val parsed = files.mapNotNull { f -> name.matchEntire(f.name)?.groupValues?.let { Triple(it[1].toInt(), it[2].toInt(), it[3].toInt()) to f } }
    val coarseWidth = parsed.groupBy { it.first.first }.mapValues { (_, l) -> l.minOf { it.first.second } }
    for (rn in renderers) {
        val r = make(rn)
        for ((key, f) in parsed.sortedBy { it.first.first * 100000 + it.first.second }) {
            val (page, w, h) = key
            val kind = if (w == coarseWidth[page]) "coarse" else "fine"
            val dev = greyToArgb(Pnm.parse(f.readBytes()))
            val other = r.renderPixels(page - 1, w, h)
            val dl = IntArray(dev.size) { lum(dev[it]) }
            val ol = IntArray(other.size) { lum(other[it]) }
            val td = InkMask.inkThreshold(dl)
            val to = InkMask.inkThreshold(ol)
            val md = InkMask.fromArgb(dev, w, h)
            val mo = InkMask.fromArgb(other, w, h)
            var xor = 0
            for (y in 0 until h) for (x in 0 until w) if (md[x, y] != mo[x, y]) xor++
            var sum = 0L
            var max = 0
            for (i in dl.indices) { val d = abs(dl[i] - ol[i]); sum += d; if (d > max) max = d }
            val gd = if (kind == "fine") TextSize.glyphHeight(md) else null
            val go = if (kind == "fine") TextSize.glyphHeight(mo) else null
            out.appendText(listOf(id, rn, page, kind, w, h, td, to, md.inkCount, mo.inkCount, xor,
                "%.4f".format(sum.toDouble() / dl.size), max, gd?.let { "%.3f".format(it) }, go?.let { "%.3f".format(it) })
                .joinToString("\t") + "\n")
        }
    }
}

/**
 * Investigation hook: the renderer the labelled-paper commands (fit, zoom-experiment) use, from
 * the environment's ZOOM_EVAL_RENDERER: `mutool` (default), `device:DIR` (a CropDumpTest dump's
 * `renders/`), or `pdfium:PYTHON` (fidelity/pdfium-render.py run by PYTHON).
 */
fun rendererFor(pdf: File): PageRenderer {
    val spec = System.getenv("ZOOM_EVAL_RENDERER") ?: "mutool"
    val mutool = MutoolPdf(pdf)
    return when {
        spec == "mutool" -> mutool
        spec.startsWith("device:") -> DumpPages(File(spec.removePrefix("device:"), pdf.nameWithoutExtension), deviceSizes(mutool.pageSizes))
        spec.startsWith("pdfium:") -> PdfiumPages(pdf, spec.removePrefix("pdfium:"), File("fidelity/pdfium-render.py"))
        else -> throw IllegalArgumentException("ZOOM_EVAL_RENDERER=$spec")
    }
}
