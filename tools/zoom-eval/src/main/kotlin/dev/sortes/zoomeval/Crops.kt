package dev.sortes.zoomeval

import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `crops --out=FILE [--configs=tuned+text] [--only=id,id] [--corpus=] [--pdfs=] [--aspect=]`:
 * every page's crop as the reader makes it ([readerCrops]), one TSV row per paper, config and
 * page, for diffing the crops of two commits.
 */
fun cropsMain(args: List<String>) = runBlocking {
    val opts = args.associate { arg ->
        require(arg.startsWith("--") && '=' in arg) { "Bad argument: $arg" }
        arg.removePrefix("--").substringBefore('=') to arg.substringAfter('=')
    }
    val known = setOf("out", "configs", "only", "corpus", "pdfs", "aspect")
    opts.keys.firstOrNull { it !in known }?.let { throw IllegalArgumentException("Unknown option --$it") }
    val home = System.getProperty("user.home")
    val corpus = readCorpus(File(opts["corpus"] ?: "corpus.tsv"))
    val pdfs = File(opts["pdfs"] ?: "$home/.cache/sortes-zoom-eval/pdf")
    val aspect = (opts["aspect"] ?: "0.75").toFloat()
    val specs = (opts["configs"] ?: "tuned+text").split(',').map { ConfigSpec.parse(it.trim()) }
    val only = opts["only"]?.split(',')?.toSet()
    val out = File(requireNotNull(opts["out"]) { "--out=FILE is required" })
    out.writeText("paper\tconfig\tpage\twindow\tleft\ttop\tscale\tcells\tmillis\n")
    for (paper in corpus) {
        if (only != null && paper.id !in only) continue
        val pdf = MutoolPdf(File(pdfs, paper.file))
        for (spec in specs) {
            val started = System.currentTimeMillis()
            val crops = readerCrops(pdf, aspect, spec.config).crops
            val ms = System.currentTimeMillis() - started
            out.appendText(crops.pages.withIndex().joinToString("") { (i, c) ->
                listOf(paper.id, spec.label, i + 1, c.windowIndex, c.window.left, c.window.top, c.window.scale,
                    "${c.cells.left},${c.cells.top},${c.cells.right},${c.cells.bottom}", ms).joinToString("\t") + "\n"
            })
            println("${paper.id} ${spec.label} ${crops.scale} K=${crops.k} ${ms}ms")
        }
    }
}
