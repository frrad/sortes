package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.CropModel
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.ModelCrops
import dev.sortes.app.zoom.PageBlocks
import dev.sortes.app.zoom.analysisDensity
import dev.sortes.app.zoom.cutInk
import dev.sortes.app.zoom.maskSize
import dev.sortes.app.zoom.padded
import dev.sortes.app.zoom.regionOn
import kotlinx.coroutines.runBlocking
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * A crop config to review, as `name[:weight=value;...]` on the command line (overrides separated
 * by semicolons, since commas separate configs): a [CropConfig] by name, with any of its weights
 * overridden. The [label] names its images and columns.
 */
data class ConfigSpec(val label: String, val config: CropConfig) {
    val fileLabel: String get() = label.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        fun parse(spec: String): ConfigSpec {
            val name = spec.substringBefore(':')
            val base = CropConfig.named(name)
            if (':' !in spec) return ConfigSpec(spec, base)
            val overrides = spec.substringAfter(':').split(';').associate {
                require('=' in it) { "Bad override $it in $spec" }
                it.substringBefore('=').trim() to it.substringAfter('=').trim()
            }
            return ConfigSpec(spec, base.copy(name = spec, weights = base.weights.with(overrides)))
        }
    }
}

/**
 * `review --configs=reader,tuned[,tuned:gamma=0.2;kappa=0.1] [--only=id,id] [--out=DIR] [--width=360] [--scale=S]
 * [--corpus=] [--pdfs=] [--aspect=]`: writes, for every paper, one readable image per page and
 * config (the padded frame with the page, the window outlined, what's off screen shaded and cut
 * ink boxed in red), a per-paper HTML grid, and an index with each config's zoom, windows and
 * cut. Before/after material for judging crops by eye.
 */
fun reviewMain(args: List<String>) {
    val opts = args.associate { arg ->
        require(arg.startsWith("--") && '=' in arg) { "Bad argument: $arg" }
        arg.removePrefix("--").substringBefore('=') to arg.substringAfter('=')
    }
    val known = setOf("configs", "only", "out", "width", "corpus", "pdfs", "aspect", "title", "blocks", "scale")
    opts.keys.firstOrNull { it !in known }?.let { throw IllegalArgumentException("Unknown option --$it") }
    val home = System.getProperty("user.home")
    val corpus = readCorpus(File(opts["corpus"] ?: "corpus.tsv"))
    val pdfs = File(opts["pdfs"] ?: "$home/.cache/sortes-zoom-eval/pdf")
    val out = File(opts["out"] ?: "$home/sortes-review/model/fable").apply { mkdirs() }
    val aspect = (opts["aspect"] ?: "0.75").toFloat()
    val width = (opts["width"] ?: "360").toInt()
    val specs = (opts["configs"] ?: "reader,tuned").split(',').map { ConfigSpec.parse(it.trim()) }
    val only = opts["only"]?.split(',')?.toSet()
    val title = opts["title"] ?: "Crop review"
    // Outline each page's blocks (PageBlocks at this gap) in blue, to judge the segmentation.
    val blocks = opts["blocks"]?.toInt()
    // One fixed scale for every paper and config, as a manual zoom; by default each config's automatic one.
    val scale = opts["scale"]?.toFloat()

    val index = mutableListOf<String>()
    for (paper in corpus) {
        if (only != null && paper.id !in only) continue
        val file = File(pdfs, paper.file)
        if (!file.exists()) {
            System.err.println("${paper.id}: missing ${file.path}; run fetch-corpus.sh")
            continue
        }
        val started = System.currentTimeMillis()
        val pdf = MutoolPdf(file)
        val density = analysisDensity(pdf.pageSizes, aspect)
        val rendered = pdf.pageSizes.indices.map { i ->
            val (w, h) = maskSize(pdf.pageSizes[i], density)
            pdf.renderMask(i, w, h)
        }
        val results = specs.map { spec ->
            val masks = spec.config.prepare(rendered)
            val crops = runBlocking { cropsFor(Algorithm.READER, masks, CropModel(aspect, spec.config.weights), scale) }
            ReviewResult(spec, crops, masks, rendered)
        }
        writePaper(paper, pdf, aspect, width, results, out, blocks)
        index += indexRow(paper, pdf.pageCount, results)
        println("${paper.id}: ${results.joinToString("  ") { "%s %.3f K=%d cut %.2f%% sliced %d".format(it.spec.label, it.crops.scale, it.crops.k, it.crops.cutFraction * 100, it.slicedLinePages) }} (${System.currentTimeMillis() - started} ms)")
    }
    File(out, "index.html").writeText(buildString {
        appendLine("<!doctype html><meta charset=utf-8><title>$title</title>")
        appendLine(STYLE)
        appendLine("<h1>$title</h1><p>Configs: ${specs.joinToString { "<b>${it.label}</b>" }}. Each paper links to a page-by-page grid. Per config: zoom · windows · cut % · pages with a sliced line (top or bottom edge through ≥3 inked cells).</p>")
        appendLine("<table><tr><th class=l>paper</th><th>pages</th>${specs.joinToString("") { "<th>${it.label}</th>" }}</tr>")
        index.forEach { appendLine(it) }
        appendLine("</table>")
    })
    println("Wrote ${File(out, "index.html")}")
}

