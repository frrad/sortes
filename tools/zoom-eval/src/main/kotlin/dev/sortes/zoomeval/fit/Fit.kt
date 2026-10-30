package dev.sortes.zoomeval.fit

import dev.sortes.app.zoom.CropModel
import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.IsolatedMarks
import dev.sortes.app.zoom.ModelCrops
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.analysisDensity
import dev.sortes.app.zoom.maskSize
import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.IGNORABLE_INK_RULES
import dev.sortes.zoomeval.Algorithm
import dev.sortes.zoomeval.MutoolPdf
import dev.sortes.zoomeval.Paper
import dev.sortes.zoomeval.cropsFor
import dev.sortes.zoomeval.labeller.PaperLabels
import dev.sortes.zoomeval.labeller.toMap
import dev.sortes.zoomeval.with
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/** Changes to the model beyond its weights, prototyped against the labels. */
data class Variant(
    /** Strip page numbers and running heads and feet ([PageFurniture]) before the model sees the pages. */
    val furniture: Boolean = false,
    /** Strip isolated marks ([IsolatedMarks]: specks, scan edges) first. */
    val marks: Boolean = false,
) {
    override fun toString() = listOfNotNull("furniture".takeIf { furniture }, "marks".takeIf { marks })
        .joinToString("+").ifEmpty { "reader" }

    /** This variant as the shared [CropConfig] (the same code the reader would run), with [weights]. */
    fun config(weights: CropModelWeights = CropModelWeights()) = CropConfig(
        toString(),
        IGNORABLE_INK_RULES + listOfNotNull(IsolatedMarks.takeIf { marks }),
        furniture,
        weights,
    )

    companion object {
        /** The variant of a config's rules: [CropConfig.TUNED] is furniture+marks. */
        fun of(config: CropConfig) = Variant(config.furniture, IsolatedMarks in config.pageRules)

        /**
         * A variant by name: "reader", a config's name ("tuned"), or rules joined by "+"
         * ("furniture+marks"; "reader+furniture+marks" as labels record it).
         */
        fun parse(full: String) = when (val name = full.removeSuffix("+weights")) {
            "reader" -> Variant()
            in CropConfig.ALL.map { it.name } -> of(CropConfig.named(name))
            else -> name.removePrefix("reader+").split('+').fold(Variant()) { v, part ->
                when (part) {
                    "furniture" -> v.copy(furniture = true)
                    "marks" -> v.copy(marks = true)
                    else -> throw IllegalArgumentException("Unknown variant part $part")
                }
            }
        }
    }
}

/** A labelled paper, rendered once, with what its labels ask. */
class LabelledPaper(
    val paper: Paper,
    /** The pages as rendered, before any ignorable-ink rules. */
    val rendered: List<InkMask>,
    /** ...and without furniture or marks: what scoring counts as ink, since the owner doesn't mind losing those. */
    val bare: List<InkMask>,
    val sizes: List<PageSize>,
    val labels: PaperLabels,
    val targets: PaperTargets,
    val aspect: Float,
) {
    /** The paper's crops under [weights] and [variant], as the reader would choose them. */
    private val prepared = HashMap<Variant, List<InkMask>>()

    suspend fun crops(weights: CropModelWeights, variant: Variant = Variant()): ModelCrops =
        cropsFor(Algorithm.READER, prepared.getOrPut(variant) { variant.config().prepare(rendered) }, CropModel(aspect, weights))

    suspend fun score(weights: CropModelWeights, variant: Variant = Variant()): PaperScore = score(crops(weights, variant))

    fun score(crops: ModelCrops): PaperScore = score(targets, crops, bare, sizes, aspect)
}

