package dev.sortes.zoomeval

import dev.sortes.app.zoom.analysisDensity
import dev.sortes.app.zoom.maskSize
import dev.sortes.app.zoom.withoutIgnorableInk
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

/** Weights from a properties file, then `--name=value` overrides. */
fun readWeights(file: File?, overrides: Map<String, String>): Weights {
    val props = Properties()
    file?.takeIf { it.exists() }?.reader()?.use { props.load(it) }
    overrides.forEach { (k, v) -> props.setProperty(k, v) }
    val d = Weights()
    return Weights(
        alpha = props.getProperty("alpha")?.toDouble() ?: d.alpha,
        beta = props.getProperty("beta")?.toDouble() ?: d.beta,
        gamma = props.getProperty("gamma")?.toDouble() ?: d.gamma,
        delta = props.getProperty("delta")?.toDouble() ?: d.delta,
        delta2 = props.getProperty("delta2")?.toDouble() ?: d.delta2,
        maxWindows = props.getProperty("maxWindows")?.toInt() ?: d.maxWindows,
    )
}

private val USAGE = """
    Usage: zoom-eval [--corpus=corpus.tsv] [--pdfs=DIR] [--out=DIR] [--aspect=W/H] [--weights=FILE]
                     [--alpha=..] [--beta=..] [--gamma=..] [--delta=..] [--delta2=..] [--maxWindows=..] [--only=id,id]
""".trimIndent()

fun main(args: Array<String>) {
    val opts = args.associate { arg ->
        require(arg.startsWith("--") && '=' in arg) { "Bad argument: $arg\n$USAGE" }
        arg.removePrefix("--").substringBefore('=') to arg.substringAfter('=')
    }
    val home = System.getProperty("user.home")
    val corpus = readCorpus(File(opts["corpus"] ?: "corpus.tsv"))
    val pdfs = File(opts["pdfs"] ?: "$home/.cache/sortes-zoom-eval/pdf")
    val out = File(opts["out"] ?: "$home/sortes-review/model").apply { mkdirs() }
    // The reader's view on the Boox Note Air5 C: 1860 x 2418 px below the status bar.
    val aspect = (opts["aspect"] ?: "0.7692").toFloat()
    val weights = readWeights(File(opts["weights"] ?: "weights.properties"), opts.filterKeys {
        it in setOf("alpha", "beta", "gamma", "delta", "delta2", "maxWindows")
    })
    val only = opts["only"]?.split(',')?.toSet()

    val report = Report(out, aspect, weights)
    for (paper in corpus) {
        if (only != null && paper.id !in only) continue
        val file = File(pdfs, paper.file)
        if (!file.exists()) {
            System.err.println("${paper.id}: missing ${file.path}; run fetch-corpus.sh")
            continue
        }
        val started = System.currentTimeMillis()
        val results = runBlocking { evaluate(paper, file, aspect, weights) }
        report.add(paper, results)
        report.write()
        println("${paper.id}: ${results.first.joinToString("  ") { "${it.method.label} K=${it.crops.k} %.3f".format(it.crops.total) }} (${System.currentTimeMillis() - started} ms)")
    }
    println("Wrote ${File(out, "index.html")}")
}

private suspend fun evaluate(paper: Paper, file: File, aspect: Float, weights: Weights): Pair<List<MethodResult>, MutoolPdf> {
    val pdf = MutoolPdf(file)
    // As the reader does: one density for the paper, from its page sizes.
    val density = analysisDensity(pdf.pageSizes, aspect)
    val raw = pdf.pageSizes.indices.map { i ->
        val (w, h) = maskSize(pdf.pageSizes[i], density)
        pdf.renderMask(i, w, h)
    }
    val model = CropModel(aspect, weights)
    val stripped = raw.map { it.withoutIgnorableInk() }
    val results = Method.entries.map { method ->
        val masks = if (method.stripsStamps) stripped else raw
        MethodResult(method, cropsFor(method, masks, aspect, model), masks)
    }
    return results to pdf
}

/** Writes the contact sheets and an index of every paper's cost breakdown. */
class Report(private val out: File, private val aspect: Float, private val weights: Weights) {
    private class Row(val paper: Paper, val pages: Int, val results: List<MethodResult>, val sheet: String)

    private val rows = mutableListOf<Row>()

    fun add(paper: Paper, evaluated: Pair<List<MethodResult>, MutoolPdf>) {
        val (results, pdf) = evaluated
        val sheet = "${paper.id}.png"
        val title = "${paper.id}  ${paper.title}  [${paper.category}]  ${pdf.pageCount} pages"
        val raw = results.first { !it.method.stripsStamps }.masks
        ImageIO.write(ContactSheet(pdf, aspect, raw, results, title).draw(), "png", File(out, sheet))
        rows += Row(paper, pdf.pageCount, results, sheet)
        File(out, "${paper.id}.pages.tsv").writeText(pageTsv(results))
    }

