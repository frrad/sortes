package dev.sortes.zoomeval.labeller

import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.app.zoom.Region
import dev.sortes.app.zoom.ZoomScales
import dev.sortes.app.zoom.cutInk
import dev.sortes.app.zoom.padded
import dev.sortes.app.zoom.regionOn
import dev.sortes.zoomeval.Paper
import dev.sortes.zoomeval.PdfiumPdf
import dev.sortes.zoomeval.fit.centreOf
import dev.sortes.zoomeval.readerCrops
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** Width in pixels of each page image: the padded screen frame, white around the page. */
const val EXPORT_FRAME_WIDTH = 900

/**
 * Writes what the labeller shows for [paper]: `<out>/<id>/paper.json`, describing every page's crop
 * as the reader chooses it today, and `<out>/<id>/pNNN.png`, each page in its padded frame.
 */
suspend fun exportPaper(
    paper: Paper,
    pdf: PdfiumPdf,
    aspect: Float,
    commit: String,
    out: File,
    config: CropConfig = CropConfig.DEFAULT,
): JSONObject {
    val dir = File(out, paper.id).apply { mkdirs() }
    // Exactly the reader's crops; the cut ink is counted on the masks the model saw.
    val reader = readerCrops(pdf, aspect, config)
    val crops = reader.crops
    val masks = reader.masks
    val pages = JSONArray()
    for (i in masks.indices) {
        val size = pdf.pageSizes[i]
        val frame = size.padded(aspect)
        val crop = crops.pages[i]
        val image = "p%03d.png".format(i + 1)
        val k = EXPORT_FRAME_WIDTH / frame.width
        val frameHeight = (frame.height * k).roundToInt()
        writeFrameImage(
            pdf,
            i,
            size.width,
            size.height,
            frame.width,
            frame.height,
            k,
            File(dir, image),
        )
        pages.put(
            JSONObject()
                .put("number", i + 1)
                .put("image", image)
                .put("imageWidth", EXPORT_FRAME_WIDTH)
                .put("imageHeight", frameHeight)
                .put(
                    "page",
                    JSONObject()
                        .put("width", size.width.toDouble())
                        .put("height", size.height.toDouble()),
                )
                .put(
                    "frame",
                    JSONObject()
                        .put("width", frame.width.toDouble())
                        .put("height", frame.height.toDouble()),
                )
                .put("window", crop.windowIndex + 1)
                .put("region", crop.window.regionOn(size, aspect).toJson())
                .put(
                    "cut",
                    JSONArray(
                        cutInk(masks[i], size, crop.cells).map {
                            it.region.toJson().put("cells", it.cells)
                        }
                    ),
                )
                .put("cutPercent", crop.pageCut * 100)
                .put("offCentre", crop.offCentre)
        )
    }
    val json =
        JSONObject()
            .put("id", paper.id)
            .put("title", paper.title)
            .put("category", paper.category)
            .put("pageCount", masks.size)
            .put(
                "labelledAgainst",
                LabelledAgainst(
                        commit = commit,
                        algorithm = config.name,
                        weights = config.weights.toMap(),
                        scale = crops.scale.toString().toDouble(),
                        aspect = aspect.toDouble(),
                        centres =
                            masks.indices.map { i ->
                                centreOf(crops.pages[i].window, pdf.pageSizes[i], aspect)
                            },
                        parameters = config.text.parameters(),
                    )
                    .toJson(),
            )
            .put("windows", crops.k)
            .put("cutPercent", crops.cutFraction * 100)
            .put("scales", JSONArray(ZoomScales.defaultScales().map { it.toString().toDouble() }))
            .put("pages", pages)
    File(dir, "paper.json").writeText(canonical(json) + "\n")
    return json
}

/** [CropModelWeights] as name to value, as the labels record them. */
fun CropModelWeights.toMap(): Map<String, Double> =
    sortedMapOf(
        "alpha" to alpha,
        "beta" to beta,
        "epsilon" to epsilon,
        "allowance" to allowance,
        "gamma" to gamma,
        "delta" to delta,
        "delta2" to delta2,
        "maxWindows" to maxWindows.toDouble(),
        "kappa" to kappa,
        "blockGap" to blockGap.toDouble(),
        "splitInk" to splitInk,
        "zeta" to zeta,
        "autoMargin" to autoMargin,
    )

private fun Region.toJson(): JSONObject =
    JSONObject()
        .put("left", left.toDouble())
        .put("top", top.toDouble())
        .put("width", width.toDouble())
        .put("height", height.toDouble())

private fun writeFrameImage(
    pdf: PdfiumPdf,
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
    val page =
        pdf.renderImage(
            index,
            (pageWidth * k).roundToInt().coerceAtLeast(1),
            (pageHeight * k).roundToInt().coerceAtLeast(1),
        )
    g.drawImage(
        page,
        (((frameWidth - pageWidth) / 2) * k).roundToInt(),
        (((frameHeight - pageHeight) / 2) * k).roundToInt(),
        null,
    )
    g.dispose()
    ImageIO.write(frame, "png", file)
}