internal class ReviewResult(val spec: ConfigSpec, val crops: ModelCrops, val masks: List<InkMask>, val raw: List<InkMask>) {
    val slices = crops.pages.mapIndexed { p, c -> edgeSlices(masks[p], c.cells) }
    val slicedLinePages get() = slices.count { it.slicesLine() }
}

private fun indexRow(paper: Paper, pages: Int, results: List<ReviewResult>) = buildString {
    append("<tr><td class=l><a href=\"${paper.id}.html\">${paper.id}</a> ${paper.title} <small>[${paper.category}]</small></td><td>$pages</td>")
    for (r in results) {
        val c = r.crops
        append("<td>%.2f× (%.3f) · K%d · %.2f%% · %d sliced</td>".format(1 / c.scale, c.scale, c.k, c.cutFraction * 100, r.slicedLinePages))
    }
    append("</tr>")
}

private const val STYLE = "<style>body{font:14px sans-serif;margin:20px}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:3px 6px;text-align:right;vertical-align:top}td.l,th.l{text-align:left}img{display:block}small{color:#555}.cap{font:11px monospace;white-space:nowrap}</style>"

private val WINDOW_COLORS = listOf(Color(0x00, 0xA0, 0x00), Color(0x10, 0x50, 0xE0), Color(0xD0, 0x00, 0xD0))

internal fun writePaper(paper: Paper, pdf: MutoolPdf, aspect: Float, width: Int, results: List<ReviewResult>, out: File, blocks: Int?) {
    val dir = File(out, paper.id).apply { mkdirs() }
    val html = StringBuilder()
    html.appendLine("<!doctype html><meta charset=utf-8><title>${paper.id} ${paper.title}</title>$STYLE")
    html.appendLine("<h1>${paper.id} ${paper.title} <small>[${paper.category}] ${pdf.pageCount} pages</small></h1><p><a href=\"index.html\">index</a></p>")
    html.appendLine("<table><tr><th class=l>config</th><th>zoom</th><th>scale</th><th>K</th><th>breaks</th><th>cut %</th><th>worst page %</th><th>sliced-line pages</th><th class=l>window per page</th></tr>")
    for (r in results) {
        val c = r.crops
        html.appendLine("<tr><td class=l><b>${r.spec.label}</b></td><td>%.2f×</td><td>%.3f</td><td>%d</td><td>%d</td><td>%.3f</td><td>%.2f</td><td>%d</td><td class=l><code>%s</code></td></tr>".format(
            1 / c.scale, c.scale, c.k, c.breaks, c.cutFraction * 100, (c.pages.maxOfOrNull { it.pageCut } ?: 0.0) * 100, r.slicedLinePages,
            c.pages.joinToString("") { Character.forDigit(it.windowIndex + 1, 36).toString() }))
    }
    html.appendLine("</table><p>Green/blue/magenta outline: window W1/W2/W3. Shaded: off screen. Red boxes: ink the window cuts off, as the model counts it. Caption: window, cut share of the page's ink, off-centredness, sliced cells on the top/bottom/left/right edge.</p>")
    html.appendLine("<table><tr><th>page</th>${results.joinToString("") { "<th class=l>${it.spec.label}</th>" }}</tr>")
    for (p in 0 until pdf.pageCount) {
        val size = pdf.pageSizes[p]
        val frame = size.padded(aspect)
        val k = width / frame.width
        val page = pdf.renderImage(p, (size.width * k).roundToInt().coerceAtLeast(1), (size.height * k).roundToInt().coerceAtLeast(1))
        html.append("<tr><td>p${p + 1}</td>")
        for (r in results) {
            val name = "${r.spec.fileLabel}-p%03d.png".format(p + 1)
            ImageIO.write(cropImage(page, size.width, size.height, frame.width, frame.height, k, r, p, aspect, blocks), "png", File(dir, name))
            val t = r.crops.pages[p]
            val s = r.slices[p]
            html.append("<td class=l><img src=\"${paper.id}/$name\" width=$width><div class=cap>W${t.windowIndex + 1} cut %.2f%% off %.3f split %d slice t%d b%d l%d r%d</div></td>".format(t.pageCut * 100, t.offCentre, t.splitBlocks, s.top, s.bottom, s.left, s.right))
        }
        html.appendLine("</tr>")
    }
    html.appendLine("</table>")
    File(out, "${paper.id}.html").writeText(html.toString())
}

