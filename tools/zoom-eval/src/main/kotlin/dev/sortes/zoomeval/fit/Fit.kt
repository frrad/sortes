package dev.sortes.zoomeval.fit

import dev.sortes.app.zoom.CropModel
import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.IsolatedMarks
import dev.sortes.app.zoom.ModelCrops
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.analysisDensity
import dev.sortes.app.zoom.maskSize
import dev.sortes.app.zoom.withoutFurniture
import dev.sortes.app.zoom.withoutIgnorableInk
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

    /** [masks] as this variant's model sees them. */
    fun prepare(masks: List<InkMask>): List<InkMask> {
        val unmarked = if (marks) masks.map { IsolatedMarks.strip(it) } else masks
        return if (furniture) unmarked.withoutFurniture() else unmarked
    }

    companion object {
        fun parse(name: String) = when (name) {
            "reader" -> Variant()
            else -> name.split('+').fold(Variant()) { v, part ->
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
    /** The pages as the reader sees them today: without ignorable ink. */
    val masks: List<InkMask>,
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
        cropsFor(Algorithm.READER, prepared.getOrPut(variant) { variant.prepare(masks) }, CropModel(aspect, weights))

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
    val masks = pdf.pageSizes.indices.map { i ->
        val (w, h) = maskSize(pdf.pageSizes[i], density)
        pdf.renderMask(i, w, h).withoutIgnorableInk()
    }
    // The crops the owner judged: the model at the weights the labels record.
    val judgedWeights = CropModelWeights().with(labels.labelledAgainst?.weights.orEmpty().mapValues { (k, v) ->
        if (k in setOf("maxWindows", "zoomIgnoredPages")) v.toInt().toString() else v.toString()
    })
    // ...and the model variant ("reader+furniture+marks" for crops made with one).
    val judgedVariant = Variant.parse(labels.labelledAgainst?.algorithm?.removePrefix("reader+")?.takeIf { it != "reader" } ?: "reader")
    val judged = cropsFor(Algorithm.READER, judgedVariant.prepare(masks), CropModel(aspect, judgedWeights))
    val centres = judged.pages.indices.map { centreOf(judged.pages[it].window, pdf.pageSizes[it], aspect) }
    val targets = targets(labels, interpretation, judged.scale.toDouble(), centres)
    return LabelledPaper(paper, masks, Variant(furniture = true, marks = true).prepare(masks), pdf.pageSizes, labels, targets, aspect)
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
