package dev.sortes.zoomeval.content

import dev.sortes.app.zoom.*
import dev.sortes.zoomeval.*
import dev.sortes.zoomeval.fit.*
import dev.sortes.zoomeval.labeller.PaperLabels
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs

/**
 * Offline experiment: automatic zoom chosen by a text-size-aware legibility cost ([Legibility]),
 * optionally with the component ([ElementLoss]) and truncated-line ([LineTruncation]) page
 * costs. Every scale of the grid is solved once per page-cost setting, then each legibility
 * spec (and worst-page risk) picks its scale from those solves, so a large grid is cheap.
 *
 * Writes `summary.tsv` (one row per paper and setting) and `paper.tsv` (each paper's text size).
 * `newIgnoredPages` counts pages whose window now shows ink the rules ignore (page numbers,
 * running heads, stamps) that the baseline's window didn't: the "don't start showing page
 * numbers" regression check.
 */
fun zoomExperimentMain(args: List<String>) = runBlocking {
    val opts = args.associate {
        require(it.startsWith("--") && '=' in it) { "Expected --name=value: $it" }
        it.removePrefix("--").substringBefore('=') to it.substringAfter('=')
    }
    val known = setOf("only", "out", "labels", "corpus", "pdfs", "factor", "elements", "lines", "risks", "zeta",
        "T", "n", "floor", "minScale", "maxScale", "render", "renderSpecs", "lineGap", "lineSaturation")
    require(opts.keys.all { it in known }) { "Unknown option: ${opts.keys - known}" }
    fun doubles(name: String, default: String) = (opts[name] ?: default).split(',').map { it.toDouble() }
    val out = File(opts["out"] ?: "${System.getProperty("user.home")}/sortes-review/zoom-experiment").apply { mkdirs() }
    val factor = (opts["factor"] ?: "4").toInt()
    val elementWeights = doubles("elements", "0")
    val lineWeights = doubles("lines", "0")
    val risks = doubles("risks", "0")
    val zeta = (opts["zeta"] ?: CropConfig.TUNED.weights.zeta.toString()).toDouble()
    val lineGap = (opts["lineGap"] ?: "1.5").toDouble()
    val lineSaturation = (opts["lineSaturation"] ?: "2").toDouble()
    val specs = listOf(Legibility.linear(zeta)) + doubles("T", "").let { ts ->
        ts.flatMap { t -> doubles("n", "4").flatMap { n -> doubles("floor", "0.1").map { f -> Legibility(zeta, t, n, f) } } }
    }
    val renderSpecs = opts["renderSpecs"]?.split(',').orEmpty()
    val render = opts["render"] == "true"
    val minScale = (opts["minScale"] ?: "0.60").toFloat()
    val maxScale = (opts["maxScale"] ?: "0.97").toFloat()
    val scales = CropAnalysis.defaultScales().filter { it in minScale - 1e-6f..maxScale + 1e-6f }
    val corpus = readCorpus(File(opts["corpus"] ?: "corpus.tsv")).associateBy { it.id }
    val pdfs = File(opts["pdfs"] ?: "${System.getProperty("user.home")}/.cache/sortes-zoom-eval/pdf")
    val labels = File(opts["labels"] ?: "labels")
    val interpretations = readInterpretations(File(labels, "interpretation.tsv"))
    val only = opts["only"]?.split(',')?.toSet()
    val base = CropConfig.TUNED
    val margin = base.weights.autoMargin.toFloat()

    val summary = StringBuilder("paper\telements\tlines\tlegibility\trisk\tscale\ttargetScale\tagree\ttargets\tzoomAgrees\tscore\tK\tslicedLinePages\tnewIgnoredPages\ttruncatedLines\n")
    val paperInfo = StringBuilder("paper\tglyphPxAtScale1\tbaselineScale\ttargetScale\ttextPxAtTarget\ttextPxAtBaseline\n")
    val pageRows = StringBuilder("paper\tconfig\tpage\terrorPt\tkeptInkCutPercent\tverdict\n")
    val links = mutableListOf<String>()
    for (file in labels.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }) {
        val l = PaperLabels.fromText(file.readText())
        if (only != null && l.paper !in only) continue
        val paper = corpus[l.paper] ?: error("${l.paper}: not in corpus")
        if (paper.category.contains("scan", ignoreCase = true)) continue
        if (l.pages.values.all { it.isEmpty } && l.idealScale == null) continue
        val pdf = MutoolPdf(File(pdfs, paper.file))
        val p = labelledPaper(paper, pdf, l, interpretations[l.paper].orEmpty(), 0.75f)
        val masks = base.prepare(p.rendered)
        println("${paper.id}: ${masks.size} pages")
        val fine = masks.indices.map { pdf.renderMask(it, p.rendered[it].width * factor, p.rendered[it].height * factor) }
        // Glyph height on screen at scale 1: the frame (scale 1) fills the screen's width.
        val k = TextSize.median(fine.indices.map { i ->
            TextSize.glyphHeight(fine[i])?.let { it / factor / PaddedPage(masks[i], p.aspect).width * SCREEN_WIDTH }
        }) ?: error("${paper.id}: no text found")
        val elements = masks.indices.map { ElementLoss.of(fine[it], p.rendered[it], masks[it]) }
        val lines = masks.indices.map { LineTruncation.of(fine[it], p.rendered[it], masks[it], lineGap, lineSaturation) }
        val byMask = masks.indices.associateBy { masks[it] }

        val baseline = CropModel(p.aspect, base.weights, parallelism = 1).auto(masks)
        val target = p.score(baseline).targetScale
        paperInfo.appendLine(listOf(paper.id, k, baseline.scale, target, k / target, k / baseline.scale).joinToString("\t"))
        val reviews = mutableListOf(ReviewResult(ConfigSpec("baseline", base), baseline, masks, p.rendered))

        fun ignoredInside(i: Int, cells: CellRect): Int {
            var n = 0
            for (y in maxOf(0, cells.top) until minOf(masks[i].height, cells.bottom))
                for (x in maxOf(0, cells.left) until minOf(masks[i].width, cells.right))
                    if (p.rendered[i][x, y] && !masks[i][x, y]) n++
            return n
        }
        val baselineIgnored = masks.indices.map { ignoredInside(it, baseline.pages[it].cells) }
        fun record(eta: Double, lambda: Double, spec: String, risk: Double, c: ModelCrops) {
            val s = p.score(c)
            val agree = s.pages.count { (it.centreError ?: 0.0) <= 8.0 && it.keepCutPercent <= 0.25 }
            val sliced = c.pages.indices.count { edgeSlices(masks[it], c.pages[it].cells).slicesLine() }
            val newIgnored = c.pages.indices.count { ignoredInside(it, c.pages[it].cells) > baselineIgnored[it] }
            val truncated = c.pages.indices.sumOf { lines[it].truncated(c.pages[it].cells) }
            summary.appendLine(listOf(paper.id, eta, lambda, spec, risk, c.scale, s.targetScale, agree, s.pages.size,
                abs(s.scale - s.targetScale) <= 0.015 + 1e-9, s.units, c.k, sliced, newIgnored, truncated).joinToString("\t"))
            val name = "e$eta-l$lambda-$spec-r$risk"
            for (page in s.pages) pageRows.appendLine(listOf(paper.id, name, page.page, page.centreError, page.keepCutPercent, page.why).joinToString("\t"))
        }
        record(0.0, 0.0, "baseline", 0.0, baseline)
        // Each spec's zoom from the plain model (no extra page costs): the extra costs can then
        // place windows at that zoom without moving it ("placed" rows).
        val plainZoom = HashMap<String, Float>()
        val wantPlain = 0.0 in elementWeights && 0.0 in lineWeights
        val settings = (listOf(0.0 to 0.0) + elementWeights.flatMap { e -> lineWeights.map { e to it } }).distinct()
        for ((eta, lambda) in settings) {
            val extra: ((InkMask, CellRect) -> Double)? = if (eta == 0.0 && lambda == 0.0) null else { mask, cells ->
                val i = byMask.getValue(mask)
                eta * elements[i].lost(cells) + lambda * lines[i].cost(cells)
            }
            val model = CropModel(p.aspect, base.weights, parallelism = 1, additionalPageCost = extra)
            val prepared = model.prepare(masks)
            val solved = HashMap<Float, ModelCrops>()
            suspend fun at(scale: Float) = solved.getOrPut(scale) { prepared.solve(scale) }
            for (scale in scales) at(scale)
            for (spec in specs) for (risk in risks) {
                fun value(c: ModelCrops) = c.total / c.pages.size + spec.cost(c.scale.toDouble(), k) +
                    risk * c.pages.maxOf { it.additionalCost }
                val best = scales.minBy { value(solved.getValue(it)) }
                val chosen = scales.firstOrNull { abs(it - (best + margin)) < 1e-4f } ?: (best + margin).coerceAtMost(1f)
                val c = at(chosen)
                if (eta == 0.0 && lambda == 0.0) plainZoom["${spec.label}-r$risk"] = chosen
                if (eta == 0.0 && lambda == 0.0 && !wantPlain) continue
                record(eta, lambda, spec.label, risk, c)
                val name = "e$eta-l$lambda-${spec.label}-r$risk"
                if (name in renderSpecs) reviews += ReviewResult(ConfigSpec(name, base), c, masks, p.rendered)
                if (eta == 0.0 && lambda == 0.0) continue
                val placed = at(plainZoom.getValue("${spec.label}-r$risk"))
                record(eta, lambda, "${spec.label}-placed", risk, placed)
                val placedName = "e$eta-l$lambda-${spec.label}-placed-r$risk"
                if (placedName in renderSpecs) reviews += ReviewResult(ConfigSpec(placedName, base), placed, masks, p.rendered)
            }
        }
        if (render) writePaper(paper, pdf, p.aspect, 480, reviews, out, null)
        links += "<li><a href=\"${paper.id}.html\">${paper.id}</a> ${paper.title}</li>"
        File(out, "summary.tsv").writeText(summary.toString())
        File(out, "paper.tsv").writeText(paperInfo.toString())
        File(out, "pages.tsv").writeText(pageRows.toString())
    }
    File(out, "index.html").writeText("<!doctype html><meta charset=utf-8><h1>Zoom experiment</h1>" +
        "<p>Columns: ${(listOf("baseline") + renderSpecs).joinToString(" · ")}</p><ul>${links.joinToString("")}</ul>")
}

/** The Boox Note Air5 C's screen width, in pixels. */
const val SCREEN_WIDTH = 1860.0
