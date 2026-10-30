package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.CropModel
import dev.sortes.app.zoom.MAX_ANALYZED_PAGES
import dev.sortes.app.zoom.PageBlocks
import dev.sortes.app.zoom.analysisDensity
import dev.sortes.app.zoom.maskSize
import dev.sortes.app.zoom.samplePages
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `bench --only=id,id [--configs=reader,tuned] [--repeat=5] [--corpus=] [--pdfs=] [--aspect=]`:
 * times the model on each paper's reader sample (at most 60 pages): the automatic zoom, and one
 * solve at a fixed scale, best of [repeat] runs after a warm-up, so changes to the solver can be
 * measured without rendering noise.
 */
fun benchMain(args: List<String>) {
    val opts = args.associate { arg ->
        require(arg.startsWith("--") && '=' in arg) { "Bad argument: $arg" }
        arg.removePrefix("--").substringBefore('=') to arg.substringAfter('=')
    }
    val known = setOf("only", "configs", "repeat", "corpus", "pdfs", "aspect", "scale")
    opts.keys.firstOrNull { it !in known }?.let { throw IllegalArgumentException("Unknown option --$it") }
    val home = System.getProperty("user.home")
    val corpus = readCorpus(File(opts["corpus"] ?: "corpus.tsv"))
    val pdfs = File(opts["pdfs"] ?: "$home/.cache/sortes-zoom-eval/pdf")
    val aspect = (opts["aspect"] ?: "0.75").toFloat()
    val repeat = (opts["repeat"] ?: "5").toInt()
    val scale = (opts["scale"] ?: "0.8").toFloat()
    val specs = (opts["configs"] ?: "reader,tuned").split(',').map { ConfigSpec.parse(it.trim()) }
    val only = requireNotNull(opts["only"]) { "--only=id,id is required" }.split(',').toSet()
    println("paper\tpages\tconfig\tauto ms\tsolves\tscale\tsolve ms at $scale")
    for (paper in corpus) {
        if (paper.id !in only) continue
        val pdf = MutoolPdf(File(pdfs, paper.file))
        val density = analysisDensity(pdf.pageSizes, aspect)
        val sample = samplePages(pdf.pageCount, MAX_ANALYZED_PAGES)
        val rendered = sample.map { i ->
            val (w, h) = maskSize(pdf.pageSizes[i], density)
            pdf.renderMask(i, w, h)
        }
        for (spec in specs) {
            val masks = spec.config.prepare(rendered)
            val model = CropModel(aspect, spec.config.weights)
            fun time(block: suspend () -> Unit): Long {
                runBlocking { block() }
                return (1..repeat).minOf {
                    val t = System.nanoTime()
                    runBlocking { block() }
                    (System.nanoTime() - t) / 1_000_000
                }
            }
            var chosen = 0f
            var solves = 0
            val auto = time {
                val p = model.prepare(masks)
                chosen = p.auto().scale
                solves = p.solves
            }
            val solve = time { model.prepare(masks).solve(scale) }
            // Segmentation alone, and the sizes of the blocks it finds.
            val gap = spec.config.weights.blockGap
            val segment = time { masks.forEach { PageBlocks.of(it, gap) } }
            val sizes = IntArray(8)
            for (m in masks) for (ink in PageBlocks.of(m, gap).inks) sizes[minOf(7, 32 - Integer.numberOfLeadingZeros(ink))]++
            println("${paper.id}\t${masks.size}\t${spec.label}\t$auto\t$solves\t${"%.3f".format(chosen)}\t$solve\tsegment $segment ms\tblocks by ink 1,2-3,4-7,8-15,16-31,32-63,64-127,128+: ${sizes.joinToString(",")}")
        }
    }
}