    /** Every page's window and terms under every method. */
    private fun pageTsv(results: List<MethodResult>) = buildString {
        appendLine("page\tmethod\twindow\tleft\ttop\tcutCells\tcutPagesOfInk\toffCentre")
        for (m in results) m.crops.pages.forEachIndexed { p, t ->
            val w = m.crops.windows[t.window]
            appendLine(
                listOf(p + 1, m.method.label, t.window + 1, "%.4f".format(w.left), "%.4f".format(w.top), t.cutCells,
                    "%.4f".format(t.cut), "%.4f".format(t.offCentre)).joinToString("\t"),
            )
        }
    }

    fun write() {
        File(out, "index.html").writeText(html())
        File(out, "summary.tsv").writeText(tsv())
    }

    /** Window numbers per page; windows past 9 are letters. */
    private fun assignment(c: Crops) = c.pages.joinToString("") { Character.forDigit(it.window + 1, 36).toString() }

    private fun tsv() = buildString {
        appendLine("paper\tcategory\tpages\tmethod\tscale\tK\tswitches\tcutPercent\tcutTerm\toffTerm\twindowTerm\tswitchTerm\ttotal\tassignment")
        for (r in rows) for (m in r.results) {
            val c = m.crops
            appendLine(
                listOf(
                    r.paper.id, r.paper.category, r.pages, m.method.label, "%.3f".format(c.scale), c.k, c.switches,
                    "%.4f".format(c.cutFraction * 100), "%.4f".format(c.cutTerm), "%.4f".format(c.offTerm),
                    "%.4f".format(c.windowTerm), "%.4f".format(c.switchTerm), "%.4f".format(c.total), assignment(c),
                ).joinToString("\t"),
            )
        }
    }

    private fun html() = buildString {
        appendLine("<!doctype html><meta charset=utf-8><title>Zoom crop model review</title>")
        appendLine("<style>body{font:14px sans-serif;margin:24px;max-width:1500px}table{border-collapse:collapse}")
        appendLine("td,th{border:1px solid #ccc;padding:3px 6px;text-align:right}td.l,th.l{text-align:left}")
        appendLine("code{font-size:12px}</style>")
        appendLine("<h1>Zoom crop model review</h1>")
        appendLine("<p>Aspect %.4f (Boox view). Weights: α=%s β=%s γ=%s δ=%s δ₂=%s, up to %d windows. See <code>tools/zoom-eval/SPEC.md</code>."
            .format(aspect, weights.alpha, weights.beta, weights.gamma, weights.delta, weights.delta2, weights.maxWindows))
        appendLine("Every method is scored with the model's cost on the masks it saw (the stamp variants don't count stamp ink).</p>")
        appendLine("<table><tr><th class=l>paper</th><th class=l>category</th><th>pages</th>")
        for (m in Method.entries) appendLine("<th>${m.label}<br>zoom · K · cut% · cost</th>")
        appendLine("</tr>")
        for (r in rows) {
            appendLine("<tr><td class=l><a href=\"${r.sheet}\">${r.paper.id}</a> ${r.paper.title}</td><td class=l>${r.paper.category}</td><td>${r.pages}</td>")
            for (m in r.results) {
                val c = m.crops
                appendLine("<td>%.2f× · %d · %.2f · %.3f</td>".format(1 / c.scale, c.k, c.cutFraction * 100, c.total))
            }
            appendLine("</tr>")
        }
        appendLine("</table>")
        for (r in rows) {
            appendLine("<h2 id=\"${r.paper.id}\">${r.paper.id} ${r.paper.title}</h2><p><a href=\"${r.sheet}\">contact sheet</a> · <a href=\"${r.paper.id}.pages.tsv\">per-page terms</a></p>")
            appendLine("<table><tr><th class=l>method</th><th>scale</th><th>K</th><th>switches</th><th>cut %</th><th>α·cut</th><th>β·off</th><th>γ·(K−1)</th><th>δ·switches + δ₂·breaks</th><th>total</th><th class=l>window per page</th></tr>")
            for (m in r.results) {
                val c = m.crops
                appendLine(
                    "<tr><td class=l>${m.method.label}</td><td>%.3f</td><td>%d</td><td>%d</td><td>%.3f</td><td>%.4f</td><td>%.4f</td><td>%.4f</td><td>%.4f</td><td>%.4f</td><td class=l><code>%s</code></td></tr>"
                        .format(c.scale, c.k, c.switches, c.cutFraction * 100, c.cutTerm, c.offTerm, c.windowTerm, c.switchTerm, c.total, assignment(c)),
                )
            }
            appendLine("</table>")
        }
    }
}
