package dev.sortes.zoomeval.fit

import dev.sortes.zoomeval.labeller.toMap
import java.io.File

/** Writes `report.md` and `trials.tsv` for a fit. */
class FitReport(private val papers: List<LabelledPaper>, private val out: File) {

    fun write(baseline: Trial, given: Trial?, tried: List<Trial>) {
        val best = tried.minBy { it.units }
        File(out, "report.md").writeText(markdown(baseline, given, best, tried))
        File(out, "trials.tsv").writeText(tsv(tried))
    }

    private fun f(x: Double, digits: Int = 2) = "%.${digits}f".format(x)

    private fun markdown(baseline: Trial, given: Trial?, best: Trial, tried: List<Trial>) = buildString {
        appendLine("# Crop model fit")
        appendLine()
        appendLine("${papers.size} labelled papers, ${tried.size} weight sets tried. Score: lower is better (see SPEC.md).")
        appendLine()
        appendLine("| paper | targets | target scale |")
        appendLine("| --- | --- | --- |")
        for (p in papers) {
            val t = p.targets
            appendLine("| ${p.paper.id} | ${t.pages.size} (${t.pages.groupingBy { it.why.substringBefore(" (") }.eachCount().entries.joinToString { "${it.value} ${it.key}" }}) | ${f(t.scale, 3)} (weight ${f(t.scaleWeight, 1)}) |")
        }
        appendLine()
        appendLine("## Best weights")
        appendLine()
        appendLine("Score ${f(baseline.units)} with the defaults, **${f(best.units)}** with: `${best.weights.diff()}`")
        given?.let { appendLine("\nGiven weights (`${it.weights.diff()}`): ${f(it.units)}") }
        appendLine()
        appendLine("| paper | scale now → fitted (target) | zoom units | page units | total |")
        appendLine("| --- | --- | --- | --- | --- |")
        for ((a, b) in baseline.scores.zip(best.scores)) {
            appendLine("| ${a.paper} | ${f(a.scale, 3)} → ${f(b.scale, 3)} (${f(a.targetScale, 3)}) | ${f(a.zoomUnits)} → ${f(b.zoomUnits)} | ${f(a.pageUnits)} → ${f(b.pageUnits)} | ${f(a.units)} → **${f(b.units)}** |")
        }
        appendLine()
        appendLine("## Held out")
        appendLine()
        appendLine("Each paper scored with the best weights fitted on the others: does the fit carry over?")
        appendLine()
        appendLine("| held-out paper | defaults | fitted without it | weights |")
        appendLine("| --- | --- | --- | --- |")
        var defaultsSum = 0.0
        var heldSum = 0.0
        for (p in papers) {
            val id = p.paper.id
            val others = tried.minBy { it.unitsWithout(id) }
            defaultsSum += baseline.unitsOf(id)
            heldSum += others.unitsOf(id)
            appendLine("| $id | ${f(baseline.unitsOf(id))} | ${f(others.unitsOf(id))} | `${others.weights.diff()}` |")
        }
        appendLine("| **all** | ${f(defaultsSum)} | **${f(heldSum)}** | |")
        appendLine()
        appendLine("## Pages that change")
        appendLine()
        appendLine("| paper | page | target | centre error (pt) | ideal ink cut (%) | units |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        for ((a, b) in baseline.scores.zip(best.scores)) {
            for ((pa, pb) in a.pages.zip(b.pages)) {
                if (kotlin.math.abs(pa.units - pb.units) < 0.05) continue
                val err = { s: PageScore -> s.centreError?.let { f(it, 1) } ?: "-" }
                appendLine("| ${a.paper} | ${pa.page} | ${pa.why} | ${err(pa)} → ${err(pb)} | ${f(pa.keepCutPercent)} → ${f(pb.keepCutPercent)} | ${f(pa.units)} → ${if (pb.units < pa.units) "**${f(pb.units)}** (better)" else "${f(pb.units)} (worse)"} |")
            }
        }
        appendLine()
        appendLine("## Where the defaults lose")
        appendLine()
        appendLine("| paper | page | target | centre error (pt) | ideal ink cut (%) | units |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        for (a in baseline.scores) for (pa in a.pages.sortedByDescending { it.units }) {
            appendLine("| ${a.paper} | ${pa.page} | ${pa.why} | ${pa.centreError?.let { f(it, 1) } ?: "-"} | ${f(pa.keepCutPercent)} | ${f(pa.units)} |")
        }
        appendLine()
        appendLine("## Top 10 weight sets")
        appendLine()
        appendLine("| score | weights |")
        appendLine("| --- | --- |")
        for (t in tried.sortedBy { it.units }.take(10)) appendLine("| ${f(t.units)} | `${t.weights.diff()}` |")
    }

    private fun tsv(tried: List<Trial>) = buildString {
        val names = tried.first().weights.toMap().keys.toList()
        appendLine((names + tried.first().scores.map { it.paper } + "total").joinToString("\t"))
        for (t in tried) {
            val w = t.weights.toMap()
            appendLine((names.map { "%.5g".format(w.getValue(it)) } + t.scores.map { f(it.units, 3) } + f(t.units, 3)).joinToString("\t"))
        }
    }
}