/** Renders [paper] and reads its [labels] through [interpretation]. */
suspend fun labelledPaper(
    paper: Paper,
    pdf: MutoolPdf,
    labels: PaperLabels,
    interpretation: Map<Int, Interpretation>,
    aspect: Float,
): LabelledPaper {
    val density = analysisDensity(pdf.pageSizes, aspect)
    val rendered = pdf.pageSizes.indices.map { i ->
        val (w, h) = maskSize(pdf.pageSizes[i], density)
        pdf.renderMask(i, w, h)
    }
    // The crops the owner judged: the model at the weights the labels record.
    val judgedWeights = CropModelWeights().with(labels.labelledAgainst?.weights.orEmpty().mapValues { (k, v) ->
        if (k in setOf("maxWindows", "zoomIgnoredPages", "blockGap")) v.toInt().toString() else v.toString()
    })
    // ...and the rules ("reader", "tuned", or "reader+furniture+marks" for a variant's crops).
    val judgedVariant by lazy { Variant.parse(labels.labelledAgainst?.algorithm ?: "reader") }
    // Labels that recorded the crops shown (an experiment's, say) use them as they were.
    val recorded = labels.labelledAgainst?.centres?.takeIf { it.size == rendered.size }
    val (judgedScale, centres) = if (recorded != null) {
        labels.labelledAgainst!!.scale to recorded
    } else {
        val judged = cropsFor(Algorithm.READER, judgedVariant.config().prepare(rendered), CropModel(aspect, judgedWeights))
        judged.scale.toDouble() to judged.pages.indices.map { centreOf(judged.pages[it].window, pdf.pageSizes[it], aspect) }
    }
    val targets = targets(labels, interpretation, judgedScale, centres)
    return LabelledPaper(paper, rendered, Variant(furniture = true, marks = true).config().prepare(rendered), pdf.pageSizes, labels, targets, aspect)
}

/** A candidate's per-paper scores. */
class Trial(val weights: CropModelWeights, val variant: Variant, val scores: List<PaperScore>) {
    val units: Double get() = scores.sumOf { it.units }
    fun unitsWithout(paper: String): Double = scores.filter { it.paper != paper }.sumOf { it.units }
    fun unitsOf(paper: String): Double = scores.first { it.paper == paper }.units
}

/** Random weights around the model's: what the search tries. */
fun sampleWeights(random: Random, base: CropModelWeights = CropModelWeights()): CropModelWeights {
    fun logUniform(lo: Double, hi: Double) = exp(ln(lo) + random.nextDouble() * (ln(hi) - ln(lo)))
    return base.copy(
        beta = logUniform(0.01, 2.0),
        epsilon = if (random.nextDouble() < 0.15) 0.0 else logUniform(1.0, 3000.0),
        allowance = random.nextDouble(0.0, 0.03),
        gamma = logUniform(0.005, 0.5),
        delta2 = random.nextDouble(0.0, 0.1),
        kappa = if (random.nextDouble() < 0.2) 0.0 else logUniform(0.01, 1.0),
        blockGap = random.nextInt(1, 4),
        splitInk = logUniform(0.001, 0.03),
        zeta = if (random.nextDouble() < 0.3) 0.0 else logUniform(0.05, 5.0),
        autoMaxLost = logUniform(0.002, 0.03),
        autoMargin = random.nextDouble(-0.02, 0.04),
        zoomIgnoredPages = random.nextInt(0, 3),
        autoMaxPageCut = if (random.nextDouble() < 0.3) 1.0 else logUniform(0.004, 0.04),
    )
}

/** Scores [weights] and [variant] on every paper. */
suspend fun trial(papers: List<LabelledPaper>, weights: CropModelWeights, variant: Variant = Variant()): Trial =
    Trial(weights, variant, papers.map { it.score(weights, variant) })

/** The weights that differ from [base], as name=value. */
fun CropModelWeights.diff(base: CropModelWeights = CropModelWeights()): String {
    val a = toMap()
    val b = base.toMap()
    return a.filter { (k, v) -> b[k] != v }.entries.joinToString(", ") { (k, v) -> "$k=${"%.4g".format(v)}" }
        .ifEmpty { "(defaults)" }
}

fun loadLabelled(
    corpus: Map<String, Paper>,
    labelsDir: File,
    pdfs: File,
    aspect: Float,
    only: Set<String>?,
): List<LabelledPaper> {
    val interpretations = readInterpretations(File(labelsDir, "interpretation.tsv"))
    val files = labelsDir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
    return files.mapNotNull { file ->
        val labels = PaperLabels.fromText(file.readText())
        if (only != null && labels.paper !in only) return@mapNotNull null
        val paper = corpus[labels.paper] ?: return@mapNotNull null.also { System.err.println("${labels.paper}: not in the corpus") }
        if (labels.pages.values.all { it.isEmpty } && labels.idealScale == null) return@mapNotNull null
        runBlocking { labelledPaper(paper, MutoolPdf(File(pdfs, paper.file)), labels, interpretations[labels.paper].orEmpty(), aspect) }
    }
}
