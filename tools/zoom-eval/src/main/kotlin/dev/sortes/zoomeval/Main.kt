package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropModel
import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.ModelCrops
import dev.sortes.app.zoom.analysisDensity
import dev.sortes.app.zoom.maskSize
import dev.sortes.app.zoom.withoutIgnorableInk
import dev.sortes.zoomeval.labeller.labellerExport
import dev.sortes.zoomeval.labeller.labellerServe
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO

/** A paper in the evaluation corpus. */
data class Paper(val id: String, val file: String, val category: String, val title: String)

/** Reads corpus.tsv: id, file name, category, title; '#' starts a comment. */
fun readCorpus(file: File): List<Paper> = file.readLines()
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .map { line ->
        val f = line.split('\t')
        require(f.size >= 4) { "Bad corpus line: $line" }
        Paper(f[0].trim(), f[1].trim(), f[2].trim(), f[3].trim())
    }

/** The reader's default weights, then a properties file's, then `--name=value` overrides. */
fun readWeights(file: File?, overrides: Map<String, String>): CropModelWeights {
    val props = Properties()
    file?.takeIf { it.exists() }?.reader()?.use { props.load(it) }
    val fromFile = props.stringPropertyNames().associateWith { props.getProperty(it).trim() }
    return CropModelWeights().with(fromFile).with(overrides)
}

/** What one column chose for a paper: its crops, the masks it saw and the masks before the ignorable-ink rules. */
class ColumnResult(
    val column: Column,
    val weights: CropModelWeights,
    val crops: ModelCrops,
    val masks: List<InkMask>,
    val raw: List<InkMask>,
    /** Time to choose the crops from the masks (analysis and solving, not rendering). */
    val millis: Long,
) {
    /** Where each page's window edges slice through ink, by [edgeSlices]. */
    val slices: List<EdgeSlices> = crops.pages.mapIndexed { p, c -> edgeSlices(masks[p], c.cells) }

    /** Pages whose top or bottom edge slices through a line. */
    val slicedLinePages: Int get() = slices.count { it.slicesLine() }

    /** Pages whose left or right edge slices through ink. */
    val slicedSidePages: Int get() = slices.count { it.slicesSide() }
}

private val USAGE = """
    Usage: zoom-eval labeller-export ... | labeller-serve ... (see labeller/README.md)
           zoom-eval [--corpus=corpus.tsv] [--columns=columns.tsv] [--pdfs=DIR] [--out=DIR]
                     [--aspect=W/H] [--scale=S] [--weights=FILE] [--<weight>=value ...] [--only=id,id]
    Weights: ${WEIGHT_NAMES.joinToString(", ")}
""".trimIndent()

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "labeller-export" -> return labellerExport(args.drop(1))
        "labeller-serve" -> return labellerServe(args.drop(1))
    }
    val opts = args.associate { arg ->
        require(arg.startsWith("--") && '=' in arg) { "Bad argument: $arg\n$USAGE" }
        arg.removePrefix("--").substringBefore('=') to arg.substringAfter('=')
    }
    val known = WEIGHT_NAMES + setOf("corpus", "columns", "pdfs", "out", "aspect", "scale", "weights", "only")
    opts.keys.firstOrNull { it !in known }?.let { throw IllegalArgumentException("Unknown option --$it\n$USAGE") }
    val home = System.getProperty("user.home")
    val corpus = readCorpus(File(opts["corpus"] ?: "corpus.tsv"))
    val columns = readColumns(File(opts["columns"] ?: "columns.tsv"))
    val pdfs = File(opts["pdfs"] ?: "$home/.cache/sortes-zoom-eval/pdf")
    val out = File(opts["out"] ?: "$home/sortes-review/model").apply { mkdirs() }
    // The reader's view on the Boox Note Air5 C: the whole 1860 x 2480 px screen, bars hidden.
    val aspect = (opts["aspect"] ?: "0.75").toFloat()
    // A fixed zoom for every paper, as a manual zoom in the reader; by default each column's own.
    val scale = opts["scale"]?.toFloat()?.also { require(it > 0f && it <= 1f) { "Bad scale: $it" } }
    val weights = readWeights(File(opts["weights"] ?: "weights.properties"), opts.filterKeys { it in WEIGHT_NAMES })
    val only = opts["only"]?.split(',')?.toSet()

    val report = Report(out, aspect, weights, columns)
    for (paper in corpus) {
        if (only != null && paper.id !in only) continue
        val file = File(pdfs, paper.file)
        if (!file.exists()) {
            System.err.println("${paper.id}: missing ${file.path}; run fetch-corpus.sh")
            continue
        }
        val started = System.currentTimeMillis()
        val pdf = MutoolPdf(file)
        val results = runBlocking { evaluate(pdf, columns, aspect, weights, scale) }
        report.add(paper, pdf, results)
        report.write()
        println("${paper.id}: ${results.joinToString("  ") { "${it.column.label} K=${it.crops.k} %.3f sliced ${it.slicedLinePages}/${it.crops.pages.size} (${it.millis} ms)".format(it.crops.total) }} (${System.currentTimeMillis() - started} ms)")
    }
    println("Wrote ${File(out, "index.html")}")
}

