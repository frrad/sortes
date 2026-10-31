package dev.sortes.zoomeval.fit

import dev.sortes.zoomeval.weightValues
import java.io.File

/** Writes `report.md` and `trials.tsv` for a fit. */
class FitReport(
    private val papers: List<LabelledPaper>,
    private val out: File,
    /**
     * A group of papers (e.g. one labelling round) to fit on and test the rest with, and vice
     * versa.
     */
    private val split: Set<String> = emptySet(),
    /**
     * Papers the search doesn't see: the best set is chosen without them, and scored on them after.
     */
    private val holdout: Set<String> = emptySet(),
) {
    private val train = papers.map { it.paper.id }.filter { it !in holdout }

    /** Writes the report: the best of [tried] on the training papers, or [descent]'s result. */
    fun write(baseline: Trial, given: Trial?, tried: List<Trial>, descent: Descent? = null) {
        val best = descent?.best ?: tried.minBy { it.unitsOn(train) }
        File(out, "report.md").writeText(markdown(baseline, given, best, tried, descent))
        File(out, "trials.tsv").writeText(tsv(tried))
    }

    private fun f(x: Double, digits: Int = 2) = "%.${digits}f".format(x)

    private fun markdown(
        baseline: Trial,
        given: Trial?,
        best: Trial,
        tried: List<Trial>,
        descent: Descent?,
    ) = buildString {
        appendLine("# Crop model fit")
        appendLine()
        appendLine(
            "${papers.size} labelled papers, ${tried.size} weight sets tried. Score: lower is better (see SPEC.md)."
        )
        appendLine()
        appendLine("| paper | targets | target scale |")
        appendLine("| --- | --- | --- |")
        for (p in papers) {
            val t = p.targets
            appendLine(
                "| ${p.paper.id} | ${t.pages.size} (${t.pages.groupingBy { it.why.substringBefore(" (") }.eachCount().entries.joinToString { "${it.value} ${it.key}" }}) | ${f(t.scale, 3)} (weight ${f(t.scaleWeight, 1)}) |"
            )
        }
        appendLine()
        appendLine("## Best weights")
        appendLine()
        appendLine(
            "Score ${f(baseline.units)} with ${baseline.config.name}, **${f(best.units)}** with `${best.diff()}`"
        )
        given?.let { appendLine("\nGiven weights (`${it.diff()}`): ${f(it.units)}") }
        if (holdout.isNotEmpty()) {
            val held = papers.map { it.paper.id }.filter { it in holdout }
            appendLine()
            appendLine(
                "Chosen on the ${train.size} training papers only: ${f(baseline.unitsOn(train))} → **${f(best.unitsOn(train))}** there. " +
                    "Held out (${held.joinToString()}): ${f(baseline.unitsOn(held))} → **${f(best.unitsOn(held))}**, " +
                    "pages agreeing ${agreeing(baseline, held)} → ${agreeing(best, held)} of ${baseline.scores.filter { it.paper in held }.sumOf { it.pages.size }}, " +
                    "zooms agreeing ${zoomsAgreeing(baseline, held)} → ${zoomsAgreeing(best, held)} of ${held.size}."
            )
        }
        appendLine()
        appendLine("| paper | scale now → fitted (target) | zoom units | page units | total |")
        appendLine("| --- | --- | --- | --- | --- |")
        for ((a, b) in baseline.scores.zip(best.scores)) {
            appendLine(
                "| ${a.paper} | ${f(a.scale, 3)} → ${f(b.scale, 3)} (${f(a.targetScale, 3)}) | ${f(a.zoomUnits)} → ${f(b.zoomUnits)} | ${f(a.pageUnits)} → ${f(b.pageUnits)} | ${f(a.units)} → **${f(b.units)}** |"
            )
        }
        appendLine()
        appendLine("## Held out")
        appendLine()
        appendLine(
            "Each paper scored with the best weights fitted on the others: does the fit carry over?"
        )
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
            appendLine(
                "| $id | ${f(baseline.unitsOf(id))} | ${f(others.unitsOf(id))} | `${others.diff()}` |"
            )
        }
        appendLine("| **all** | ${f(defaultsSum)} | **${f(heldSum)}** | |")
        if (split.isNotEmpty()) {
            val a = papers.map { it.paper.id }.filter { it in split }
            val b = papers.map { it.paper.id }.filter { it !in split }
            fun sum(t: Trial, ids: List<String>) = ids.sumOf { t.unitsOf(it) }
            appendLine()
            appendLine("### Held out by group")
            appendLine()
            appendLine("| fit on | test on | defaults on test | fitted on test | weights |")
            appendLine("| --- | --- | --- | --- | --- |")
            for ((train, test) in listOf(a to b, b to a)) {
                val fitted = tried.minBy { sum(it, train) }
                appendLine(
                    "| ${train.joinToString()} | ${test.joinToString()} | ${f(sum(baseline, test))} | **${f(sum(fitted, test))}** | `${fitted.diff()}` |"
                )
            }
        }
        appendLine()
        descent?.let { appendSearch(it) }
        appendAgreement(baseline)
        given?.let { appendAgreement(it) }
        if (best !== baseline && best !== given) appendAgreement(best)
        appendLine("## Pages that change")
        appendLine()
        appendLine("| paper | page | target | centre error (pt) | ideal ink cut (%) | units |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        for ((a, b) in baseline.scores.zip(best.scores)) {
            for ((pa, pb) in a.pages.zip(b.pages)) {
                if (kotlin.math.abs(pa.units - pb.units) < 0.05) continue
                val err = { s: PageScore -> s.centreError?.let { f(it, 1) } ?: "-" }
                appendLine(
                    "| ${a.paper} | ${pa.page} | ${pa.why} | ${err(pa)} → ${err(pb)} | ${f(pa.keepCutPercent)} → ${f(pb.keepCutPercent)} | ${f(pa.units)} → ${if (pb.units < pa.units) "**${f(pb.units)}** (better)" else "${f(pb.units)} (worse)"} |"
                )
            }
        }
        appendLine()
        appendLine("## Where the defaults lose")
        appendLine()
        appendLine("| paper | page | target | centre error (pt) | ideal ink cut (%) | units |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        for (a in baseline.scores) for (pa in a.pages.sortedByDescending { it.units }) {
            appendLine(
                "| ${a.paper} | ${pa.page} | ${pa.why} | ${pa.centreError?.let { f(it, 1) } ?: "-"} | ${f(pa.keepCutPercent)} | ${f(pa.units)} |"
            )
        }
        appendLine()
        appendLine("## Top 10 weight sets")
        appendLine()
        appendLine("| score | weights |")
        appendLine("| --- | --- |")
        for (t in tried.sortedBy { it.units }.take(10)) appendLine(
            "| ${f(t.units)} | `${t.diff()}` |"
        )
    }

    /**
     * Whether each labelled page agrees with its label in principle: window centre within
     * [AGREE_POINTS] of the owner's, and at most [AGREE_KEEP_PERCENT] of the page's ink cut that
     * the owner's crop keeps; and each paper's zoom within [AGREE_SCALE] of the owner's.
     */
    private fun StringBuilder.appendAgreement(t: Trial) {
        appendLine("## Agreement in principle (`${t.diff()}`)")
        appendLine()
        appendLine(
            "A page agrees if its window centre is within ${f(AGREE_POINTS, 0)} pt of the owner's (or of the crop they called good) and it cuts at most ${f(AGREE_KEEP_PERCENT)}% of the page's ink that the owner's crop keeps. A paper's zoom agrees within ${f(AGREE_SCALE, 3)} of the owner's scale."
        )
        appendLine()
        appendLine("| paper | zoom: model (owner) | pages agreeing | pages that don't |")
        appendLine("| --- | --- | --- | --- |")
        var agree = 0
        var total = 0
        for (s in t.scores) {
            val bad = s.pages.filter { !agrees(it) }
            agree += s.pages.size - bad.size
            total += s.pages.size
            val zoom =
                if (kotlin.math.abs(s.scale - s.targetScale) <= AGREE_SCALE + 1e-9) "agrees"
                else "**off**"
            appendLine(
                "| ${s.paper} | ${f(s.scale, 3)} (${f(s.targetScale, 3)}) $zoom | ${s.pages.size - bad.size} of ${s.pages.size} | " +
                    bad.joinToString {
                        "p${it.page}: ${it.centreError?.let { e -> "${f(e, 0)} pt" } ?: ""}${if (it.keepCutPercent > AGREE_KEEP_PERCENT) " cuts ${f(it.keepCutPercent)}%" else ""}"
                    } +
                    " |"
            )
        }
        appendLine("| **all** | | **$agree of $total** | |")
        appendLine()
    }

    private fun StringBuilder.appendSearch(d: Descent) {
        appendLine("## Search")
        appendLine()
        appendLine(
            "Coordinate descent on the ${d.train.size} training papers: each step tries the values around the current best and keeps the best if it gains more than ${f(d.minGain)} units there. " +
                "*Fragile*: training papers without which the step would have decided otherwise."
        )
        appendLine()
        appendLine("| sweep | weights | from | tried (training units) | kept | fragile |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        for (step in d.steps) {
            val names = step.coordinate.names
            fun values(t: Trial) =
                names.joinToString("/") { "%.4g".format(t.config.weightValues().getValue(it)) }
            val tried =
                step.candidates.joinToString(", ") { "${values(it)}: ${f(it.unitsOn(d.train))}" }
            val kept =
                if (step.moved)
                    "**${values(step.kept)}** (${f(step.incumbent.unitsOn(d.train) - step.kept.unitsOn(d.train))} better)"
                else "–"
            appendLine(
                "| ${step.sweep} | ${step.coordinate} | ${values(step.incumbent)}: ${f(step.incumbent.unitsOn(d.train))} | $tried | $kept | ${d.fragile(step).joinToString()} |"
            )
        }
        appendLine()
    }

    private fun agreeing(t: Trial, ids: Collection<String>) =
        t.scores.filter { it.paper in ids }.sumOf { s -> s.pages.count { agrees(it) } }

    private fun zoomsAgreeing(t: Trial, ids: Collection<String>) =
        t.scores.count {
            it.paper in ids && kotlin.math.abs(it.scale - it.targetScale) <= AGREE_SCALE + 1e-9
        }

    private fun agrees(p: PageScore) =
        (p.centreError ?: 0.0) <= AGREE_POINTS && p.keepCutPercent <= AGREE_KEEP_PERCENT

    private companion object {
        const val AGREE_POINTS = 8.0
        const val AGREE_KEEP_PERCENT = 0.25
        const val AGREE_SCALE = 0.015
    }

    private fun tsv(tried: List<Trial>) = buildString {
        val names = tried.first().config.weightValues().keys.toList()
        appendLine((names + tried.first().scores.map { it.paper } + "total").joinToString("\t"))
        for (t in tried) {
            val w = t.config.weightValues()
            appendLine(
                (names.map { "%.5g".format(w.getValue(it)) } +
                        t.scores.map { f(it.units, 3) } +
                        f(t.units, 3))
                    .joinToString("\t")
            )
        }
    }
}
