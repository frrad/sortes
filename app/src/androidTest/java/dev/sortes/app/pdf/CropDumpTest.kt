package dev.sortes.app.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sortes.app.zoom.PageRenderer
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.analyzePaper
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test of the app but a probe of the tablet's renderer, for `zoom-eval device-check`
 * (tools/zoom-eval/README.md, notes/renderer-fidelity.md): runs the reader's crop pipeline on
 * PdfRenderer for PDFs pushed to the app's external files dir (`fidelity/pdf/`), writing every
 * page's crop in `zoom-eval crops` format, and optionally every render's luminance as PGM, to
 * `fidelity/out/`. Skipped when no PDFs have been pushed, so it's inert in a normal test run.
 *
 * Args: `papers` (comma-separated file names; default all), `dump` (comma-separated file names
 * whose renders to save), `aspect` (default 0.75).
 */
@RunWith(AndroidJUnit4::class)
class CropDumpTest {

    @Test
    fun dumpCrops() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.getExternalFilesDir(null), "fidelity")
        val pdfDir = File(root, "pdf")
        val out = File(root, "out").apply { mkdirs() }
        val aspect = args.getString("aspect")?.toFloat() ?: 0.75f
        val dump = args.getString("dump")?.split(',')?.toSet() ?: emptySet()
        val names = args.getString("papers")?.split(',')
            ?: pdfDir.list().orEmpty().filter { it.endsWith(".pdf") }.sorted()
        assumeTrue("No PDFs pushed to $pdfDir", names.isNotEmpty())
        val summary = File(out, "summary.tsv")
        if (!summary.exists()) summary.writeText("paper\tpages\tanalysed\tscale\tk\tglyphPx\tmillis\tsolveMillis\n")
        for (name in names) {
            val id = name.removeSuffix(".pdf")
            val pages = PdfPages.open(File(pdfDir, name))
            val renders = if (name in dump) File(out, "renders/$id").apply { mkdirs() } else null
            val source = RecordingRenderer(pages, renders)
            try {
                val started = System.currentTimeMillis()
                val analysis = analyzePaper(source, aspect)
                val ms = System.currentTimeMillis() - started
                val crops = analysis.placeAll(analysis.auto).crops
                File(out, "$id.tsv").writeText(crops.pages.withIndex().joinToString("") { (i, c) ->
                    listOf(id, "device", i + 1, c.windowIndex, c.window.left, c.window.top, c.window.scale,
                        "${c.cells.left},${c.cells.top},${c.cells.right},${c.cells.bottom}", ms).joinToString("\t") + "\n"
                })
                summary.appendText(listOf(id, analysis.pageCount, analysis.pagesAnalyzed, analysis.auto.scale,
                    analysis.auto.k, analysis.glyphPx, ms, analysis.solveMillis).joinToString("\t") + "\n")
                source.stats?.let { File(renders, "colour.tsv").writeText(it.toString()) }
            } finally {
                pages.close()
            }
        }
    }

    /** Passes renders through, saving each as an 8-bit PGM of the pipeline's luminance. */
    private class RecordingRenderer(private val pages: PdfPages, private val dir: File?) : PageRenderer {
        val stats = dir?.let { StringBuilder("page\twidth\theight\tcolouredPixels\tmaxSpread\n") }
        override val pageCount get() = pages.pageCount
        override suspend fun pageSize(index: Int): PageSize = pages.pageSize(index)
        override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray {
            val pixels = pages.renderPixels(index, width, height)
            if (dir != null) {
                val grey = ByteArray(pixels.size)
                var coloured = 0
                var maxSpread = 0
                for (i in pixels.indices) {
                    val p = pixels[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val spread = maxOf(r, g, b) - minOf(r, g, b)
                    if (spread > 0) coloured++
                    if (spread > maxSpread) maxSpread = spread
                    grey[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
                }
                File(dir, "p${index + 1}_${width}x$height.pgm").outputStream().use {
                    it.write("P5\n$width $height\n255\n".toByteArray())
                    it.write(grey)
                }
                stats!!.append("${index + 1}\t$width\t$height\t$coloured\t$maxSpread\n")
            }
            return pixels
        }
    }
}
