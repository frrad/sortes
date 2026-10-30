package dev.sortes.zoomeval.content

import dev.sortes.app.zoom.CellRect
import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.LineTruncation
import dev.sortes.app.zoom.renderMask
import dev.sortes.zoomeval.ConfigSpec
import dev.sortes.zoomeval.MutoolPdf
import dev.sortes.zoomeval.ReviewResult
import dev.sortes.zoomeval.edgeSlices
import dev.sortes.zoomeval.fit.cellsOf
import dev.sortes.zoomeval.fit.labelledPaper
import dev.sortes.zoomeval.fit.readInterpretations
import dev.sortes.zoomeval.fit.windowAt
import dev.sortes.zoomeval.labeller.PaperLabels
import dev.sortes.zoomeval.readCorpus
import dev.sortes.zoomeval.readerCrops
import dev.sortes.zoomeval.writePaper
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs

/**
 * `zoom-experiment [--T=28,...] [--n=16,...] [--floor=0.1,...] [--elements=0.01,...] [--lines=0.1,...]
 * [--zeta=0.5] [--only=id,id] [--render=true] [--out=] [--labels=] [--corpus=] [--pdfs=]`: scores
 * variations of [CropConfig.DEFAULT]'s text model on the labelled clean papers. Each combination of
 * the listed values is a config, and each config's crops are the reader's: [readerCrops], through
 * [dev.sortes.app.zoom.analyzePaper]. `--T=Infinity` is the plain zeta·scale zoom.
 *
 * Writes `summary.tsv` (one row per paper and config), `pages.tsv` and `paper.tsv` (each paper's
 * text size). `strictAgree` also asks that no line segment be clipped that the owner's crop shows
 * whole; `newIgnoredPages` counts pages whose window shows ink the rules ignore (page numbers,
 * running heads) that the first config's didn't.
 */
