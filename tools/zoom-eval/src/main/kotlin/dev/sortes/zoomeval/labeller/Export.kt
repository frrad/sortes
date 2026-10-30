package dev.sortes.zoomeval.labeller

import dev.sortes.app.zoom.CropAnalysis
import dev.sortes.app.zoom.CropModel
import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.app.zoom.ModelCrops
import dev.sortes.app.zoom.Region
import dev.sortes.app.zoom.analysisDensity
import dev.sortes.app.zoom.cutInk
import dev.sortes.app.zoom.maskSize
import dev.sortes.app.zoom.padded
import dev.sortes.app.zoom.regionOn
import dev.sortes.app.zoom.withoutIgnorableInk
import dev.sortes.zoomeval.Algorithm
import dev.sortes.zoomeval.MutoolPdf
import dev.sortes.zoomeval.Paper
import dev.sortes.zoomeval.cropsFor
import dev.sortes.zoomeval.fit.Variant
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Width in pixels of each page image: the padded screen frame, white around the page. */
const val EXPORT_FRAME_WIDTH = 900

/**
 * Writes what the labeller shows for [paper]: `<out>/<id>/paper.json`, describing every page's
 * crop as the reader chooses it today, and `<out>/<id>/pNNN.png`, each page in its padded frame.
 */
suspend fun exportPaper(
    paper: Paper,
    pdf: MutoolPdf,
    aspect: Float,
    weights: CropModelWeights,
    commit: String,
    out: File,
    variant: Variant = Variant(),
): JSONObject {
    val dir = File(out, paper.id).apply { mkdirs() }
    val density = analysisDensity(pdf.pageSizes, aspect)
    // The pages as the model sees them, which is also what its cut ink is counted on.
    val masks = variant.prepare(pdf.pageSizes.indices.map { i ->
        val (w, h) = maskSize(pdf.pageSizes[i], density)
        pdf.renderMask(i, w, h).withoutIgnorableInk()
    })
    val crops: ModelCrops = cropsFor(Algorithm.READER, masks, CropModel(aspect, weights))
    val pages = JSONArray()
    for (i in masks.indices) {
        val size = pdf.pageSizes[i]
        val frame = size.padded(aspect)
        val crop = crops.pages[i]
        val image = "p%03d.png".format(i + 1)
        val k = EXPORT_FRAME_WIDTH / frame.width
        val frameHeight = (frame.height * k).roundToInt()
        writeFrameImage(pdf, i, size.width, size.height, frame.width, frame.height, k, File(dir, image))
        pages.put(
            JSONObject()
                .put("number", i + 1)
                .put("image", image)
                .put("imageWidth", EXPORT_FRAME_WIDTH)
                .put("imageHeight", frameHeight)
                .put("page", JSONObject().put("width", size.width.toDouble()).put("height", size.height.toDouble()))
                .put("frame", JSONObject().put("width", frame.width.toDouble()).put("height", frame.height.toDouble()))
                .put("window", crop.windowIndex + 1)
                .put("region", crop.window.regionOn(size, aspect).toJson())
                .put("cut", JSONArray(cutInk(masks[i], size, crop.cells).map { it.region.toJson().put("cells", it.cells) }))
                .put("cutPercent", crop.pageCut * 100)
                .put("offCentre", crop.offCentre),
        )
    }
    val json = JSONObject()
        .put("id", paper.id)
        .put("title", paper.title)
        .put("category", paper.category)
        .put("pageCount", masks.size)
        .put("labelledAgainst", LabelledAgainst(
            commit = commit,
            algorithm = if (variant == Variant()) "reader" else "reader+$variant",
            weights = weights.toMap(),
            scale = crops.scale.toString().toDouble(),
            aspect = aspect.toDouble(),
        ).toJson())
        .put("windows", crops.k)
        .put("cutPercent", crops.cutFraction * 100)
        .put("scales", JSONArray(CropAnalysis.defaultScales().map { it.toString().toDouble() }))
        .put("pages", pages)
    File(dir, "paper.json").writeText(canonical(json) + "\n")
    return json
}

/** [CropModelWeights] as name to value, as the labels record them. */
fun CropModelWeights.toMap(): Map<String, Double> = sortedMapOf(
    "alpha" to alpha, "beta" to beta, "epsilon" to epsilon, "allowance" to allowance,
    "gamma" to gamma, "delta" to delta, "delta2" to delta2, "maxWindows" to maxWindows.toDouble(),
    "autoMaxLost" to autoMaxLost, "autoMargin" to autoMargin, "zoomIgnoredPages" to zoomIgnoredPages.toDouble(),
    "autoMaxPageCut" to autoMaxPageCut,
)

private fun Region.toJson(): JSONObject = JSONObject()
    .put("left", left.toDouble()).put("top", top.toDouble())
    .put("width", width.toDouble()).put("height", height.toDouble())

private fun writeFrameImage(
    pdf: MutoolPdf,
    index: Int,
    pageWidth: Float,
    pageHeight: Float,
    frameWidth: Float,
    frameHeight: Float,
    k: Float,
    file: File,
) {
    val w = (frameWidth * k).roundToInt()
    val h = (frameHeight * k).roundToInt()
    val frame = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = frame.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, w, h)
    val page = pdf.renderImage(index, (pageWidth * k).roundToInt().coerceAtLeast(1), (pageHeight * k).roundToInt().coerceAtLeast(1))
    g.drawImage(page, (((frameWidth - pageWidth) / 2) * k).roundToInt(), (((frameHeight - pageHeight) / 2) * k).roundToInt(), null)
    g.dispose()
    ImageIO.write(frame, "png", file)
}
