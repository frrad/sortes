package dev.sortes.zoomeval.content

import dev.sortes.app.zoom.*
import dev.sortes.zoomeval.*
import dev.sortes.zoomeval.fit.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import dev.sortes.zoomeval.labeller.toMap
import org.json.JSONObject

/** Offline experiment only. Baseline, target-cost audit, bounded exhaustive scale search. */
fun contentMain(args: List<String>) = runBlocking {
    val opts = args.associate {
        require(it.startsWith("--") && '=' in it) { "Expected --name=value: $it" }
        it.removePrefix("--").substringBefore('=') to it.substringAfter('=')
    }
    val known = WEIGHT_NAMES + setOf("only", "out", "labels", "corpus", "pdfs", "weights", "factor", "render", "risks", "minScale", "maxScale")
    require(opts.keys.all { it in known }) { "Unknown option: ${opts.keys - known}" }
    val out = File(opts["out"] ?: "${System.getProperty("user.home")}/sortes-review/content").apply { mkdirs() }
    val factor = (opts["factor"] ?: "4").toInt().also { require(it in 1..8) }
    val weights = (opts["weights"] ?: "0,0.002,0.01,0.05").split(',').map { it.toDouble().also { w -> require(w >= 0 && w.isFinite()) } }.distinct()
    val risks = (opts["risks"] ?: "0").split(',').map { it.toDouble().also { w -> require(w >= 0 && w.isFinite()) } }.distinct()
    val overrides = opts.filterKeys { it in WEIGHT_NAMES }
    val candidateConfig = CropConfig.TUNED.copy(weights=CropConfig.TUNED.weights.with(overrides))
    val suffix = overrides.toSortedMap().entries.joinToString("") { "-${it.key}-${it.value}" }
    val render = (opts["render"] ?: "false").also { require(it in setOf("true","false")) } == "true"
    val minScale = (opts["minScale"] ?: "0.60").toFloat()
    val maxScale = (opts["maxScale"] ?: "0.95").toFloat()
    require(minScale > 0 && maxScale <= 1 && minScale <= maxScale)
    val scales = CropAnalysis.defaultScales().filter { it in minScale-1e-6f..maxScale+1e-6f }
    require(scales.isNotEmpty())
    val corpus = readCorpus(File(opts["corpus"] ?: "corpus.tsv")).associateBy { it.id }
    val pdfs = File(opts["pdfs"] ?: "${System.getProperty("user.home")}/.cache/sortes-zoom-eval/pdf")
    val labels = File(opts["labels"] ?: "labels")
    val interpretations = readInterpretations(File(labels, "interpretation.tsv"))
    val only = opts["only"]?.split(',')?.toSet()
    require(only == null || only.all { it in corpus }) { "Unknown paper in --only" }
    val metadata = JSONObject().put("feature", "fine-components-v2").put("factor",factor)
        .put("weights",weights).put("risks",risks).put("minScale",minScale).put("maxScale",maxScale)
        .put("candidateWeights",JSONObject(candidateConfig.weights.toMap())).put("digitalNativeOnly",true)
        .put("fixedZoomControl",true)
    val hashes = JSONObject()
    metadata.put("pdfSha256",hashes)
    fun saveMetadata() = File(out,"parameters.json").writeText(metadata.toString(2)+"\n")
    saveMetadata()
    val summary = StringBuilder("paper\tconfig\tweight\trisk\tscale\ttargetScale\tagree\ttargets\tzoomAgrees\tscore\tK\tslicedLinePages\telementsLost\tworstElementsLost\tobjective\n")
    val audit = StringBuilder("paper\tpage\tscale\tbaseCost\tpreferredCost\tbaseInk\tpreferredInk\tbaseSplit\tpreferredSplit\tbaseOff\tpreferredOff\tbaseElements\tpreferredElements\n")
    val details = StringBuilder("paper\tconfig\tpage\terrorPt\tkeptInkCutPercent\tverdict\n")
    val curves = StringBuilder("paper\tweight\tscale\tmeanCost\tworstAdditionalCost\tlegibilityCost\tK\n")
    val links = mutableListOf<String>()
    for (file in labels.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }) {
        val l = dev.sortes.zoomeval.labeller.PaperLabels.fromText(file.readText())
        if (only != null && l.paper !in only) continue
        // Digital-native scope: the scan remains a separate later robustness test.
        val paper = corpus[l.paper] ?: error("${l.paper}: not in corpus")
        if (paper.category.contains("scan", ignoreCase=true)) continue
        if (l.pages.values.all { it.isEmpty } && l.idealScale == null) continue
        val pdfFile = File(pdfs,paper.file)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        pdfFile.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer,0,n) }
        }
        hashes.put(paper.id,digest.digest().joinToString("") { "%02x".format(it) })
        saveMetadata()
        val pdf = MutoolPdf(pdfFile)
        val p = labelledPaper(paper,pdf,l,interpretations[l.paper].orEmpty(),0.75f)
        val masks = CropConfig.TUNED.prepare(p.rendered)
        println("${paper.id}: rendering fine elements (${masks.size} pages, ${factor}x)")
        val elements = masks.indices.map { i ->
            val m = p.rendered[i]
            ElementLoss.of(pdf.renderMask(i,m.width*factor,m.height*factor),m,masks[i])
        }
        val byMask = masks.indices.associate { masks[it] to elements[it] }
        val baselineModel = CropModel(p.aspect,CropConfig.TUNED.weights,parallelism=1)
        val baseline = baselineModel.auto(masks)
        val reviews = mutableListOf(ReviewResult(ConfigSpec("baseline",CropConfig.TUNED),baseline,masks,p.rendered))
        fun record(name: String, weight: Double, risk: Double, c: ModelCrops, objective: Double) {
            val s = p.score(c)
            val agree = s.pages.count { (it.centreError ?: 0.0) <= 8.0 && it.keepCutPercent <= 0.25 }
            val losses = c.pages.mapIndexed { i, crop -> elements[i].lost(crop.cells) }
            val sliced = c.pages.indices.count { edgeSlices(masks[it],c.pages[it].cells).slicesLine() }
            summary.appendLine(listOf(paper.id,name,weight,risk,c.scale,s.targetScale,agree,s.pages.size,
                abs(s.scale-s.targetScale)<=0.015+1e-9,s.units,c.k,sliced,losses.sum(),losses.maxOrNull() ?: 0.0,objective).joinToString("\t"))
            for (page in s.pages) details.appendLine(listOf(paper.id,name,page.page,page.centreError,page.keepCutPercent,page.why).joinToString("\t"))
            println("  $name: scale=${c.scale}, agree=$agree/${s.pages.size}, score=${s.units}, K=${c.k}, slices=$sliced")
        }
        record("baseline",0.0,0.0,baseline,baseline.zoomObjective)
        // Page-local counterfactual at the current scale. Excludes window/sequence penalties;
        // this is a feature/ranking diagnostic, NOT a feasible paper-wide optimum claim.
        for (target in p.targets.pages.filter { l.pages[it.page]?.idealCentre != null }) {
            val i = target.page-1
            val before = baseline.pages[i]
            val wanted = windowAt(target.keepCentre,baseline.scale.toDouble(),p.sizes[i],p.aspect)
            val changed = baseline.pages.mapIndexed { j,c -> if (j==i) wanted else c.window }
            val after = baselineModel.score(masks,changed).pages[i]
            audit.appendLine(listOf(paper.id,i+1,baseline.scale,baselineModel.pageCost(before),baselineModel.pageCost(after),
                before.cut,after.cut,before.split,after.split,before.offCentre,after.offCentre,
                elements[i].lost(before.cells),elements[i].lost(after.cells)).joinToString("\t"))
        }
        for (weight in weights) {
            val model = CropModel(p.aspect,candidateConfig.weights,parallelism=1,
                additionalPageCost=if (weight==0.0) null else { mask,cells -> weight*byMask.getValue(mask).lost(cells) })
            val prepared = model.prepare(masks)
            if (weight > 0.0) {
                // Isolate placement from automatic zoom: the baseline scale is shared by ALL
                // pages. This is an ablation, not a new unified zoom objective.
                val fixed = prepared.solve(baseline.scale)
                val name = "elements-$weight-fixed-zoom$suffix"
                record(name,weight,0.0,fixed,fixed.zoomObjective)
                reviews += ReviewResult(ConfigSpec(name,candidateConfig),fixed,masks,p.rendered)
            }
            val candidates = scales.map { scale -> prepared.solve(scale).also { c ->
                curves.appendLine(listOf(paper.id,weight,scale,c.total/c.pages.size,
                    c.pages.maxOf { it.additionalCost },c.weights.zeta*scale,c.k).joinToString("\t"))
            } }
            for (risk in risks) {
                // Sensitivity only: placements minimise the MEAN objective, then scales are ranked
                // by mean + risk*worst. This is NOT a joint minimax placement solver.
                fun value(c: ModelCrops) = c.zoomObjective + risk*c.pages.maxOf { it.additionalCost }
                val best = candidates.minBy { value(it) }
                val chosenScale = (best.scale+candidateConfig.weights.autoMargin.toFloat()).coerceAtMost(1f)
                val c = prepared.solve(chosenScale)
                val name = "elements-${weight}-risk-$risk$suffix"
                record(name,weight,risk,c,value(c))
                reviews += ReviewResult(ConfigSpec(name,candidateConfig),c,masks,p.rendered)
            }
        }
        if (render) writePaper(paper,pdf,p.aspect,480,reviews,out,null)
        links += if (render) "<li><a href=\"${paper.id}.html\">${paper.id}</a></li>" else "<li>${paper.id}</li>"
        // Write after every paper: interrupted runs retain their completed observations.
        File(out,"summary.tsv").writeText(summary.toString())
        File(out,"audit.tsv").writeText(audit.toString())
        File(out,"pages.tsv").writeText(details.toString())
        File(out,"curves.tsv").writeText(curves.toString())
    }
    require(links.isNotEmpty()) { "No labelled digital-native papers selected" }
    File(out,"index.html").writeText("<!doctype html><meta charset=utf-8><h1>Content experiment</h1><p>Training experiment; clean PDFs only. Exhaustive scale grid $minScale–$maxScale, followed by the existing margin. Fixed-scale multi-window solving remains heuristic. Risk columns only rerank mean-objective placements.</p><p><a href=\"summary.tsv\">Scores</a> · <a href=\"audit.tsv\">Target-cost audit</a> · <a href=\"curves.tsv\">Scale curves</a></p><ul>${links.joinToString("")}</ul>")
}
