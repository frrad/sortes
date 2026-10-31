package dev.sortes.zoomeval.fit

import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.app.zoom.IGNORABLE_INK_RULES
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.IsolatedMarks
import dev.sortes.app.zoom.ModelCrops
import dev.sortes.app.zoom.PageRenderer
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.paperRaster
import dev.sortes.zoomeval.MemoPages
import dev.sortes.zoomeval.Paper
import dev.sortes.zoomeval.PdfiumPdf
import dev.sortes.zoomeval.labeller.PaperLabels
import dev.sortes.zoomeval.labeller.toMap
import dev.sortes.zoomeval.readerCrops
import dev.sortes.zoomeval.weightValues
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random


/** A labelled paper, rendered once, with what its labels ask. */
class LabelledPaper(
    val paper: Paper,
    /** The paper's pages, analysis masks remembered: what the crops are made from. */
    val pages: PageRenderer,
    /** Every page's analysis mask, as rendered (before any ignorable-ink rules). */
    val rendered: List<InkMask>,
    /** Every page's analysis mask without furniture or marks: what scoring counts as ink, since the owner doesn't mind losing those. */
    val bare: List<InkMask>,
    val sizes: List<PageSize>,
    val labels: PaperLabels,
    val targets: PaperTargets,
    val aspect: Float,
) {
    /** The paper's crops under [config], exactly as the reader makes them. */
    suspend fun crops(config: CropConfig): ModelCrops = readerCrops(pages, aspect, config).crops

    suspend fun score(config: CropConfig): PaperScore = score(crops(config))

    fun score(crops: ModelCrops): PaperScore = score(targets, crops, bare, sizes, aspect)
}

/**
 * Renders [paper] and reads its [labels] through [interpretation]. The labels must record the
 * crops they judged (`labelledAgainst.centres`): those are the "stay put" targets.
 */
suspend fun labelledPaper(
    paper: Paper,
    pdf: PdfiumPdf,
    labels: PaperLabels,
    interpretation: Map<Int, Interpretation>,
    aspect: Float,
): LabelledPaper {
    // Every render is remembered, so trials don't re-render.
    val pages = MemoPages(pdf)
    val raster = paperRaster(pages, aspect)
    val rendered = pdf.pageSizes.indices.map { raster.coarse(it) }
    val judged = requireNotNull(labels.labelledAgainst?.takeIf { it.centres?.size == rendered.size }) {
        "${labels.paper}: the labels don't record the ${rendered.size} pages' judged crops (labelledAgainst.centres)"
    }
    val targets = targets(labels, interpretation, judged.scale, judged.centres!!)
    val bare = SCORING_RULES.prepare(rendered)
    return LabelledPaper(paper, pages, rendered, bare, pdf.pageSizes, labels, targets, aspect)
}

/**
 * What scoring ignores when it counts a page's ink: margin stamps, isolated marks and page
 * furniture, which the owner doesn't mind losing. Fixed, whatever the config being scored.
 */
val SCORING_RULES = CropConfig("scoring", IGNORABLE_INK_RULES + IsolatedMarks, furniture = true, CropConfig.DEFAULT.weights, CropConfig.DEFAULT.text)

/** A candidate's per-paper scores. */
class Trial(val config: CropConfig, val scores: List<PaperScore>) {
    val weights: CropModelWeights get() = config.weights
    /** The weights that differ from [CropConfig.DEFAULT]'s, both models'. */
    fun diff(): String = config.diff()
    val units: Double get() = scores.sumOf { it.units }
    fun unitsWithout(paper: String): Double = scores.filter { it.paper != paper }.sumOf { it.units }
    fun unitsOf(paper: String): Double = scores.first { it.paper == paper }.units
}

/** Random weights around [base]'s: what the search tries. */
fun sampleWeights(random: Random, base: CropModelWeights = CropConfig.DEFAULT.weights): CropModelWeights {
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
        zeta = logUniform(0.05, 5.0),
        autoMargin = random.nextDouble(-0.02, 0.04),
    )
}

/** Scores [config] on every paper. */
suspend fun trial(papers: List<LabelledPaper>, config: CropConfig): Trial =
    Trial(config, papers.map { it.score(config) })

/** The weights that differ from [base], as name=value. */
fun CropModelWeights.diff(base: CropModelWeights = CropConfig.DEFAULT.weights): String = diff(toMap(), base.toMap())

/** The weights, the crop model's and the text model's, that differ from [base]'s, as name=value. */
fun CropConfig.diff(base: CropConfig = CropConfig.DEFAULT): String = diff(weightValues(), base.weightValues())

private fun diff(a: Map<String, Double>, b: Map<String, Double>): String {
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
        runBlocking { labelledPaper(paper, PdfiumPdf(File(pdfs, paper.file)), labels, interpretations[labels.paper].orEmpty(), aspect) }
    }
}