private suspend fun evaluate(
    pdf: MutoolPdf,
    columns: List<Column>,
    aspect: Float,
    weights: CropModelWeights,
    scale: Float?,
): List<ColumnResult> {
    // As the reader does: one density for the paper, from its page sizes.
    val density = analysisDensity(pdf.pageSizes, aspect)
    val rendered = HashMap<Int?, List<InkMask>>()
    return columns.map { column ->
        val raw = rendered.getOrPut(column.threshold) {
            pdf.pageSizes.indices.map { i ->
                val (w, h) = maskSize(pdf.pageSizes[i], density)
                pdf.renderMask(i, w, h, column.threshold)
            }
        }
        val masks = raw.map { it.withoutIgnorableInk() }
        val w = weights.with(column.overrides)
        val started = System.nanoTime()
        val crops = cropsFor(column.algorithm, masks, CropModel(aspect, w), scale)
        ColumnResult(column, w, crops, masks, raw, (System.nanoTime() - started) / 1_000_000)
    }
}

/** Writes the contact sheets and an index of every paper's cost breakdown. */
class Report(private val out: File, private val aspect: Float, private val weights: CropModelWeights, private val columns: List<Column>) {
    private class Row(val paper: Paper, val pages: Int, val results: List<ColumnResult>, val sheet: String)

    private val rows = mutableListOf<Row>()

    fun add(paper: Paper, pdf: MutoolPdf, results: List<ColumnResult>) {
        val sheet = "${paper.id}.png"
        val title = "${paper.id}  ${paper.title}  [${paper.category}]  ${pdf.pageCount} pages"
        ImageIO.write(ContactSheet(pdf, aspect, results, title).draw(), "png", File(out, sheet))
        rows += Row(paper, pdf.pageCount, results, sheet)
        File(out, "${paper.id}.pages.tsv").writeText(pageTsv(results))
    }

    fun write() {
        File(out, "index.html").writeText(html())
        File(out, "summary.tsv").writeText(tsv())
    }

    /** Every page's window and terms under every column. */
    private fun pageTsv(results: List<ColumnResult>) = buildString {
        appendLine("page\tcolumn\twindow\tleft\ttop\tcutCells\tcutPagesOfInk\tpageCut\toffCentre\tignoredCells\tsliceTop\tsliceBottom\tsliceLeft\tsliceRight")
        for (m in results) m.crops.pages.forEachIndexed { p, t ->
            val w = t.window
            appendLine(
                listOf(p + 1, m.column.label, t.windowIndex + 1, "%.4f".format(w.left), "%.4f".format(w.top), t.cutCells,
                    "%.4f".format(t.cut), "%.4f".format(t.pageCut), "%.4f".format(t.offCentre),
                    m.raw[p].inkCount - m.masks[p].inkCount, m.slices[p].top, m.slices[p].bottom, m.slices[p].left,
                    m.slices[p].right).joinToString("\t"),
            )
        }
    }

    /** Window numbers per page; windows past 9 are letters. */
    private fun assignment(c: ModelCrops) = c.pages.joinToString("") { Character.forDigit(it.windowIndex + 1, 36).toString() }

    private fun maxPageCut(c: ModelCrops) = c.pages.maxOfOrNull { it.pageCut } ?: 0.0

