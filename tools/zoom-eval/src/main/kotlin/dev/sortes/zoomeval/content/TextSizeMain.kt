package dev.sortes.zoomeval.content

import dev.sortes.app.zoom.analysisDensity
import dev.sortes.app.zoom.maskSize
import dev.sortes.zoomeval.MutoolPdf
import dev.sortes.zoomeval.readCorpus
import java.io.File

/** Validation: image-space text-size estimates per paper, in points, for comparison with the PDF's fonts. */
fun textSizeMain(args: List<String>) {
    val opts = args.associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=') }
    val corpus = readCorpus(File(opts["corpus"] ?: "corpus.tsv"))
    val pdfs = File(opts["pdfs"] ?: "${System.getProperty("user.home")}/.cache/sortes-zoom-eval/pdf")
    val only = opts["only"]?.split(',')?.toSet()
    val sample = (opts["pages"] ?: "12").toInt()
    val factor = (opts["factor"] ?: "4").toInt()
    println("paper\tcoarsePitchPt\tfinePitchPt\tglyphPt\tpages")
    for (paper in corpus) {
        if (only != null && paper.id !in only) continue
        if (paper.category.contains("scan", ignoreCase = true)) continue
        val pdf = MutoolPdf(File(pdfs, paper.file))
        val density = analysisDensity(pdf.pageSizes, 0.75f)
        val n = pdf.pageCount
        val pages = (0 until minOf(sample, n)).map { it * n / minOf(sample, n) }
        val coarse = ArrayList<Double?>()
        val fine = ArrayList<Double?>()
        val glyph = ArrayList<Double?>()
        for (i in pages) {
            val (w, h) = maskSize(pdf.pageSizes[i], density)
            val ptPerCell = pdf.pageSizes[i].height / h
            val m = pdf.renderMask(i, w, h)
            coarse += TextSize.linePitch(m)?.times(ptPerCell)
            val f = pdf.renderMask(i, w * factor, h * factor)
            fine += TextSize.linePitch(f)?.times(ptPerCell / factor)
            glyph += TextSize.glyphHeight(f)?.times(ptPerCell / factor)
        }
        fun fmt(v: Double?) = v?.let { "%.2f".format(it) } ?: "-"
        println("${paper.id}\t${fmt(TextSize.median(coarse))}\t${fmt(TextSize.median(fine))}\t${fmt(TextSize.median(glyph))}\t" +
            pages.indices.joinToString(",") { fmt(coarse[it]) + "/" + fmt(fine[it]) + "/" + fmt(glyph[it]) })
    }
}