fun zoomExperimentMain(args: List<String>) = runBlocking {
    val opts = args.associate {
        require(it.startsWith("--") && '=' in it) { "Expected --name=value: $it" }
        it.removePrefix("--").substringBefore('=') to it.substringAfter('=')
    }
    val known = setOf("only", "out", "labels", "corpus", "pdfs", "elements", "lines", "zeta", "T", "n", "floor", "render")
    require(opts.keys.all { it in known }) { "Unknown option: ${opts.keys - known}" }
    val base = CropConfig.DEFAULT
    fun doubles(name: String, default: Double) = opts[name]?.split(',')?.map { it.toDouble() } ?: listOf(default)
    val zeta = (opts["zeta"] ?: base.weights.zeta.toString()).toDouble()
    val configs = doubles("elements", base.text.elements).flatMap { e ->
        doubles("lines", base.text.lines).flatMap { l ->
            doubles("T", base.text.comfortable).flatMap { t ->
                doubles("n", base.text.power).flatMap { n ->
                    doubles("floor", base.text.floor).map { f ->
                        val text = base.text.copy(elements = e, lines = l, comfortable = t, power = n, floor = f)
                        val weights = base.weights.copy(zeta = zeta)
                        val label = "e$e-l$l-T$t-n$n-f$f" + if (zeta != base.weights.zeta) "-z$zeta" else ""
                        if (text == base.text && weights == base.weights) base else base.copy(name = label, weights = weights, text = text)
                    }
                }
            }
        }
    }.distinct()
    val out = File(opts["out"] ?: "${System.getProperty("user.home")}/sortes-review/zoom-experiment").apply { mkdirs() }
    val render = opts["render"] == "true"
    val corpus = readCorpus(File(opts["corpus"] ?: "corpus.tsv")).associateBy { it.id }
    val pdfs = File(opts["pdfs"] ?: "${System.getProperty("user.home")}/.cache/sortes-zoom-eval/pdf")
    val labels = File(opts["labels"] ?: "labels")
    val interpretations = readInterpretations(File(labels, "interpretation.tsv"))
    val only = opts["only"]?.split(',')?.toSet()

    val summary = StringBuilder("paper\tconfig\tscale\ttargetScale\tagree\ttargets\tzoomAgrees\tscore\tK\tslicedLinePages\tnewIgnoredPages\ttruncatedLines\tstrictAgree\tclippedLines\tglyphPx\n")
    val paperInfo = StringBuilder("paper\tglyphPxAtScale1\tscale\ttargetScale\ttextPxAtTarget\ttextPxAtScale\n")
    val pageRows = StringBuilder("paper\tconfig\tpage\terrorPt\tkeptInkCutPercent\tverdict\tclippedLines\n")
    val links = mutableListOf<String>()
    for (file in labels.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }) {
        val l = PaperLabels.fromText(file.readText())
        if (only != null && l.paper !in only) continue
        val paper = corpus[l.paper] ?: error("${l.paper}: not in corpus")
        if (paper.category.contains("scan", ignoreCase = true)) continue
        if (l.pages.values.all { it.isEmpty } && l.idealScale == null) continue
        val pdf = MutoolPdf(File(pdfs, paper.file))
        val p = labelledPaper(paper, pdf, l, interpretations[l.paper].orEmpty(), 0.75f)
        println("${paper.id}: ${p.rendered.size} pages")
        val reviews = mutableListOf<ReviewResult>()
        var reference: List<Int>? = null
        // Lines of the labelled pages, for strict agreement: measured on the first config's masks.
        var lines: Map<Int, LineTruncation>? = null
        for (config in configs) {
            val r = readerCrops(p.pages, p.aspect, config)
            val c = r.crops
            val masks = r.masks
            fun ignoredInside(i: Int, cells: CellRect): Int {
                var n = 0
                for (y in maxOf(0, cells.top) until minOf(masks[i].height, cells.bottom))
                    for (x in maxOf(0, cells.left) until minOf(masks[i].width, cells.right))
                        if (p.rendered[i][x, y] && !masks[i][x, y]) n++
                return n
            }
            val ignored = masks.indices.map { ignoredInside(it, c.pages[it].cells) }
            val referenceIgnored = reference ?: ignored.also { reference = it }
            val pageLines = lines ?: p.targets.pages.associate { t ->
                val i = t.page - 1
                val raw = p.rendered[i]
                val f = config.text.factor
                i to LineTruncation.of(p.pages.renderMask(i, raw.width * f, raw.height * f), raw, masks[i], config.text.lineGap, config.text.lineSaturation)
            }.also { lines = it }
            val s = p.score(c)
            val agree = s.pages.count { (it.centreError ?: 0.0) <= 8.0 && it.keepCutPercent <= 0.25 }
            val clipped = p.targets.pages.associate { t ->
                val i = t.page - 1
                val ideal = cellsOf(windowAt(t.keepCentre, p.targets.scale, p.sizes[i], p.aspect), masks[i], p.aspect)
                t.page to pageLines.getValue(i).clippedAgainst(c.pages[i].cells, ideal)
            }
            val strict = s.pages.count { (it.centreError ?: 0.0) <= 8.0 && it.keepCutPercent <= 0.25 && clipped.getValue(it.page) == 0 }
            val sliced = c.pages.indices.count { edgeSlices(masks[it], c.pages[it].cells).slicesLine() }
            val newIgnored = c.pages.indices.count { ignored[it] > referenceIgnored[it] }
            val truncated = p.targets.pages.sumOf { t -> pageLines.getValue(t.page - 1).truncated(c.pages[t.page - 1].cells) }
            val k = r.analysis.glyphPx
            summary.appendLine(listOf(paper.id, config.name, c.scale, s.targetScale, agree, s.pages.size,
                abs(s.scale - s.targetScale) <= 0.015 + 1e-9, s.units, c.k, sliced, newIgnored, truncated, strict, clipped.values.sum(), k).joinToString("\t"))
            if (config === configs.first()) paperInfo.appendLine(listOf(paper.id, k, c.scale, s.targetScale, k?.div(s.targetScale), k?.div(c.scale)).joinToString("\t"))
            for (page in s.pages) pageRows.appendLine(listOf(paper.id, config.name, page.page, page.centreError, page.keepCutPercent, page.why, clipped.getValue(page.page)).joinToString("\t"))
            println("  ${config.name}: scale ${c.scale}, agree $agree/${s.pages.size} (strict $strict), score ${"%.2f".format(s.units)}, K=${c.k}")
            if (render) reviews += ReviewResult(ConfigSpec(config.name, config), c, masks)
        }
        if (render) writePaper(paper, pdf, p.aspect, 480, reviews, out, null)
        links += "<li><a href=\"${paper.id}.html\">${paper.id}</a> ${paper.title}</li>"
        File(out, "summary.tsv").writeText(summary.toString())
        File(out, "paper.tsv").writeText(paperInfo.toString())
        File(out, "pages.tsv").writeText(pageRows.toString())
    }
    File(out, "index.html").writeText("<!doctype html><meta charset=utf-8><h1>Zoom experiment</h1>" +
        "<p>Columns: ${configs.joinToString(" · ") { it.name }}</p><ul>${links.joinToString("")}</ul>")
}