private fun cropImage(
    page: BufferedImage, pageW: Float, pageH: Float, frameW: Float, frameH: Float, k: Float,
    r: ReviewResult, p: Int, aspect: Float, blocks: Int?,
): BufferedImage {
    val w = (frameW * k).roundToInt()
    val h = (frameH * k).roundToInt()
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, w, h)
    val padX = (frameW - pageW) / 2
    val padY = (frameH - pageH) / 2
    g.drawImage(page, (padX * k).roundToInt(), (padY * k).roundToInt(), null)
    val t = r.crops.pages[p]
    val region = t.window.regionOn(dev.sortes.app.zoom.PageSize(pageW, pageH), aspect)
    val rx = (region.left + padX) * k
    val ry = (region.top + padY) * k
    val rw = region.width * k
    val rh = region.height * k
    if (blocks != null) {
        val mask = r.masks[p]
        val sx = pageW / mask.width
        val sy = pageH / mask.height
        g.color = Color(0x40, 0x80, 0xFF)
        g.stroke = BasicStroke(1f)
        for (b in PageBlocks.of(mask, blocks).boxes) {
            g.drawRect(((b.left * sx + padX) * k).roundToInt(), ((b.top * sy + padY) * k).roundToInt(),
                ((b.right - b.left) * sx * k).roundToInt(), ((b.bottom - b.top) * sy * k).roundToInt())
        }
    }
    // Cut ink, boxed.
    g.color = Color(0xE0, 0x10, 0x10)
    g.stroke = BasicStroke(1.5f)
    for (box in cutInk(r.masks[p], dev.sortes.app.zoom.PageSize(pageW, pageH), t.cells)) {
        val reg = box.region
        g.drawRect(((reg.left + padX) * k).roundToInt(), ((reg.top + padY) * k).roundToInt(), (reg.width * k).roundToInt().coerceAtLeast(2), (reg.height * k).roundToInt().coerceAtLeast(2))
    }
    // Shade what's off screen.
    g.color = Color(0, 0, 0, 50)
    g.fillRect(0, 0, w, ry.toInt())
    g.fillRect(0, (ry + rh).toInt(), w, h)
    g.fillRect(0, ry.toInt(), rx.toInt(), (ry + rh).toInt() - ry.toInt())
    g.fillRect((rx + rw).toInt(), ry.toInt(), w, (ry + rh).toInt() - ry.toInt())
    g.color = WINDOW_COLORS.getOrElse(t.windowIndex) { Color.GRAY }
    g.stroke = BasicStroke(2f)
    g.drawRect(rx.roundToInt(), ry.roundToInt(), rw.roundToInt() - 1, rh.roundToInt() - 1)
    g.color = Color.DARK_GRAY
    g.stroke = BasicStroke(1f)
    g.drawRect(0, 0, w - 1, h - 1)
    g.dispose()
    return img
}