    private fun tsv() = buildString {
        appendLine("paper\tcategory\tpages\tcolumn\tscale\tK\tswitches\tbreaks\tcutPercent\tmaxPageCutPercent\tcutTerm\toffTerm\tpageCutTerm\twindowTerm\tswitchTerm\ttotal\tmillis\tassignment\tslicedLinePages\tslicedSidePages")
        for (r in rows) for (m in r.results) {
            val c = m.crops
            appendLine(
                listOf(
                    r.paper.id, r.paper.category, r.pages, m.column.label, "%.3f".format(c.scale), c.k, c.switches, c.breaks,
                    "%.4f".format(c.cutFraction * 100), "%.3f".format(maxPageCut(c) * 100), "%.4f".format(c.cutTerm),
                    "%.4f".format(c.offTerm), "%.4f".format(c.pageCutTerm), "%.4f".format(c.windowTerm),
                    "%.4f".format(c.switchTerm), "%.4f".format(c.total), m.millis, assignment(c), m.slicedLinePages,
                    m.slicedSidePages,
                ).joinToString("\t"),
            )
        }
    }

    private fun html() = buildString {
        appendLine("<!doctype html><meta charset=utf-8><title>Zoom crop model review</title>")
        appendLine("<style>body{font:14px sans-serif;margin:24px;max-width:1600px}table{border-collapse:collapse}")
        appendLine("td,th{border:1px solid #ccc;padding:3px 6px;text-align:right}td.l,th.l{text-align:left}")
        appendLine("code{font-size:12px}</style>")
        appendLine("<h1>Zoom crop model review</h1>")
        appendLine("<p>Aspect %.4f (Boox view). Base weights: <code>%s</code>. See <code>tools/zoom-eval/SPEC.md</code>.</p>".format(aspect, weights))
        appendLine("<p>Columns (all apply the reader's ignorable-ink rules; every column is scored with the model's cost under its own weights):</p><ul>")
        for (c in columns) {
            val threshold = c.threshold?.let { "luminance &lt; $it" } ?: "paper-relative (default)"
            val o = if (c.overrides.isEmpty()) "" else ", weights " + c.overrides.entries.joinToString { "${it.key}=${it.value}" }
            appendLine("<li><b>${c.label}</b>: ${c.algorithm.name.lowercase()}, ink $threshold$o</li>")
        }
        appendLine("</ul>")
        appendLine("<table><tr><th class=l>paper</th><th class=l>category</th><th>pages</th>")
        for (c in columns) appendLine("<th>${c.label}<br>zoom · K · cut% · worst page% · cost</th>")
        appendLine("</tr>")
        for (r in rows) {
            appendLine("<tr><td class=l><a href=\"${r.sheet}\">${r.paper.id}</a> ${r.paper.title}</td><td class=l>${r.paper.category}</td><td>${r.pages}</td>")
            for (m in r.results) {
                val c = m.crops
                appendLine("<td>%.2f× · %d · %.2f · %.1f · %.3f</td>".format(1 / c.scale, c.k, c.cutFraction * 100, maxPageCut(c) * 100, c.total))
            }
            appendLine("</tr>")
        }
        appendLine("</table>")
        for (r in rows) {
            appendLine("<h2 id=\"${r.paper.id}\">${r.paper.id} ${r.paper.title}</h2><p><a href=\"${r.sheet}\">contact sheet</a> · <a href=\"${r.paper.id}.pages.tsv\">per-page terms</a></p>")
            appendLine("<table><tr><th class=l>column</th><th>scale</th><th>K</th><th>switches</th><th>breaks</th><th>cut %</th><th>worst page %</th><th>α·cut</th><th>β·off</th><th>ε·page cut</th><th>γ·(K−1)</th><th>δ·switches + δ₂·breaks</th><th>total</th><th>ms</th><th class=l>window per page</th></tr>")
            for (m in r.results) {
                val c = m.crops
                appendLine(
                    "<tr><td class=l>${m.column.label}</td><td>%.3f</td><td>%d</td><td>%d</td><td>%d</td><td>%.3f</td><td>%.2f</td><td>%.4f</td><td>%.4f</td><td>%.4f</td><td>%.4f</td><td>%.4f</td><td>%.4f</td><td>%d</td><td class=l><code>%s</code></td></tr>"
                        .format(c.scale, c.k, c.switches, c.breaks, c.cutFraction * 100, maxPageCut(c) * 100, c.cutTerm, c.offTerm, c.pageCutTerm, c.windowTerm, c.switchTerm, c.total, m.millis, assignment(c)),
                )
            }
            appendLine("</table>")
        }
    }
}
